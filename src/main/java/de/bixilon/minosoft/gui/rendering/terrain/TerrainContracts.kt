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

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexAttribute
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexAttributeFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexLayoutDeclaration
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic

enum class TerrainMaterialClass {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT,
    EMISSIVE_ADDITIVE,
}

enum class TerrainInvalidationReason {
    BLOCK,
    LIGHT,
    NEIGHBOUR,
    VISIBILITY,
    RESOURCE_GENERATION,
    BACKEND_REPLACED,
    WORLD_UNLOAD,
}

/**
 * A detached, bounded block snapshot. Block states are normalized host objects;
 * no protocol palette or 1.20.4 registry index crosses this boundary.
 */
class TerrainSectionSnapshot(
    val position: SectionPosition,
    states: Array<BlockState?>,
) {
    private val states = states.copyOf()

    init {
        require(this.states.size == ChunkSize.BLOCKS_PER_SECTION) {
            "Terrain section snapshot must contain ${ChunkSize.BLOCKS_PER_SECTION} states"
        }
    }

    operator fun get(position: InSectionPosition): BlockState? = states[position.index]

    fun copyStates(): Array<BlockState?> = states.copyOf()

    companion object {
        fun capture(section: ChunkSection): TerrainSectionSnapshot {
            val states = arrayOfNulls<BlockState>(ChunkSize.BLOCKS_PER_SECTION)
            section.chunk.lock.lock()
            try {
                for (index in states.indices) {
                    states[index] = section.blocks[InSectionPosition(index)]
                }
            } finally {
                section.chunk.lock.unlock()
            }
            return TerrainSectionSnapshot(SectionPosition.of(section), states)
        }
    }
}

data class TerrainBackendDescriptor(
    val owner: RenderOwnerId,
    val implementation: String,
    val materials: Set<TerrainMaterialClass>,
    val vertexLayout: VertexLayoutDeclaration,
    val supportsAuxiliaryViews: Boolean,
) {
    init {
        require(implementation.isNotBlank()) { "Terrain backend implementation must not be blank" }
        require(materials.containsAll(REQUIRED_MATERIALS)) {
            "Terrain backend $owner lacks required material classes: ${REQUIRED_MATERIALS - materials}"
        }
        require(vertexLayout.attributes.any { it.semantic == VertexSemantic.POSITION }) {
            "Terrain backend $owner must declare a position attribute"
        }
    }

    companion object {
        val REQUIRED_MATERIALS = setOf(
            TerrainMaterialClass.OPAQUE,
            TerrainMaterialClass.CUTOUT,
            TerrainMaterialClass.TRANSLUCENT,
        )
    }
}

interface TerrainBackend : AutoCloseable {
    val descriptor: TerrainBackendDescriptor

    fun prepare()
    fun finishPreparation()
    fun submit(view: RenderViewId, material: TerrainMaterialClass)
    fun finishFrame()
    fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason)

    override fun close()
}

object BuiltInTerrainVertexLayout {
    val VALUE = VertexLayoutDeclaration(
        id = RenderResourceId("minosoft:terrain/iris-material"),
        strideBytes = 84,
        attributes = listOf(
            VertexAttribute(VertexSemantic.POSITION, VertexAttributeFormat.FLOAT3, 0),
            VertexAttribute(VertexSemantic.TEXTURE_COORDINATE, VertexAttributeFormat.UINT, 12),
            VertexAttribute(VertexSemantic.TEXTURE_LAYER, VertexAttributeFormat.UINT, 16),
            VertexAttribute(VertexSemantic.PACKED_LIGHT_COLOR, VertexAttributeFormat.UINT, 20),
            VertexAttribute(VertexSemantic.BLOCK_ID, VertexAttributeFormat.FLOAT2, 24),
            VertexAttribute(VertexSemantic.MID_TEXTURE_COORDINATE, VertexAttributeFormat.FLOAT2, 32),
            VertexAttribute(VertexSemantic.TANGENT, VertexAttributeFormat.FLOAT4, 40),
            VertexAttribute(VertexSemantic.NORMAL, VertexAttributeFormat.FLOAT3, 56),
            VertexAttribute(VertexSemantic.MID_BLOCK, VertexAttributeFormat.FLOAT4, 68),
        ),
    )
}
