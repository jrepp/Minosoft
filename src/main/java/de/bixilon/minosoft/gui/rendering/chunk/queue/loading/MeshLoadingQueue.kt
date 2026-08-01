/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.chunk.queue.loading

import de.bixilon.kutil.concurrent.lock.Lock
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.profiler.stack.StackedProfiler.Companion.invoke
import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.kutil.unit.Bytes.Companion.bytes
import de.bixilon.kutil.unit.Bytes.Companion.gigabytes
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause
import de.bixilon.minosoft.gui.rendering.chunk.util.ChunkRendererUtil.maxBusyTime
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainProductionPhase
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAcceptanceFault

internal data class NearUploadPageRegistrySnapshot(
    val generation: Long,
    val identities: List<TerrainBuildIdentity>,
)

class MeshLoadingQueue(
    private val renderer: ChunkRenderer,
) {
    private val comparator = LoadingQueueComparator()
    private val meshes = ArrayDeque<ChunkMeshes>(100)
    private val positions: MutableSet<SectionPosition> = HashSet()
    private val lock = Lock.lock()
    private var diagnosticGeneration = 0L


    val max = if (Runtime.getRuntime().maxMemory().bytes > 1.gigabytes) 120 else 60
    val size get() = lock.locked { meshes.size }


    var updates = 0
        get() {
            val value = field
            field = 0
            return value
        }



    fun sort() = lock.locked {
        comparator.update(renderer.visibility.eyePosition)
        meshes.sortWith(comparator)
    }

    fun work() {
        if (size == 0) return
        val start = now()
        val maxTime = renderer.maxBusyTime

        var index = 0
        var admittedBytes = 0L
        while (true) {
            if (index > 0 && now() - start >= maxTime) break
            val mesh = lock.locked {
                if (meshes.isEmpty()) return@locked null
                val first = meshes.first()
                val nextBytes = if (Long.MAX_VALUE - admittedBytes < first.outputBytes) {
                    Long.MAX_VALUE
                } else {
                    admittedBytes + first.outputBytes
                }
                if (index > 0 && nextBytes > MAX_UPLOAD_BYTES_PER_FRAME) return@locked null
                this.positions -= first.position
                meshes.removeFirst().also {
                    diagnosticGeneration = Math.incrementExact(diagnosticGeneration)
                    renderer.terrainPerformance.pendingUploads(meshes.size)
                }
            } ?: break
            index++

            val selectedGeneration = renderer.context.shaderPipeline.selection().fingerprint
            if (mesh.materialGeneration != selectedGeneration || !isCurrent(mesh)) {
                renderer.terrainPerformance.stale()
                discard(mesh)
                renderer.invalidate(
                    mesh.section,
                    TerrainBuildCause.RESOURCE_GENERATION_CHANGE,
                    advanceRevision = false,
                )
                continue
            }
            if (
                renderer.terrainFaults.isArmed(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD) &&
                renderer.terrainFaults.consume(
                    TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD,
                    renderer.terrainFaultScope(),
                )
            ) {
                renderer.terrainPerformance.rejected()
                renderer.terrainPerformance.uploadFailed()
                discard(mesh)
                renderer.loaded.coverageRequested(mesh.position.chunkPosition)
                renderer.invalidate(
                    mesh.section,
                    TerrainBuildCause.UPLOAD_RETRY,
                    advanceRevision = false,
                )
                continue
            }
            val uploadStarted = renderer.terrainPerformance.begin(TerrainProductionPhase.UPLOAD)
            var regionPublished = false
            try {
                val region = renderer.regionTerrain
                if (region != null) {
                    if (region.publish(mesh)) {
                        regionPublished = true
                        renderer.context.profiler("loadRegion$index") { mesh.loadRegionBacked() }
                    } else {
                        renderer.context.profiler("loadFallback$index") { mesh.load() }
                    }
                } else {
                    renderer.context.profiler("load$index") { mesh.load() }
                }
                renderer.terrainPerformance.uploaded(mesh.outputBytes)
                admittedBytes = if (Long.MAX_VALUE - admittedBytes < mesh.outputBytes) {
                    Long.MAX_VALUE
                } else {
                    admittedBytes + mesh.outputBytes
                }
            } catch (error: Throwable) {
                renderer.terrainPerformance.uploadFailed()
                if (regionPublished) {
                    try {
                        renderer.regionTerrain?.remove(mesh)
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                try {
                    discard(mesh)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                renderer.loaded.coverageRequested(mesh.position.chunkPosition)
                throw error
            } finally {
                renderer.terrainPerformance.finish(TerrainProductionPhase.UPLOAD, uploadStarted)
            }

            updates++
            renderer.loaded += mesh
            val candidateCache = mesh.candidateCache
            mesh.candidateCache = null
            if (candidateCache != null) {
                if (candidateCache.isEmpty) {
                    candidateCache.drop()
                    renderer.cache.publish(mesh.position, null)
                } else {
                    renderer.cache.publish(mesh.position, candidateCache)
                }
            }
        }
    }

    private fun isCurrent(mesh: ChunkMeshes): Boolean {
        val identity = mesh.terrainIdentity ?: return true
        val terrainGeneration = renderer.terrain.selection().generation
        return identity.page.worldEpoch == renderer.world.terrainEpoch &&
            identity.page.detailLevel == 0 &&
            identity.page.x == mesh.position.x.toLong() &&
            identity.page.y == mesh.position.y.toLong() &&
            identity.page.z == mesh.position.z.toLong() &&
            identity.capturedModelRevision == mesh.section.terrainRevision.get() &&
            identity.providerGeneration == terrainGeneration &&
            identity.layoutGeneration == terrainGeneration &&
            identity.materialGeneration == renderer.context.shaderPipeline.selection().generation
    }


    operator fun plusAssign(mesh: ChunkMeshes) = lock.locked {
        this.meshes += mesh
        sort()

        this.positions += mesh.position
        diagnosticGeneration = Math.incrementExact(diagnosticGeneration)
        renderer.terrainPerformance.pendingUploads(meshes.size)
    }


    fun removeIf(requeue: Boolean, predicate: (position: SectionPosition) -> Boolean) {
        val removed = lock.locked {
            val removed = ArrayList<ChunkMeshes>()
            val iterator = meshes.iterator()
            while (iterator.hasNext()) {
                val mesh = iterator.next()
                if (!predicate.invoke(mesh.position)) continue

                iterator.remove()
                this.positions -= mesh.position
                removed += mesh
            }
            renderer.terrainPerformance.pendingUploads(meshes.size)
            if (removed.isNotEmpty()) diagnosticGeneration = Math.incrementExact(diagnosticGeneration)
            removed
        }
        cleanupRemoved(removed, requeue)
    }

    operator fun minusAssign(position: ChunkPosition) = removeIf(false) { it.chunkPosition == position }

    operator fun minusAssign(position: SectionPosition) {
        val removed = lock.locked {
            if (!this.positions.remove(position)) return@locked null

            val iterator = meshes.iterator()
            var removed: ChunkMeshes? = null
            while (iterator.hasNext()) {
                val mesh = iterator.next()
                if (mesh.position != position) continue

                iterator.remove()
                removed = mesh
                break
            }
            renderer.terrainPerformance.pendingUploads(meshes.size)
            if (removed != null) diagnosticGeneration = Math.incrementExact(diagnosticGeneration)
            removed
        }
        if (removed != null) cleanupRemoved(listOf(removed), requeue = false)
    }

    fun clear() {
        val removed = lock.locked {
            val removed = meshes.toList()
            meshes.clear()
            positions.clear()
            renderer.terrainPerformance.pendingUploads(0)
            if (removed.isNotEmpty()) diagnosticGeneration = Math.incrementExact(diagnosticGeneration)
            removed
        }
        cleanupRemoved(removed, requeue = false)
    }

    internal fun pageRegistrySnapshot(): NearUploadPageRegistrySnapshot = lock.locked {
        NearUploadPageRegistrySnapshot(
            generation = diagnosticGeneration,
            identities = meshes.mapNotNull { it.terrainIdentity },
        )
    }

    private fun cleanupRemoved(removed: Collection<ChunkMeshes>, requeue: Boolean) {
        var failure: Throwable? = null
        for (mesh in removed) {
            try {
                discard(mesh)
            } catch (error: Throwable) {
                failure = accumulate(failure, error)
            }
            try {
                if (requeue) {
                    renderer.invalidate(mesh.section, TerrainBuildCause.UPLOAD_RETRY, advanceRevision = false)
                } else {
                    renderer.cache -= mesh.position
                }
            } catch (error: Throwable) {
                failure = accumulate(failure, error)
            }
        }
        failure?.let { throw it }
    }

    private fun discard(mesh: ChunkMeshes) {
        var failure: Throwable? = null
        try {
            mesh.drop()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            mesh.candidateCache?.drop()
        } catch (error: Throwable) {
            failure = accumulate(failure, error)
        } finally {
            mesh.candidateCache = null
        }
        failure?.let { throw it }
    }

    private fun accumulate(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        current.addSuppressed(next)
        return current
    }

    companion object {
        const val BATCH_SIZE = 5
        const val MAX_UPLOAD_BYTES_PER_FRAME = 16L * 1024L * 1024L
    }
}
