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

package de.bixilon.minosoft.gui.rendering.system.base.buffer.frame

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.depth.DepthAttachment
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.stencil.StencilAttachment
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.texture.TextureAttachment
import kotlin.math.roundToInt

fun scaledFramebufferSize(size: Vec2i, scale: Float): Vec2i {
    require(size.x > 0 && size.y > 0) { "Framebuffer dimensions must be positive: $size" }
    require(scale.isFinite() && scale > 0.0f) { "Framebuffer scale must be finite and positive: $scale" }
    if (scale == 1.0f) return size

    fun scaled(dimension: Int): Int {
        val value = dimension.toDouble() * scale.toDouble()
        require(value.isFinite() && value <= Int.MAX_VALUE.toDouble()) {
            "Scaled framebuffer dimension exceeds the supported range: $dimension * $scale"
        }
        return value.roundToInt().coerceAtLeast(1)
    }
    return Vec2i(scaled(size.x), scaled(size.y))
}

interface Framebuffer {
    val state: FramebufferState

    val depth: DepthAttachment?
    val stencil: StencilAttachment?
    val texture: TextureAttachment?

    val size: Vec2i
    val scale: Float

    fun init()
    fun delete()

    fun bind()
    fun bindTexture()
}
