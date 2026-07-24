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

package de.bixilon.minosoft.gui.rendering.gui.elements.input.slider

import kotlin.math.roundToLong

object SteppedSliderSteps {

    fun clamp(step: Int, minimum: Int, maximum: Int): Int {
        require(minimum <= maximum) { "Minimum step must not exceed maximum step!" }
        return step.coerceIn(minimum, maximum)
    }

    fun fromPosition(position: Float, width: Float, minimum: Int, maximum: Int, handleWidth: Float): Int {
        require(minimum <= maximum) { "Minimum step must not exceed maximum step!" }
        require(width.isFinite() && handleWidth.isFinite()) { "Slider dimensions must be finite." }
        if (!position.isFinite()) return minimum
        if (minimum == maximum) {
            return minimum
        }

        val travel = (width - handleWidth).coerceAtLeast(1.0f)
        val progress = ((position - handleWidth / 2.0f) / travel).coerceIn(0.0f, 1.0f)
        val range = maximum.toLong() - minimum
        return (minimum.toLong() + (progress * range).roundToLong()).coerceIn(minimum.toLong(), maximum.toLong()).toInt()
    }

    fun handleOffset(step: Int, width: Float, minimum: Int, maximum: Int, handleWidth: Float): Float {
        require(minimum <= maximum) { "Minimum step must not exceed maximum step!" }
        require(width.isFinite() && handleWidth.isFinite()) { "Slider dimensions must be finite." }
        val travel = (width - handleWidth).coerceAtLeast(0.0f)
        if (minimum == maximum) {
            return travel / 2.0f
        }

        val clamped = clamp(step, minimum, maximum)
        return travel * (clamped.toLong() - minimum) / (maximum.toLong() - minimum).toFloat()
    }
}
