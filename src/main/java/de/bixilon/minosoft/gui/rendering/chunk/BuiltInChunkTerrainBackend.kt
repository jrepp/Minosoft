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

package de.bixilon.minosoft.gui.rendering.chunk

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackend
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainInvalidationReason
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.TerrainSectionSnapshot
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause

class BuiltInChunkTerrainBackend(
    private val renderer: ChunkRenderer,
) : TerrainBackend {
    override val descriptor = TerrainBackendDescriptor(
        owner = OWNER,
        implementation = "minosoft-built-in-chunk-renderer",
        materials = TerrainMaterialClass.entries.toSet(),
        vertexLayout = BuiltInTerrainVertexLayout.VALUE,
        supportsAuxiliaryViews = true,
    )

    override fun prepare() = renderer.prepareTerrainCore()

    override fun finishPreparation() = renderer.finishTerrainPreparationCore()

    override fun submit(view: RenderViewId, material: TerrainMaterialClass) {
        renderer.submitTerrainCore(view, material)
    }

    override fun finishFrame() = renderer.finishTerrainFrameCore()

    override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) {
        renderer.invalidate(snapshot.position, reason.toBuildCause())
    }

    override fun close() = renderer.closeTerrainCore()

    companion object {
        val OWNER = RenderOwnerId("minosoft:built-in-terrain")
    }
}

internal fun TerrainInvalidationReason.toBuildCause(): TerrainBuildCause = when (this) {
    TerrainInvalidationReason.BLOCK -> TerrainBuildCause.BLOCK_CHANGE
    TerrainInvalidationReason.LIGHT -> TerrainBuildCause.LIGHT_CHANGE
    TerrainInvalidationReason.NEIGHBOUR -> TerrainBuildCause.NEIGHBOUR_CHANGE
    TerrainInvalidationReason.VISIBILITY -> TerrainBuildCause.CULLED
    TerrainInvalidationReason.RESOURCE_GENERATION,
    TerrainInvalidationReason.BACKEND_REPLACED -> TerrainBuildCause.RESOURCE_GENERATION_CHANGE
    TerrainInvalidationReason.WORLD_UNLOAD -> TerrainBuildCause.WORLD_UNLOAD
}
