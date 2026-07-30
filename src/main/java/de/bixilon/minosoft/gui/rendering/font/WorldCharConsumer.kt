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

package de.bixilon.minosoft.gui.rendering.font

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.MVec3f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshBuilder
import de.bixilon.minosoft.gui.rendering.font.renderer.component.ChatComponentRenderer
import de.bixilon.minosoft.gui.rendering.font.renderer.properties.FontProperties
import de.bixilon.minosoft.gui.rendering.font.renderer.properties.FormattingProperties
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.CharVertexConsumer
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray


class WorldCharConsumer(
    val mesh: ChunkMeshBuilder,
    val transform: Mat4f,
    val light: Int,
) : CharVertexConsumer {
    private val whiteTexture = mesh.context.textures.whiteTexture
    private val transformed = MVec3f() // temporary


    override fun addChar(start: Vec2f, end: Vec2f, texture: ShaderTexture, uvStart: Vec2f, uvEnd: Vec2f, italic: Boolean, tint: RGBAColor, options: GUIVertexOptions?) {
        val topOffset = if (italic) (end.y - start.y) / FontProperties.CHAR_BASE_HEIGHT * FormattingProperties.ITALIC_OFFSET else 0.0f

        // uv is already pretransformed
        addQuad(
            floatArrayOf(
                start.x + topOffset, start.y,
                end.x + topOffset, start.y,
                end.x, end.y,
                start.x, end.y,
            ),
            floatArrayOf(
                uvStart.x, uvStart.y,
                uvEnd.x, uvStart.y,
                uvEnd.x, uvEnd.y,
                uvStart.x, uvEnd.y,
            ),
            texture,
            tint,
        )
    }


    override fun addQuad(start: Vec2f, end: Vec2f, tint: RGBAColor, options: GUIVertexOptions?) {
        addQuad(
            floatArrayOf(
                start.x, start.y,
                end.x, start.y,
                end.x, end.y,
                start.x, end.y,
            ),
            FloatArray(8),
            whiteTexture.texture,
            tint,
        )
    }

    private fun addQuad(points: FloatArray, textureCoordinates: FloatArray, texture: ShaderTexture, tint: RGBAColor) {
        val positions = FloatArray(12)
        for (index in 0 until 4) {
            times(
                transform,
                points[index * 2] / ChatComponentRenderer.TEXT_BLOCK_RESOLUTION,
                -points[index * 2 + 1] / ChatComponentRenderer.TEXT_BLOCK_RESOLUTION,
                transformed,
            )
            positions[index * 3] = transformed.x
            positions[index * 3 + 1] = transformed.y
            positions[index * 3 + 2] = transformed.z
        }
        val packed = PackedUVArray(FloatArray(4) { index ->
            PackedUV(textureCoordinates[index * 2], textureCoordinates[index * 2 + 1]).raw
        })
        mesh.addQuad(
            Vec3f.EMPTY,
            positions,
            packed,
            texture,
            light,
            tint.rgb(),
            AmbientOcclusionUtil.EMPTY,
        )
    }

    private fun times(mat: Mat4f, x: Float, y: Float, result: MVec3f) {
        result.x = mat[0, 0] * x + mat[0, 1] * y + mat[0, 2] + mat[0, 3]
        result.y = mat[1, 0] * x + mat[1, 1] * y + mat[1, 2] + mat[1, 3]
        result.z = mat[2, 0] * x + mat[2, 1] * y + mat[2, 2] + mat[2, 3]
    }
}
