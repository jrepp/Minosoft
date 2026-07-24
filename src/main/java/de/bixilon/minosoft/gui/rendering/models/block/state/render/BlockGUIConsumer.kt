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

package de.bixilon.minosoft.gui.rendering.models.block.state.render

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.camera.CameraDefinition
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.models.raw.display.ModelDisplay
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import kotlin.math.min

class BlockGUIConsumer(
    val gui: GUIRenderer,
    val offset: Vec2f,
    val consumer: GuiVertexConsumer,
    val options: GUIVertexOptions?,
    val display: ModelDisplay,
    val size: Vec2f,
) : BlockVertexConsumer {
    private val matrix = VIEW_MATRIX * display.matrix
    private val quads = mutableListOf<Quad>()

    private fun transform(index: Int, offset: Vec3f, positions: FaceVertexData): Vec2f {
        val vertexOffset = index * Vec3f.LENGTH
        val xyz = Vec3f(
            offset.x + positions[vertexOffset + 0],
            offset.y + positions[vertexOffset + 1],
            offset.z + positions[vertexOffset + 2],
        )

        val out = matrix * xyz
        return Vec2f(out.x, -out.y)
    }

    override fun addQuad(offset: Vec3f, positions: FaceVertexData, uv: PackedUVArray, texture: ShaderTexture, light: Int, tint: RGBColor, ao: IntArray) {
        if (quads.size >= MAX_QUADS) return
        val projected = Array(PrimitiveTypes.QUAD.vertices) { transform(it, offset, positions) }
        quads += Quad(projected, uv, texture, tint)
    }

    fun flush() {
        if (quads.isEmpty()) return

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (quad in quads) {
            for (position in quad.positions) {
                minX = minOf(minX, position.x)
                minY = minOf(minY, position.y)
                maxX = maxOf(maxX, position.x)
                maxY = maxOf(maxY, position.y)
            }
        }

        val width = maxX - minX
        val height = maxY - minY
        if (!width.isFinite() || !height.isFinite() || width <= 0.0f || height <= 0.0f) {
            quads.clear()
            return
        }

        val scale = min(size.x / width, size.y / height) * CONTENT_SCALE
        val sourceCenter = Vec2f((minX + maxX) / 2.0f, (minY + maxY) / 2.0f)
        val targetCenter = offset + size / 2.0f

        for (quad in quads) {
            val positions = FloatArray(PrimitiveTypes.QUAD.vertices * Vec2f.LENGTH)
            for ((index, position) in quad.positions.withIndex()) {
                val centered = (position - sourceCenter) * scale + targetCenter
                positions[index * Vec2f.LENGTH + 0] = centered.x
                positions[index * Vec2f.LENGTH + 1] = centered.y
            }
            consumer.addQuad(positions, quad.uv, quad.texture, quad.tint.rgba(), options)
        }
        quads.clear()
    }

    private data class Quad(
        val positions: Array<Vec2f>,
        val uv: PackedUVArray,
        val texture: ShaderTexture,
        val tint: RGBColor,
    )

    companion object {
        const val CONTENT_SCALE = 0.9f
        const val MAX_QUADS = 65_536
        val VIEW_MATRIX = CameraUtil.lookAt(Vec3f(0.0f, 2.5f, -1.0f), Vec3f(0.0f, 0.0f, 1.0f), CameraDefinition.CAMERA_UP_VEC3)
    }
}
