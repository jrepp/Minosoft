/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.chunk

import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.concurrent.lock.RWLock
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.camera.frustum.FrustumResults
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes
import de.bixilon.minosoft.gui.rendering.chunk.mesh.details.ChunkMeshDetails
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause
import de.bixilon.minosoft.gui.rendering.chunk.visible.VisibleMeshes
import de.bixilon.minosoft.gui.rendering.chunk.visible.VisibilityGraphInvalidReason
import de.bixilon.minosoft.gui.rendering.terrain.near.NearSurfaceCoverage
import de.bixilon.minosoft.gui.rendering.terrain.near.NearSurfaceCoverageIndex
import de.bixilon.minosoft.gui.rendering.terrain.near.NearSurfaceCoverageEnvironment
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap

data class LoadedVisibilitySnapshot(
    val revision: Long,
    val candidates: List<Pair<ChunkMeshes, FrustumResults>>,
)

data class NativeTerrainOwnershipSnapshot(
    val revision: Long,
    val chunks: Set<ChunkPosition>,
    val lifecycle: TerrainCoverageSnapshot? = null,
)

internal data class NearLoadedPageRegistrySnapshot(
    val generation: Long,
    val identities: List<TerrainBuildIdentity>,
    val artifactDigests: Map<de.bixilon.minosoft.terrain.model.identity.TerrainPageKey, String> = emptyMap(),
)

