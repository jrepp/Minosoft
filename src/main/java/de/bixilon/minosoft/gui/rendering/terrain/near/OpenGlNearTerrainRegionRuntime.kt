/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.terrain.near

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.Rendering
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshBuilder
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.storage.OpenGlTerrainRegionDevice as SharedOpenGlTerrainRegionDevice
import de.bixilon.minosoft.gui.rendering.terrain.storage.OpenGlTerrainStagingBuffer as SharedOpenGlTerrainStagingBuffer
import de.bixilon.minosoft.gui.rendering.terrain.storage.OpenGlTerrainSubmissionCompletion as SharedOpenGlTerrainSubmissionCompletion
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainProcessScopeId
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.storage.TerrainBatchCache
import de.bixilon.minosoft.terrain.runtime.storage.TerrainDrawBatch
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionKey
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionExtent
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionStorage
import de.bixilon.minosoft.terrain.runtime.storage.TerrainViewKey
import org.lwjgl.opengl.GL32.glFinish
import java.util.concurrent.atomic.AtomicLong

data class NearTerrainRegionMetrics(
    val regions: Int,
    val activePages: Int,
    val retiredPages: Int,
    val residentBytes: Long,
    val retiredBytes: Long,
    val vertexAllocatedBytes: Long,
    val indexAllocatedBytes: Long,
    val vertexHighWaterBytes: Long,
    val indexHighWaterBytes: Long,
    val stagingCapacityBytes: Int,
    val uploadedBytes: Long,
    val uploadNanos: Long,
    val publications: Long,
    val allocationFailures: Long,
    val uploadFailures: Long,
    val batchCacheEntries: Int,
    val batchBuilds: Long,
    val batchHits: Long,
    val batchEvictions: Long,
    val drawBatches: Int,
    val drawCommands: Int,
    val drawVertices: Long,
    val pendingSubmissionFences: Int,
)

