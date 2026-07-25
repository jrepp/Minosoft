/*
 * Minosoft
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

package de.bixilon.minosoft.gui.rendering.gui.mesh.consumer

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.font.renderer.properties.FontProperties
import de.bixilon.minosoft.gui.rendering.font.renderer.properties.FormattingProperties
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.GuiClipRect
import de.bixilon.minosoft.gui.rendering.gui.mesh.GuiClipVertex
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray

class ClippedGuiVertexConsumer(
    private val delegate: GuiVertexConsumer,
    private val clip: GuiClipRect,
) : GuiVertexConsumer {
    override fun ensureSize(primitives: Int) = delegate.ensureSize(primitives)

    override fun addQuad(
        positions: FloatArray,
        uv: PackedUVArray,
        texture: ShaderTexture,
        tint: RGBAColor,
        options: GUIVertexOptions?,
    ) {
        require(positions.size == VERTEX_COUNT * Vec2f.LENGTH) { "A GUI quad must contain four 2D positions." }
        val vertices = List(VERTEX_COUNT) { index ->
            GuiClipVertex(
                x = positions[index * Vec2f.LENGTH],
                y = positions[index * Vec2f.LENGTH + 1],
                u = uv[index].u,
                v = uv[index].v,
            )
        }
        emit(clip.clip(vertices), texture, tint, options)
    }

    override fun addQuad(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        texture: ShaderTexture,
        uvStartX: Float,
        uvStartY: Float,
        uvEndX: Float,
        uvEndY: Float,
        tint: RGBAColor,
        options: GUIVertexOptions?,
    ) {
        val vertices = listOf(
            GuiClipVertex(startX, startY, texture.transformU(uvStartX), texture.transformV(uvStartY)),
            GuiClipVertex(endX, startY, texture.transformU(uvEndX), texture.transformV(uvStartY)),
            GuiClipVertex(endX, endY, texture.transformU(uvEndX), texture.transformV(uvEndY)),
            GuiClipVertex(startX, endY, texture.transformU(uvStartX), texture.transformV(uvEndY)),
        )
        emit(clip.clip(vertices), texture, tint, options)
    }

    override fun addQuad(start: Vec2f, end: Vec2f, tint: RGBAColor, options: GUIVertexOptions?) {
        val clippedStart = Vec2f(maxOf(start.x, clip.start.x), maxOf(start.y, clip.start.y))
        val clippedEnd = Vec2f(minOf(end.x, clip.end.x), minOf(end.y, clip.end.y))
        if (clippedEnd.x <= clippedStart.x || clippedEnd.y <= clippedStart.y) return
        delegate.addQuad(clippedStart, clippedEnd, tint, options)
    }

    override fun addChar(
        start: Vec2f,
        end: Vec2f,
        texture: ShaderTexture,
        uvStart: Vec2f,
        uvEnd: Vec2f,
        italic: Boolean,
        tint: RGBAColor,
        options: GUIVertexOptions?,
    ) {
        val topOffset = if (italic) {
            (end.y - start.y) / FontProperties.CHAR_BASE_HEIGHT * FormattingProperties.ITALIC_OFFSET
        } else {
            0.0f
        }
        val vertices = listOf(
            GuiClipVertex(start.x + topOffset, start.y, uvStart.x, uvStart.y),
            GuiClipVertex(end.x + topOffset, start.y, uvEnd.x, uvStart.y),
            GuiClipVertex(end.x, end.y, uvEnd.x, uvEnd.y),
            GuiClipVertex(start.x, end.y, uvStart.x, uvEnd.y),
        )
        emit(clip.clip(vertices), texture, tint, options)
    }

    private fun emit(
        vertices: List<GuiClipVertex>,
        texture: ShaderTexture,
        tint: RGBAColor,
        options: GUIVertexOptions?,
    ) {
        when (vertices.size) {
            0, 1, 2 -> return
            VERTEX_COUNT -> emitQuad(vertices, texture, tint, options)
            else -> {
                val first = vertices.first()
                for (index in 1 until vertices.lastIndex) {
                    emitQuad(
                        listOf(first, vertices[index], vertices[index + 1], vertices[index + 1]),
                        texture,
                        tint,
                        options,
                    )
                }
            }
        }
    }

    private fun emitQuad(
        vertices: List<GuiClipVertex>,
        texture: ShaderTexture,
        tint: RGBAColor,
        options: GUIVertexOptions?,
    ) {
        val positions = FloatArray(VERTEX_COUNT * Vec2f.LENGTH)
        val uv = PackedUVArray()
        for ((index, vertex) in vertices.withIndex()) {
            positions[index * Vec2f.LENGTH] = vertex.x
            positions[index * Vec2f.LENGTH + 1] = vertex.y
            uv[index] = PackedUV(vertex.u, vertex.v)
        }
        delegate.addQuad(positions, uv, texture, tint, options)
    }

    private companion object {
        const val VERTEX_COUNT = 4
    }
}