class LoadedMeshes(
    private val renderer: ChunkRenderer,
) {
    private val meshes: MutableMap<ChunkPosition, Int2ObjectOpenHashMap<ChunkMeshes>> = HashMap(1000)
    private val lock = RWLock.rwlock()
    private var revision = 0L
    private val surfaceCoverage = NearSurfaceCoverageIndex {
        NearSurfaceCoverageEnvironment(
            worldEpoch = renderer.world.terrainEpoch,
            providerGeneration = renderer.terrain.selection().generation,
            frame = renderer.context.frameNumber,
        )
    }


    val size get() = lock.acquired { meshes.size }

    operator fun get(position: SectionPosition) = lock.acquired { meshes[position.chunkPosition]?.get(position.y) }
    operator fun plusAssign(mesh: ChunkMeshes) {
        var previous: ChunkMeshes? = null
        lock.locked {
            val chunk = meshes.getOrPut(mesh.position.chunkPosition) { Int2ObjectOpenHashMap() }
            revision++
            previous = chunk.put(mesh.position.y, mesh)
            updateOwnership(mesh.position.chunkPosition, chunk, mesh)

            val visible = renderer.visibility.meshes
            previous?.let { visible -= it }

            val frustum = renderer.visibility.contains(mesh.position, mesh.min, mesh.max)
            if (frustum != FrustumResults.OUTSIDE) {
                visible.lock.locked {
                    visible.unsafeAdd(mesh, frustum)
                    visible.sort()
                }
            }
        }

        previous?.let { renderer.unloadingQueue += it }
        renderer.visibility.invalidate(VisibilityGraphInvalidReason.MESH_UPDATE)
    }

    operator fun minusAssign(position: SectionPosition) {
        val mesh = lock.locked {
            val meshes = this.meshes[position.chunkPosition] ?: return
            val mesh = meshes.remove(position.y) ?: return
            if (meshes.isEmpty()) {
                this.meshes -= position.chunkPosition
            }
            revision++
            updateOwnership(position.chunkPosition, meshes, mesh)
            return@locked mesh
        }

        renderer.visibility.meshes -= mesh
        if (mesh.regionBacked) renderer.regionTerrain?.remove(mesh)

        renderer.unloadingQueue += mesh
        renderer.cache -= position
    }

    operator fun minusAssign(position: ChunkPosition) {
        val meshes = lock.locked {
            val removed = meshes.remove(position) ?: return
            revision++
            surfaceCoverage.remove(position)
            removed
        }
        meshes.values.forEach { renderer.visibility.meshes -= it }
        meshes.values.forEach { if (it.regionBacked) renderer.regionTerrain?.remove(it) }

        renderer.unloadingQueue += meshes.values
        renderer.cache -= position
    }

    fun clear() = clear(surfaceCoverage::clear)

    /** Clears owned resources after the terrain registry has begun closing. */
    fun close() = clear(surfaceCoverage::discard)

    private fun clear(clearCoverage: () -> Boolean) = lock.locked {
        renderer.visibility.meshes.clear()
        for (meshes in meshes.values) {
            renderer.unloadingQueue += meshes.values
        }
        if (meshes.isNotEmpty()) {
            revision++
        }
        clearCoverage()
        meshes.clear()
        renderer.regionTerrain?.clear()
    }


    fun visibilitySnapshot(): LoadedVisibilitySnapshot = lock.acquired {
        val frustum = renderer.context.camera.frustum
        val candidates = ArrayList<Pair<ChunkMeshes, FrustumResults>>()

        val iterator = this.meshes.iterator()
        while (iterator.hasNext()) {
            val (position, meshes) = iterator.next()

            if (position !in frustum) continue

            for (mesh in meshes.values) {
                // TODO: unload if occluded (but we can not distinct between frustum and occlusion)
                val result = renderer.visibility.contains(mesh.position, mesh.min, mesh.max)
                if (result == FrustumResults.OUTSIDE) continue

                candidates += mesh to result
            }
        }

        LoadedVisibilitySnapshot(revision, candidates)
    }

    /**
     * Chunks enter this snapshot only after at least one section mesh has been
     * uploaded. Consumers must not infer render ownership from packet-loaded
     * chunks because terrain can still be queued or rebuilding at that point.
     */
    fun ownershipSnapshot(): NativeTerrainOwnershipSnapshot = lock.acquired {
        surfaceCoverage.snapshot().let { NativeTerrainOwnershipSnapshot(it.revision, it.chunks, it.lifecycle) }
    }

    internal fun pageRegistrySnapshot(): NearLoadedPageRegistrySnapshot = lock.acquired {
        NearLoadedPageRegistrySnapshot(
            generation = revision,
            identities = meshes.values.asSequence()
                .flatMap { it.values.asSequence() }
                .mapNotNull(ChunkMeshes::terrainIdentity)
                .sortedWith(compareBy({ it.page.detailLevel }, { it.page.z }, { it.page.y }, { it.page.x }))
                .toList(),
            artifactDigests = meshes.values.asSequence()
                .flatMap { it.values.asSequence() }
                .mapNotNull { mesh ->
                    val page = mesh.terrainIdentity?.page ?: return@mapNotNull null
                    val digest = mesh.artifactDigest?.encodedValue ?: return@mapNotNull null
                    page to digest
                }
                .toMap(),
        )
    }

    fun coverageRequested(position: ChunkPosition) = lock.locked { surfaceCoverage.requested(position) }

    fun coverageBuilding(position: ChunkPosition) = lock.locked { surfaceCoverage.building(position) }

    fun coverageUploadPending(position: ChunkPosition) = lock.locked { surfaceCoverage.uploadPending(position) }

    fun publishVisibility(snapshot: LoadedVisibilitySnapshot, meshes: VisibleMeshes): Boolean = lock.acquired {
        if (snapshot.revision != revision) {
            return@acquired false
        }
        renderer.visibility.publish(meshes)
        true
    }

    fun forEachLoaded(consumer: (ChunkMeshes) -> Unit) {
        val snapshot = lock.acquired {
            val snapshot = ArrayList<ChunkMeshes>()
            for (column in meshes.values) {
                snapshot.addAll(column.values)
            }
            snapshot
        }
        for (mesh in snapshot) {
            consumer(mesh)
        }
    }

    fun update() = lock.locked {
        renderer.meshingQueue.lock.lock()
        try {
            var changed = false

            val iterator = this.meshes.iterator()
            while (iterator.hasNext()) {
                val (chunkPosition, meshes) = iterator.next()

                if (!renderer.visibility.isInViewDistance(chunkPosition)) {
                    iterator.remove()
                    changed = true
                    val values = meshes.values
                    val chunk = values.iterator().next().section.chunk // dirty hack

                    meshes.values.forEach { renderer.visibility.meshes -= it }
                    meshes.values.forEach { if (it.regionBacked) renderer.regionTerrain?.remove(it) }
                    renderer.culledQueue += chunk
                    renderer.unloadingQueue += values
                    renderer.cache -= chunkPosition
                    surfaceCoverage.remove(chunkPosition)
                    continue
                }

                val sections = meshes.values.iterator()
                while (sections.hasNext()) {
                    val mesh = sections.next() ?: continue

                    if (!renderer.visibility.isInViewDistance(mesh.position)) {
                        sections.remove()
                        changed = true
                        renderer.culledQueue += mesh.section
                        renderer.visibility.meshes -= mesh
                        if (mesh.regionBacked) renderer.regionTerrain?.remove(mesh)
                        renderer.unloadingQueue += mesh
                        renderer.cache -= chunkPosition
                        updateOwnership(chunkPosition, meshes, mesh)
                        continue
                    }

                    val next = ChunkMeshDetails.update(mesh.details, mesh.position, renderer.visibility.sectionPosition) + renderer.mesher.details

                    if (next == mesh.details) continue

                    renderer.meshingQueue.unsafeAdd(mesh.section, TerrainBuildCause.LEVEL_OF_DETAIL_UPDATE)
                }

                if (meshes.isEmpty()) {
                    iterator.remove()
                }
            }
            if (changed) {
                revision++
            }
            renderer.meshingQueue.sort()
        } finally {
            renderer.meshingQueue.lock.unlock()
        }
    }

    private fun updateOwnership(
        position: ChunkPosition,
        uploaded: Int2ObjectOpenHashMap<ChunkMeshes>,
        representative: ChunkMeshes,
    ) {
        val required = NearSurfaceCoverage.requiredSections(representative.section.chunk.light.heightmap)
        surfaceCoverage.update(position, required, uploaded.keys.toSet())
    }
}
