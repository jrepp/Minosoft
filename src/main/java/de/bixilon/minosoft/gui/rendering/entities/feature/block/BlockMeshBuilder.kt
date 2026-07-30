/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.block

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadConsumer.Companion.iterate
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray

class BlockMeshBuilder(context: RenderContext) : QuadMeshBuilder(context, BlockMeshStruct), BlockVertexConsumer {

    inline fun addVertex(
        x: Float,
        y: Float,
        z: Float,
        uv: PackedUV,
        texture: ShaderTexture,
        tint: RGBColor,
        midUv: Vec2f,
        normal: Vec3f,
        tangent: Vec4f,
    ) {
        data.add(
            x, y, z,
            uv.raw,
            texture.shaderId.buffer(),
            tint.rgb.buffer(),
            midUv.x, midUv.y,
        )
        data.add(normal.x, normal.y, normal.z)
        data.add(tangent.x, tangent.y, tangent.z, tangent.w)
    }


    override fun addQuad(offset: Vec3f, positions: FaceVertexData, uv: PackedUVArray, texture: ShaderTexture, light: Int, tint: RGBColor, ao: IntArray) {
        ensureSize(1)
        val midUv = faceUvMidpoint(uv)
        val quad = de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainQuad.calculate(positions, uv)

        iterate {
            val vertexOffset = it * Vec3f.LENGTH

            addVertex(
                offset.x + positions[vertexOffset], offset.y + positions[vertexOffset + 1], offset.z + positions[vertexOffset + 2],
                uv[it],
                texture,
                tint,
                midUv,
                quad.normal,
                quad.tangent,
            )
        }
        addIndexQuad()
    }

    data class BlockMeshStruct(
        val position: Vec3f,
        val uv: PackedUV,
        val texture: ShaderTexture,
        val tint: RGBColor,
        val midUv: Vec2f,
        val normal: Vec3f,
        val tangent: Vec4f,
    ) {
        companion object : MeshStruct(BlockMeshStruct::class)
    }

    internal companion object {
        fun faceUvMidpoint(uv: PackedUVArray): Vec2f {
            var u = 0.0f
            var v = 0.0f
            for (vertex in 0 until PackedUVArray.COMPONENTS) {
                u += uv[vertex].u
                v += uv[vertex].v
            }
            return Vec2f(u / PackedUVArray.COMPONENTS, v / PackedUVArray.COMPONENTS)
        }
    }
}
