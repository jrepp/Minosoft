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
import de.bixilon.minosoft.gui.rendering.terrain.storage.TerrainRegionFrameSubmission
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageRangeDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageVisibilityDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionKey
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionExtent
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionStorage
import de.bixilon.minosoft.terrain.runtime.storage.TerrainResidencyTarget
import de.bixilon.minosoft.terrain.runtime.storage.TerrainSelectionPublication
import de.bixilon.minosoft.terrain.runtime.storage.TerrainSelectionPublicationSnapshot
import de.bixilon.minosoft.terrain.runtime.storage.TerrainViewKey
import org.lwjgl.opengl.GL32.glFinish

data class NearTerrainRegionMetrics(
    val deviceCapacityBytes: Long,
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
    val cpuLeaseCount: Int,
    val pendingSubmissionCount: Int,
    val failedSubmissionCount: Int,
    val invalidatedSubmissionCount: Int,
    val retiredCpuLeasedPages: Int,
    val retiredPendingSubmissionPages: Int,
    val retiredFailedSubmissionPages: Int,
    val retiredDeviceInvalidatedPages: Int,
    val deviceInvalidations: Long,
    val batchCacheEntries: Int,
    val batchBuilds: Long,
    val batchHits: Long,
    val batchEvictions: Long,
    val drawBatches: Int,
    val drawCommands: Int,
    val drawVertices: Long,
    val pendingSubmissionFences: Int,
)

data class NearTerrainPublicationDiagnostics(
    val storageGeneration: Long,
    val residentPageCount: Int,
    val selection: TerrainSelectionPublicationSnapshot,
)