/** Production adapter for the transactional near-terrain region path. */
class OpenGlNearTerrainRegionRuntime private constructor(
    private val context: RenderContext,
    private val system: OpenGlRenderSystem,
) : AutoCloseable {
    private data class Region(
        val device: SharedOpenGlTerrainRegionDevice,
        val storage: TerrainRegionStorage,
    )

    private val batchCache = TerrainBatchCache()
    private val staging = SharedOpenGlTerrainStagingBuffer(VERTEX_CAPACITY_BYTES)
    private val regions = LinkedHashMap<TerrainRegionKey, Region>()
    private val submittedBatches = ArrayList<TerrainDrawBatch>()
    private var completion: SharedOpenGlTerrainSubmissionCompletion? = null
    private var deviceRuntime: TerrainDeviceRuntimeId? = null
    private var layoutGeneration = -1L
    private var nextSerial = 1L
    private var closed = false
    private var frameDrawBatches = 0
    private var frameDrawCommands = 0
    private var frameDrawVertices = 0L
    private var lastDrawBatches = 0
    private var lastDrawCommands = 0
    private var lastDrawVertices = 0L

    fun prepareFrame() {
        check(!closed) { "Near terrain region runtime is closed" }
        check(submittedBatches.isEmpty()) { "Previous near terrain frame was not finished" }
        frameDrawBatches = 0
        frameDrawCommands = 0
        frameDrawVertices = 0L
    }

    fun publish(mesh: ChunkMeshes): Boolean {
        check(!closed) { "Near terrain region runtime is closed" }
        val artifact = mesh.artifact ?: return false
        ensureGeneration(artifact.identity.layoutGeneration)
        val key = TerrainRegionKey.containing(artifact.identity.page, REGION_EXTENT)
        var created = false
        val region = regions[key] ?: run {
            if (regions.size >= MAX_REGIONS) return false
            created = true
            createRegion(key).also { regions[key] = it }
        }
        val published = try {
            region.storage.publish(artifact)
        } catch (failure: Throwable) {
            if (created) {
                regions.remove(key)
                try {
                    region.device.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
        if (published == null && created) {
            regions.remove(key)
            region.device.close()
        }
        return published != null
    }

    fun remove(mesh: ChunkMeshes) {
        val page = mesh.terrainIdentity?.page ?: return
        val key = TerrainRegionKey.containing(page, REGION_EXTENT)
        regions[key]?.storage?.remove(page)
    }

    fun hasVisibleMaterial(material: TerrainMaterialClass, meshes: Collection<ChunkMeshes>): Boolean {
        val semantic = material.semanticMaterial
        return meshes.any { it.regionBacked && semantic in it.terrainMaterials }
    }

    fun submit(
        view: RenderViewId,
        material: TerrainMaterialClass,
        meshes: Collection<ChunkMeshes>,
        materialGeneration: Long,
    ) {
        if (meshes.isEmpty() || layoutGeneration < 0L) return
        val semantic = material.semanticMaterial
        val ordered = if (material == TerrainMaterialClass.TRANSLUCENT) {
            meshes.sortedByDescending(ChunkMeshes::distance)
        } else {
            meshes.sortedBy(ChunkMeshes::distance)
        }
        val byRegion = ordered.mapNotNull { it.terrainIdentity?.page }
            .groupBy { TerrainRegionKey.containing(it, REGION_EXTENT) }
        for ((key, pages) in byRegion) {
            val region = regions[key] ?: continue
            val batch = batchCache.batch(
                storage = region.storage,
                material = semantic,
                view = TerrainViewKey(view.value),
                orderedPages = pages,
                layoutGeneration = layoutGeneration,
                materialGeneration = materialGeneration,
            ) ?: continue
            try {
                val deviceBatches = region.device.draw(batch)
                submittedBatches += batch
                frameDrawBatches = Math.addExact(frameDrawBatches, deviceBatches)
                frameDrawCommands = Math.addExact(frameDrawCommands, batch.commands.size)
                frameDrawVertices = Math.addExact(
                    frameDrawVertices,
                    batch.commands.sumOf { it.indexCount.toLong() },
                )
            } catch (failure: Throwable) {
                batch.close()
                throw failure
            }
        }
    }

    fun finishFrame() {
        collectStorage()
        lastDrawBatches = frameDrawBatches
        lastDrawCommands = frameDrawCommands
        lastDrawVertices = frameDrawVertices
        if (submittedBatches.isEmpty()) return
        var failure: Throwable? = null
        try {
            val serial = TerrainSubmissionSerial(nextSerial)
            nextSerial = Math.addExact(nextSerial, 1L)
            val completion = checkNotNull(completion)
            val submission = completion.fence(serial)
            for (batch in submittedBatches) {
                try {
                    batch.submit(submission)
                } catch (error: Throwable) {
                    failure = combineCleanupFailure(failure, error)
                }
            }
        } catch (error: Throwable) {
            failure = combineCleanupFailure(failure, error)
        }
        try {
            closeSubmittedBatches()
        } catch (error: Throwable) {
            failure = combineCleanupFailure(failure, error)
        }
        if (failure != null) throw failure
    }

    /** Runs only at the context-current post-draw boundary. */
    private fun collectStorage() {
        completion?.collect()
        val iterator = regions.iterator()
        var removed = false
        while (iterator.hasNext()) {
            val region = iterator.next().value
            region.storage.collectRetired()
            val metrics = region.storage.metrics()
            if (metrics.activePages != 0 || metrics.retiredPages != 0) continue
            region.device.close()
            iterator.remove()
            removed = true
        }
        if (removed) batchCache.clear()
    }

    fun clear() {
        if (Thread.currentThread() !== context.thread || Rendering.currentContext !== context) {
            // A JVM shutdown hook can disconnect the play session after the
            // render context has already stopped being current. Calling even
            // glFinish in that state aborts the VM inside LWJGL. Context
            // destruction owns these device objects, so only abandon the
            // logical references on this path.
            submittedBatches.clear()
            regions.clear()
            completion = null
            deviceRuntime = null
            layoutGeneration = -1L
            batchCache.clear()
            frameDrawBatches = 0
            frameDrawCommands = 0
            frameDrawVertices = 0L
            lastDrawBatches = 0
            lastDrawCommands = 0
            lastDrawVertices = 0L
            return
        }
        var failure: Throwable? = null
        if (submittedBatches.isNotEmpty()) {
            // Deletion during teardown is legal, but complete current commands
            // before releasing logical leases without a retirement serial.
            try {
                gl { glFinish() }
            } catch (error: Throwable) {
                failure = error
            }
            try {
                closeSubmittedBatches()
            } catch (error: Throwable) {
                failure = combineCleanupFailure(failure, error)
            }
        }
        for (region in regions.values) {
            try {
                region.device.close()
            } catch (error: Throwable) {
                failure = combineCleanupFailure(failure, error)
            }
        }
        regions.clear()
        try {
            completion?.close()
        } catch (error: Throwable) {
            failure = combineCleanupFailure(failure, error)
        }
        completion = null
        deviceRuntime = null
        layoutGeneration = -1L
        batchCache.clear()
        frameDrawBatches = 0
        frameDrawCommands = 0
        frameDrawVertices = 0L
        lastDrawBatches = 0
        lastDrawCommands = 0
        lastDrawVertices = 0L
        if (failure != null) throw failure
    }

    fun metrics(): NearTerrainRegionMetrics {
        val storage = regions.values.map { it.storage.metrics() }
        val batches = batchCache.metrics()
        return NearTerrainRegionMetrics(
            regions = regions.size,
            activePages = storage.sumOf { it.activePages },
            retiredPages = storage.sumOf { it.retiredPages },
            residentBytes = storage.sumOf { it.residentBytes },
            retiredBytes = storage.sumOf { it.retiredBytes },
            vertexAllocatedBytes = storage.sumOf { it.vertexAllocatedBytes.toLong() },
            indexAllocatedBytes = storage.sumOf { it.indexAllocatedBytes.toLong() },
            vertexHighWaterBytes = storage.sumOf { it.vertexHighWaterBytes.toLong() },
            indexHighWaterBytes = storage.sumOf { it.indexHighWaterBytes.toLong() },
            stagingCapacityBytes = staging.capacityBytes,
            uploadedBytes = storage.sumOf { it.uploadedBytes },
            uploadNanos = storage.sumOf { it.uploadNanos },
            publications = storage.sumOf { it.publications },
            allocationFailures = storage.sumOf { it.allocationFailures },
            uploadFailures = storage.sumOf { it.uploadFailures },
            batchCacheEntries = batches.entries,
            batchBuilds = batches.builds,
            batchHits = batches.hits,
            batchEvictions = batches.evictions,
            drawBatches = lastDrawBatches,
            drawCommands = lastDrawCommands,
            drawVertices = lastDrawVertices,
            pendingSubmissionFences = completion?.pendingFences ?: 0,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            clear()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            staging.close()
        } catch (error: Throwable) {
            failure = combineCleanupFailure(failure, error)
        }
        if (failure != null) throw failure
    }

    private fun ensureGeneration(generation: Long) {
        if (layoutGeneration == generation) return
        clear()
        layoutGeneration = generation
        val runtime = TerrainDeviceRuntimeId(
            PROCESS_SCOPE,
            DEVICE_GENERATIONS.getAndUpdate { Math.incrementExact(it) },
        )
        deviceRuntime = runtime
        completion = SharedOpenGlTerrainSubmissionCompletion(context, runtime)
    }

    private fun createRegion(key: TerrainRegionKey): Region {
        val runtime = checkNotNull(deviceRuntime)
        val completion = checkNotNull(completion)
        val device = SharedOpenGlTerrainRegionDevice(
            system = system,
            struct = ChunkMeshBuilder.ChunkMeshStruct,
            deviceRuntime = runtime,
            layoutGeneration = layoutGeneration,
            vertexCapacityBytes = VERTEX_CAPACITY_BYTES,
            indexCapacityBytes = INDEX_CAPACITY_BYTES,
            staging = staging,
        )
        return Region(
            device,
            TerrainRegionStorage(key, REGION_EXTENT, device, completion),
        )
    }

    private fun closeSubmittedBatches() {
        var failure: Throwable? = null
        try {
            for (batch in submittedBatches.asReversed()) {
                try {
                    batch.close()
                } catch (error: Throwable) {
                    failure = combineCleanupFailure(failure, error)
                }
            }
        } finally {
            submittedBatches.clear()
        }
        if (failure != null) throw failure
    }

    private fun combineCleanupFailure(primary: Throwable?, next: Throwable): Throwable {
        if (primary == null) return next
        primary.addSuppressed(next)
        return primary
    }

    companion object {
        // Keep the same 320 MiB hard ceiling while giving one 8x32x8 region
        // enough room for the ordinary surface-section population.  The old
        // 8+2 MiB split exhausted an individual region after roughly forty
        // pages even though the process-wide ceiling was mostly unused, so a
        // completed page could remain at the head of the upload queue forever.
        private const val MAX_REGIONS = 16
        private const val VERTEX_CAPACITY_BYTES = 16 * 1024 * 1024
        private const val INDEX_CAPACITY_BYTES = 4 * 1024 * 1024
        private val PROCESS_SCOPE = TerrainProcessScopeId(0L)
        private val REGION_EXTENT = TerrainRegionExtent(8, 32, 8)
        private val DEVICE_GENERATIONS = AtomicLong(1L)

        fun create(context: RenderContext): OpenGlNearTerrainRegionRuntime? {
            if (!NearTerrainArtifactCapture.regionStorageEnabled) return null
            val system = context.system as? OpenGlRenderSystem ?: return null
            return OpenGlNearTerrainRegionRuntime(context, system)
        }
    }
}

private val TerrainMaterialClass.semanticMaterial: TerrainSemanticMaterialId
    get() = TerrainSemanticMaterialId(
        when (this) {
            TerrainMaterialClass.OPAQUE -> "minosoft:terrain/opaque"
            TerrainMaterialClass.CUTOUT -> "minosoft:terrain/cutout"
            TerrainMaterialClass.TRANSLUCENT -> "minosoft:terrain/translucent"
            TerrainMaterialClass.EMISSIVE_ADDITIVE -> "minosoft:terrain/text"
        },
    )
