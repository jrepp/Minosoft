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

package de.bixilon.minosoft.gui.rendering.chunk.mesher

import de.bixilon.kutil.enums.inline.IntInlineSet
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshesBuilder
import de.bixilon.minosoft.gui.rendering.chunk.mesh.cache.ChunkMeshCache
import de.bixilon.minosoft.gui.rendering.chunk.mesh.details.ChunkMeshDetails
import de.bixilon.minosoft.gui.rendering.chunk.mesher.fluid.FluidSectionMesher
import de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainMaterialResolver
import de.bixilon.minosoft.gui.rendering.terrain.near.buildSemanticArtifact
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainSnapshotTintSampler
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainCancellationToken

class ChunkMesher(
    private val renderer: ChunkRenderer,
) {
    private val profile = renderer.context.session.profiles.block.lod

    var details = IntInlineSet()
        private set

    init {
        profile::enabled.observe(this) { updateDetails() }
        profile::minorVisualImpact.observe(this) { updateDetails() }
        profile::aggressiveCulling.observe(this) { updateDetails() }
        profile::darkCaveCulling.observe(this) { updateDetails() }

        updateDetails()
    }

    private fun updateDetails() {
        var details = IntInlineSet()


        if (!profile.enabled) details += ChunkMeshDetails.ALL

        if (!profile.minorVisualImpact) details += ChunkMeshDetails.MINOR_VISUAL_IMPACT
        if (!profile.aggressiveCulling) details += ChunkMeshDetails.AGGRESSIVE_CULLING
        if (!profile.darkCaveCulling) details += ChunkMeshDetails.DARK_CAVE_SURFACE


        if (details == this.details) return

        renderer.invalidate(renderer.world, TerrainBuildCause.RENDER_SETTING_CHANGE)
    }

    fun createWorkerContext() = WorkerContext()

    val snapshotHalo: Int
        get() {
            val blending = renderer.context.session.profiles.rendering.biome.blending
            return TerrainSnapshotTintSampler.requiredHalo(blending.enabled, blending.radius)
        }

    inner class WorkerContext : AutoCloseable {
        private val solid = SolidSectionMesher(renderer.context)
        private val fluid = FluidSectionMesher(renderer.context)

        fun mesh(
            cache: ChunkMeshCache,
            section: ChunkSection,
            snapshot: TerrainBuildSnapshot,
            identity: TerrainBuildIdentity,
            cancellation: TerrainCancellationToken,
        ): ChunkMeshes? {
            if (cancellation.isCancelled || snapshot.isEmpty) return null
            if (!snapshot.completeHorizontalNeighbours) return null

            val position = snapshot.position

            // TODO: This disables LOD completely
            // val details = ChunkMeshDetails.of(position, renderer.visibility.sectionPosition) + this@ChunkMesher.details
            val details = ChunkMeshDetails.ALL


            // TODO: put sizes of previous mesh (cache estimate)
            val mesh = ChunkMeshesBuilder(
                renderer.context,
                section,
                details,
                IrisTerrainMaterialResolver.capture(renderer.context),
                snapshot,
            )
            try {
                solid.mesh(snapshot, mesh, cancellation)
                if (cancellation.isCancelled) {
                    mesh.drop()
                    return null
                }

                if (snapshot.fluidCount > 0) {
                    fluid.mesh(snapshot, mesh, cancellation)
                }
                if (cancellation.isCancelled) {
                    mesh.drop()
                    return null
                }
            } catch (error: Throwable) {
                mesh.drop()
                throw error
            }

            val connectivity = snapshot.connectivity()
            val artifact = mesh.buildSemanticArtifact(identity, connectivity)
            return try {
                mesh.build(position, snapshot.modelRevision, connectivity, artifact).also { built ->
                    if (built == null) artifact.close()
                }
            } catch (failure: Throwable) {
                artifact.close()
                throw failure
            }
        }

        override fun close() = Unit
    }
}