data class NearTerrainPagePublicationDiagnostic(
    val publicationRevision: Long,
    val artifactDigest: String,
    val ranges: List<TerrainPageRangeDiagnosticSnapshot>,
    val visibility: List<TerrainPageVisibilityDiagnosticSnapshot>,
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

    private class ViewPlan(
        val source: Collection<ChunkMeshes>,
        val residentRevision: Long,
        val activePages: Set<TerrainPageKey>,
    ) {
        var frontToBack: Map<TerrainRegionKey, List<TerrainPageKey>>? = null
        var backToFront: Map<TerrainRegionKey, List<TerrainPageKey>>? = null
    }

    private val selectionPublication = TerrainSelectionPublication()
    private val staging = SharedOpenGlTerrainStagingBuffer(VERTEX_CAPACITY_BYTES)
    private val regions = LinkedHashMap<TerrainRegionKey, Region>()
    private val residentVersions = LinkedHashMap<TerrainPageKey, Long>()
    private val frameSubmission = TerrainRegionFrameSubmission(context) { checkNotNull(completion) }
    private val frameSelectionViews = HashSet<TerrainViewKey>()
    private val frameViewPlans = HashMap<TerrainViewKey, ViewPlan>()
    private val cachedViewPlans = HashMap<TerrainViewKey, ViewPlan>()
    private var completion: SharedOpenGlTerrainSubmissionCompletion? = null
    private var residentRevision = 0L
    private var layoutGeneration = -1L
    private var closed = false

    fun prepareFrame() {
        check(!closed) { "Near terrain region runtime is closed" }
        frameSubmission.beginFrame()
        frameSelectionViews.clear()
        frameViewPlans.clear()
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
        if (published != null) {
            residentVersions[published.key] = published.publicationId
            residentRevision = Math.addExact(residentRevision, 1L)
            mesh.recordArtifactDigest(published.digest)
        }
        return published != null
    }

    fun remove(mesh: ChunkMeshes) {
        val page = mesh.terrainIdentity?.page ?: return
        val key = TerrainRegionKey.containing(page, REGION_EXTENT)
        if (regions[key]?.storage?.remove(page) == true) {
            residentVersions.remove(page)
            residentRevision = Math.addExact(residentRevision, 1L)
        }
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
        val viewKey = TerrainViewKey(view.value)
        if (frameSelectionViews.add(viewKey)) {
            val cached = cachedViewPlans[viewKey]
            val plan = if (cached != null && cached.source === meshes && cached.residentRevision == residentRevision) {
                cached
            } else {
                buildViewPlan(viewKey, meshes).also { cachedViewPlans[viewKey] = it }
            }
            frameViewPlans[viewKey] = plan
        }
        val plan = frameViewPlans[viewKey] ?: return
        val activePages = plan.activePages
        if (activePages.isEmpty() || layoutGeneration < 0L) return
        val semantic = material.semanticMaterial
        val byRegion = groupedPages(plan, material == TerrainMaterialClass.TRANSLUCENT)
        for ((key, pages) in byRegion) {
            val region = regions[key] ?: continue
            frameSubmission.draw(
                device = region.device,
                storage = region.storage,
                material = semantic,
                view = viewKey,
                orderedPages = pages,
                layoutGeneration = layoutGeneration,
                materialGeneration = materialGeneration,
            )
        }
    }

    private fun buildViewPlan(view: TerrainViewKey, meshes: Collection<ChunkMeshes>): ViewPlan {
        val requestedPages = meshes.asSequence()
            .mapNotNull { it.terrainIdentity?.page }
            .distinct()
            .toList()
        val targets = requestedPages.mapNotNull { page ->
            residentVersions[page]?.let { TerrainResidencyTarget(page, it) }
        }
        selectionPublication.desireAvailable(view, requestedPages, targets)
        selectionPublication.promote(view, residentVersions)
        return ViewPlan(
            source = meshes,
            residentRevision = residentRevision,
            activePages = selectionPublication.active(view).toHashSet(),
        )
    }

    private fun groupedPages(
        plan: ViewPlan,
        backToFront: Boolean,
    ): Map<TerrainRegionKey, List<TerrainPageKey>> {
        val cached = if (backToFront) plan.backToFront else plan.frontToBack
        if (cached != null) return cached
        val ordered = if (backToFront) {
            plan.source.sortedByDescending(ChunkMeshes::distance)
        } else {
            plan.source.sortedBy(ChunkMeshes::distance)
        }
        val grouped = ordered.asSequence()
            .mapNotNull { it.terrainIdentity?.page }
            .filter(plan.activePages::contains)
            .groupBy { TerrainRegionKey.containing(it, REGION_EXTENT) }
        if (backToFront) plan.backToFront = grouped else plan.frontToBack = grouped
        return grouped
    }

    fun finishFrame() {
        selectionPublication.snapshot(residentVersions).views
            .asSequence()
            .map { it.view }
            .filterNot(frameSelectionViews::contains)
            .forEach { view ->
                selectionPublication.desire(view, emptyList())
                selectionPublication.promote(view, residentVersions)
                cachedViewPlans.remove(view)
            }
        collectStorage()
        frameSubmission.finishFrame()
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
        if (removed) frameSubmission.clearCache()
    }

    /** Advances prior-frame ownership at a context-current diagnostic boundary. */
    fun collectSubmissionCompletions() {
        check(!closed) { "Near terrain region runtime is closed" }
        collectStorage()
    }

    fun clear() {
        if (Thread.currentThread() !== context.thread || Rendering.currentContext !== context) {
            // A JVM shutdown hook can disconnect the play session after the
            // render context has already stopped being current. Calling even
            // glFinish in that state aborts the VM inside LWJGL. Context
            // destruction owns these device objects, so only abandon the
            // logical references on this path.
            frameSubmission.abandon()
            frameSelectionViews.clear()
            frameViewPlans.clear()
            cachedViewPlans.clear()
            regions.clear()
            residentVersions.clear()
            residentRevision = 0L
            completion = null
            layoutGeneration = -1L
            selectionPublication.clear()
            return
        }
        var failure: Throwable? = null
        if (frameSubmission.hasSubmittedBatches) {
            // Deletion during teardown is legal, but complete current commands
            // before releasing logical leases without a retirement serial.
            try {
                gl { glFinish() }
            } catch (error: Throwable) {
                failure = error
            }
            try {
                frameSubmission.closeSubmittedBatches()
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
        frameSelectionViews.clear()
        frameViewPlans.clear()
        cachedViewPlans.clear()
        residentVersions.clear()
        residentRevision = 0L
        layoutGeneration = -1L
        frameSubmission.clearCache()
        selectionPublication.clear()
        frameSubmission.resetMetrics()
        if (failure != null) throw failure
    }

    fun metrics(): NearTerrainRegionMetrics {
        val storage = regions.values.map { it.storage.metrics() }
        val batches = frameSubmission.batchMetrics()
        val draws = frameSubmission.metrics()
        return NearTerrainRegionMetrics(
            deviceCapacityBytes = Math.addExact(
                Math.multiplyExact(
                    MAX_REGIONS.toLong(),
                    Math.addExact(VERTEX_CAPACITY_BYTES, INDEX_CAPACITY_BYTES).toLong(),
                ),
                staging.capacityBytes.toLong(),
            ),
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
            cpuLeaseCount = storage.fold(0) { total, metrics -> Math.addExact(total, metrics.cpuLeaseCount) },
            pendingSubmissionCount = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.pendingSubmissionCount)
            },
            failedSubmissionCount = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.failedSubmissionCount)
            },
            invalidatedSubmissionCount = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.invalidatedSubmissionCount)
            },
            retiredCpuLeasedPages = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredCpuLeasedPages)
            },
            retiredPendingSubmissionPages = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredPendingSubmissionPages)
            },
            retiredFailedSubmissionPages = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredFailedSubmissionPages)
            },
            retiredDeviceInvalidatedPages = storage.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredDeviceInvalidatedPages)
            },
            deviceInvalidations = storage.sumOf { it.deviceInvalidations },
            batchCacheEntries = batches.entries,
            batchBuilds = batches.builds,
            batchHits = batches.hits,
            batchEvictions = batches.evictions,
            drawBatches = draws.drawBatches,
            drawCommands = draws.drawCommands,
            drawVertices = draws.drawVertices,
            pendingSubmissionFences = completion?.pendingFences ?: 0,
        )
    }

    fun publicationDiagnostics(): NearTerrainPublicationDiagnostics {
        val storageGeneration = regions.values.fold(0L) { total, region ->
            Math.addExact(total, region.storage.metrics().publicationGeneration)
        }
        return NearTerrainPublicationDiagnostics(
            storageGeneration = storageGeneration,
            residentPageCount = residentVersions.size,
            selection = selectionPublication.snapshot(residentVersions),
        )
    }

    fun pagePublicationDiagnostics(): Map<TerrainPageKey, NearTerrainPagePublicationDiagnostic> {
        val views = listOf(TerrainViewKey(RenderViewId.MAIN.value), TerrainViewKey("minosoft:shadow"))
        val selected = views.associateWith(selectionPublication::active)
        return regions.values.asSequence()
            .flatMap { it.storage.snapshot().values.asSequence() }
            .associate { page ->
                page.key to NearTerrainPagePublicationDiagnostic(
                    publicationRevision = page.publicationId,
                    artifactDigest = page.digest.encodedValue,
                    ranges = page.streams.map { stream ->
                        TerrainPageRangeDiagnosticSnapshot(
                            partitionId = stream.partitionId,
                            vertexOffsetBytes = stream.vertexRange.offset,
                            vertexLengthBytes = stream.vertexRange.length,
                            indexOffsetBytes = stream.indexRange.offset,
                            indexLengthBytes = stream.indexRange.length,
                        )
                    },
                    visibility = views.map { view ->
                        TerrainPageVisibilityDiagnosticSnapshot(
                            viewId = view.value,
                            selected = page.key in selected.getValue(view),
                            masked = false,
                        )
                    },
                )
            }
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
        val runtime = context.terrainSubmissions.deviceRuntime
        completion = SharedOpenGlTerrainSubmissionCompletion(context, runtime)
    }

    private fun createRegion(key: TerrainRegionKey): Region {
        val runtime = context.terrainSubmissions.deviceRuntime
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
        private val REGION_EXTENT = TerrainRegionExtent(8, 32, 8)

        fun create(context: RenderContext): OpenGlNearTerrainRegionRuntime? {
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
            TerrainMaterialClass.DISTANT_WATER -> "minosoft:terrain/distant-water"
        },
    )
