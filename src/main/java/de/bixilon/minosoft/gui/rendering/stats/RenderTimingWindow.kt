/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.stats

import kotlin.math.ceil

class RenderTimingWindow(
    capacity: Int = 600,
) {
    private val values = LongArray(capacity)
    private var next = 0
    private var size = 0

    init {
        require(capacity > 0) { "Timing window capacity must be positive" }
    }

    @Synchronized
    fun add(nanoseconds: Long) {
        require(nanoseconds >= 0L) { "Timing sample must not be negative" }
        values[next] = nanoseconds
        next = (next + 1) % values.size
        if (size < values.size) size++
    }

    @Synchronized
    fun percentile(percentile: Double): Long {
        require(percentile in 0.0..1.0) { "Percentile must be in [0, 1]" }
        if (size == 0) return 0L
        val sorted = values.copyOf(size)
        sorted.sort()
        val index = (ceil(percentile * size).toInt() - 1).coerceIn(0, size - 1)
        return sorted[index]
    }

    val samples: Int
        @Synchronized get() = size
}
