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

package de.bixilon.minosoft.gui.rendering.system.opengl.texture

import de.bixilon.kmath.vec.vec2.i.Vec2i

object OpenGlTextureSizing {

    fun growPowerOfTwo(current: Int, required: Int): Int {
        require(current > 0 && required > 0) { "Texture resolutions must be positive: current=$current, required=$required" }
        if (required <= current) return current

        val highest = Integer.highestOneBit(required - 1)
        require(highest <= Int.MAX_VALUE / 2) { "Texture resolution is too large: $required" }
        return highest shl 1
    }

    fun arraySize(minimum: Int, sizes: Collection<Vec2i>): Vec2i {
        require(minimum > 0) { "Minimum texture resolution must be positive: $minimum" }
        var width = minimum
        var height = minimum
        for (size in sizes) {
            require(size.x > 0 && size.y > 0) { "Texture dimensions must be positive: $size" }
            width = maxOf(width, size.x)
            height = maxOf(height, size.y)
        }
        return Vec2i(width, height)
    }
}
