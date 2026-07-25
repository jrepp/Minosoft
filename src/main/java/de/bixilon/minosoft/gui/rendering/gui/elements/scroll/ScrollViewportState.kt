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

package de.bixilon.minosoft.gui.rendering.gui.elements.scroll

class ScrollViewportState {
    var offset: Float = 0.0f
        private set
    var maximumOffset: Float = 0.0f
        private set

    fun update(contentHeight: Float, viewportHeight: Float): Float {
        require(contentHeight.isFinite() && contentHeight >= 0.0f) { "Content height must be finite and non-negative." }
        require(viewportHeight.isFinite() && viewportHeight >= 0.0f) { "Viewport height must be finite and non-negative." }
        maximumOffset = maxOf(0.0f, contentHeight - viewportHeight)
        offset = offset.coerceIn(0.0f, maximumOffset)
        return offset
    }

    fun scrollBy(pixels: Float): Float {
        if (!pixels.isFinite()) return offset
        offset = (offset + pixels).coerceIn(0.0f, maximumOffset)
        return offset
    }

    fun scrollTo(pixels: Float): Float {
        offset = if (pixels.isFinite()) pixels.coerceIn(0.0f, maximumOffset) else 0.0f
        return offset
    }

    fun visible(start: Float, end: Float, viewportHeight: Float): Boolean {
        return end > offset && start < offset + viewportHeight
    }
}
