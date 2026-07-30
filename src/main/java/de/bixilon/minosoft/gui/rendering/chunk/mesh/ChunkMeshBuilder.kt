/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainMaterial
import de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainQuad
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadConsumer.Companion.iterate
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray

class ChunkMeshBuilder(context: RenderContext, estimate: Int) : QuadMeshBuilder(context, ChunkMeshStruct, estimate), BlockVertexConsumer {
    var material: IrisTerrainMaterial? = null

    private fun addVertex(
        x: Float,
        y: Float,
        z: Float,
        aUV: Float,
        texture: ShaderTexture,
        lightTint: Int,
        quad: IrisTerrainQuad,
    ) {
        val material = material ?: IrisTerrainMaterial.EMPTY
        data.add(
            x, y, z,
            aUV,
            texture.shaderId.buffer(),
            lightTint.buffer(),
        )
        data.add(
            material.blockId.toFloat(),
            material.renderType.toFloat(),
            quad.midTexture.x,
            quad.midTexture.y,
        )
        data.add(
            quad.tangent.x,
            quad.tangent.y,
            quad.tangent.z,
            quad.tangent.w,
        )
        data.add(
            quad.normal.x,
            quad.normal.y,
            quad.normal.z,
            material.centerX - x,
            material.centerY - y,
            material.centerZ - z,
            material.lightValue.toFloat(),
        )
    }

    fun addQuad(
        offset: Vec3f,
        positions: FaceVertexData,
        uv: PackedUVArray,
        texture: ShaderTexture,
        lightTint: Int,
    ) {
        addQuad(offset, positions, uv, texture, IntArray(4) { lightTint }, false)
    }

    fun addQuad(
        offset: Vec3f,
        positions: FaceVertexData,
        uv: PackedUVArray,
        texture: ShaderTexture,
        lightTint: IntArray,
        flipDiagonal: Boolean,
        front: Boolean = true,
        reverse: Boolean = false,
    ) {
        ensureSize(1)
        val quad = IrisTerrainQuad.calculate(positions, uv)
        iterate {
            val vertexOffset = it * Vec3f.LENGTH
            addVertex(
                offset.x + positions[vertexOffset],
                offset.y + positions[vertexOffset + 1],
                offset.z + positions[vertexOffset + 2],
                uv[it].raw,
                texture,
                lightTint[it],
                quad,
            )
        }
        addIndexQuad(front, reverse, flipDiagonal)
    }

    override fun addQuad(offset: Vec3f, positions: FaceVertexData, uv: PackedUVArray, texture: ShaderTexture, light: Int, tint: RGBColor, ao: IntArray) {
        val lightTint = (light and 0xFF shl 24) or tint.rgb
        val quad = IrisTerrainQuad.calculate(positions, uv)
        ensureSize(1)
        iterate {
            val vertexOffset = it * Vec3f.LENGTH
            val aUV = Float.fromBits(uv.raw[it].toBits() or (ao[it] shl 24))
            addVertex(
                offset.x + positions[vertexOffset], offset.y + positions[vertexOffset + 1], offset.z + positions[vertexOffset + 2],
                aUV,
                texture,
                lightTint,
                quad,
            )
        }
        addIndexQuad()
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
        val packed = IntArray(4) { ((light[it] and 0xFF) shl 24) or (tint[it] and 0xFFFFFF) }
        addQuad(offset, positions, uv, texture, packed, flipDiagonal)
    }

    override fun bake() = ChunkMesh(createVertexBuffer())

    data class ChunkMeshStruct(
        val position: Vec3f,
        val uv: PackedUV,
        val texture: ShaderTexture,
        val lightTint: Int,
        val blockId: Vec2f,
        val midTexture: Vec2f,
        val tangent: Vec4f,
        val normal: Vec3f,
        val midBlock: Vec4f,
    ) {
        companion object : MeshStruct(ChunkMeshStruct::class)
    }
}
