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

package de.bixilon.minosoft.gui.rendering.chunk.mesh

import de.bixilon.kutil.enums.inline.IntInlineSet
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.camera.frustum.FrustumResults
import de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.cache.ChunkMeshCache
import de.bixilon.minosoft.gui.rendering.chunk.mesh.types.ChunkMeshTypeMap
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainDirectionalVisibility
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactDigest
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates

class ChunkMeshes(
    val section: ChunkSection,
    val position: SectionPosition,
    val min: InSectionPosition,
    val max: InSectionPosition,

    val details: IntInlineSet,
    val materialGeneration: String?,
    val modelRevision: Long,
    val connectivity: TerrainDirectionalVisibility,
    val outputBytes: Long,

    val meshes: ChunkMeshTypeMap,
    entityPositions: List<InSectionPosition>,
    var entities: Array<BlockEntityRenderer>?,
    val artifact: TerrainMeshArtifact? = null,
) {
    val entityPositions: List<InSectionPosition> = java.util.List.copyOf(entityPositions)
    var artifactDigest: TerrainArtifactDigest? = null
        private set
    val terrainIdentity: TerrainBuildIdentity? = artifact?.identity
    val terrainMaterials: Set<TerrainSemanticMaterialId> = java.util.Set.copyOf(
        artifact?.streams?.mapTo(linkedSetOf()) { it.material } ?: emptySet(),
    )
    var regionBacked: Boolean = false
        private set
    var candidateCache: ChunkMeshCache? = null

    fun recordArtifactDigest(digest: TerrainArtifactDigest) {
        check(artifact != null) { "Only semantic terrain candidates have an artifact digest" }
        artifactDigest = digest
    }

    val center = BlockPosition.of(position, InSectionPosition(8, 8, 8))

    var delta = BlockPosition()
    var result = FrustumResults.FULLY_INSIDE

    var distance = 0
    var sort = 0

    fun load() {
        var failure: Throwable? = null
        try {
            meshes.forEach { _, mesh ->
                if (failure == null) {
                    try {
                        mesh.load()
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
            }
            entities?.forEach { entity ->
                if (failure == null) {
                    try {
                        entity.load()
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
            }
            if (failure != null) releaseMeshes(failure)
        } finally {
            try {
                artifact?.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (failure != null) throw failure
    }

    /** Uploads entity resources while discarding the superseded per-section VBO candidates. */
    fun loadRegionBacked() {
        check(artifact != null) { "Region-backed terrain requires a semantic artifact" }
        var failure: Throwable? = null
        try {
            releaseMeshes()?.let { failure = it }
            entities?.forEach { entity ->
                if (failure == null) {
                    try {
                        entity.load()
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
            }
            if (failure == null) regionBacked = true
        } finally {
            try {
                artifact.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (failure != null) throw failure
    }

    fun attachEntities(entities: Array<BlockEntityRenderer>?) {
        check(this.entities == null) { "Block-entity renderers are already attached" }
        this.entities = entities
    }

    fun unload() {
        if (regionBacked) return
        releaseMeshes()?.let { throw it }
    }

    fun drop() {
        var failure = releaseMeshes()
        try {
            artifact?.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        if (failure != null) throw failure
    }

    private fun releaseMeshes(primary: Throwable? = null): Throwable? {
        var failure = primary
        meshes.forEach { _, mesh ->
            try {
                when (mesh.state) {
                    MeshStates.PREPARING -> mesh.drop()
                    MeshStates.LOADED -> mesh.unload()
                    MeshStates.UNLOADED -> Unit
                }
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        return failure
    }

    fun update(camera: BlockPosition, frustum: FrustumResults) {
        val delta = (camera - center)
        val distance = delta.x * delta.x + (delta.y * delta.y / 4) + delta.z * delta.z
        this.distance = distance

        var occlusion = true
        if ((delta - this.delta) == BlockPosition.EMPTY && this.result == FrustumResults.FULLY_INSIDE) {
            occlusion = false
        } else {
            this.delta = delta
        }

        this.result = frustum


        meshes.forEach { type, mesh ->
            mesh.cameraDelta = delta
            mesh.distance = distance * if (type.inverseDistance) -1 else 1
            if (occlusion) mesh.occlusion = ChunkMesh.OcclusionStates.MAYBE
        }
    }

    fun resetOcclusion() {
        meshes.forEach { _, mesh ->
            mesh.occlusion = ChunkMesh.OcclusionStates.MAYBE
        }
    }
}
