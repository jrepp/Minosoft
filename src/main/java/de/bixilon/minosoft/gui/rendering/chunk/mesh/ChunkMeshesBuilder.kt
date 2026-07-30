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

package de.bixilon.minosoft.gui.rendering.chunk.mesh

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.enums.inline.IntInlineSet
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.details.ChunkMeshDetails
import de.bixilon.minosoft.gui.rendering.chunk.mesh.types.ChunkMeshTypeMap
import de.bixilon.minosoft.gui.rendering.chunk.mesh.types.ChunkMeshTypes
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainMaterial
import de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainMaterialResolver
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainDirectionalVisibility
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray

class ChunkMeshesBuilder(
    context: RenderContext,
    val section: ChunkSection,
    val details: IntInlineSet,
    private val materialResolver: IrisTerrainMaterialResolver = IrisTerrainMaterialResolver.EMPTY,
) : BlockVertexConsumer { // TODO: Don't inherit
    var opaque = ChunkMeshBuilder(context, section.blocks.count.opaqueCount())
    var cutout = ChunkMeshBuilder(context, section.blocks.count.translucentCount())
    var translucent = ChunkMeshBuilder(context, section.blocks.count.translucentCount())
    var text = ChunkMeshBuilder(context, if (ChunkMeshDetails.TEXT in details && section.entities.count > 0) 128 else 0)
    var entities: ArrayList<BlockEntityRenderer> = ArrayList(if (ChunkMeshDetails.ENTITIES in details) section.entities.count else 0)
    private var material: IrisTerrainMaterial? = null

    // used for frustum culling
    var minPosition = InSectionPosition(ChunkSize.SECTION_MAX_X, ChunkSize.SECTION_MAX_Y, ChunkSize.SECTION_MAX_Z)
    var maxPosition = InSectionPosition(0, 0, 0)


    fun addBlock(x: Int, y: Int, z: Int) {
        if (x < minPosition.x) {
            minPosition = minPosition.with(x = x)
        }
        if (y < minPosition.y) {
            minPosition = minPosition.with(y = y)
        }
        if (z < minPosition.z) {
            minPosition = minPosition.with(z = z)
        }

        if (x > maxPosition.x) {
            maxPosition = maxPosition.with(x = x)
        }
        if (y > maxPosition.y) {
            maxPosition = maxPosition.with(y = y)
        }
        if (z > maxPosition.z) {
            maxPosition = maxPosition.with(z = z)
        }
    }

    fun material(
        state: de.bixilon.minosoft.data.registries.blocks.state.BlockState,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        fluid: Boolean = false,
    ) {
        val resolved = materialResolver.resolve(state, centerX, centerY, centerZ, fluid)
        material = resolved
        opaque.material = resolved
        cutout.material = resolved
        translucent.material = resolved
        text.material = resolved
    }


    fun build(
        position: SectionPosition,
        modelRevision: Long = 0L,
        connectivity: TerrainDirectionalVisibility = TerrainDirectionalVisibility.ALL,
    ): ChunkMeshes? {
        val outputBytes = listOf(opaque, cutout, translucent, text).fold(0L) { total, builder ->
            val vertexBytes = Math.multiplyExact(builder._data?.size?.toLong() ?: 0L, Float.SIZE_BYTES.toLong())
            val indexBytes = Math.multiplyExact(builder._index?.size?.toLong() ?: 0L, Int.SIZE_BYTES.toLong())
            Math.addExact(total, Math.addExact(vertexBytes, indexBytes))
        }
        val meshes = ChunkMeshTypeMap()

        meshes[ChunkMeshTypes.OPAQUE] = opaque
        meshes[ChunkMeshTypes.CUTOUT] = cutout
        meshes[ChunkMeshTypes.TRANSLUCENT] = translucent
        meshes[ChunkMeshTypes.TEXT] = text

        val entities = entities.takeIf { it.isNotEmpty() }?.toTypedArray()

        if (meshes.size == 0 && entities == null) {
            return null
        }

        return ChunkMeshes(
            section,
            position,
            minPosition,
            maxPosition,
            details,
            materialResolver.generation,
            modelRevision,
            connectivity,
            outputBytes,
            meshes,
            entities,
        )
    }

    fun drop() {
        opaque.drop()
        cutout.drop()
        translucent.drop()
        text.drop()
    }

    override fun addQuad(offset: Vec3f, positions: FaceVertexData, uv: PackedUVArray, texture: ShaderTexture, light: Int, tint: RGBColor, ao: IntArray) {
        val mesh = this[texture.transparency]
        mesh.addQuad(offset, positions, uv, texture, light, tint, ao)
    }

    override fun addQuad(
        offset: Vec3f,
        positions: FaceVertexData,
        uv: PackedUVArray,
        texture: ShaderTexture,
        light: IntArray,
        tint: IntArray,
        flipDiagonal: Boolean,
    ) {
        val mesh = this[texture.transparency]
        mesh.addQuad(offset, positions, uv, texture, light, tint, flipDiagonal)
    }

    operator fun get(transparency: TextureTransparencies) = when {
        transparency == TextureTransparencies.TRANSLUCENT -> translucent
        transparency == TextureTransparencies.TRANSPARENT -> cutout
        else -> opaque
    }.also { it.material = material }

    companion object {

        private fun Int.opaqueCount() = when { // Rounded mean counts of faces in a normal world
            this <= 32 -> 32
            this <= 128 -> 300
            this <= 512 -> 600
            this <= 3584 -> 1024
            this <= 4064 -> 512
            else -> 280
        }

        private fun Int.translucentCount() = when {
            this <= 32 -> 32
            this <= 4064 -> 256
            else -> 32
        }
    }
}
