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
    data class Snapshot(
        val samples: Int,
        val medianNanos: Long,
        val p95Nanos: Long,
    )

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
        return percentile(sorted, percentile)
    }

    @Synchronized
    fun snapshot(): Snapshot {
        if (size == 0) return Snapshot(0, 0L, 0L)
        val sorted = values.copyOf(size)
        sorted.sort()
        return Snapshot(
            samples = size,
            medianNanos = percentile(sorted, 0.5),
            p95Nanos = percentile(sorted, 0.95),
        )
    }

    val samples: Int
        @Synchronized get() = size

    private fun percentile(sorted: LongArray, percentile: Double): Long {
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
