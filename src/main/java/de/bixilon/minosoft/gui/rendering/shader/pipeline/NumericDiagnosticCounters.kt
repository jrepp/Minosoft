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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

/**
 * Bounded primitive counter storage for diagnostic routes that are sparse but
 * too numerous for a dense array. Recording never allocates or grows.
 */
internal class NumericDiagnosticCounters(capacity: Int = 256) {
    private val keys: IntArray
    private val counts: LongArray
    private val mask: Int
    var overflow: Long = 0L
        private set

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "Numeric diagnostic capacity must be a positive power of two"
        }
        keys = IntArray(capacity) { EMPTY }
        counts = LongArray(capacity)
        mask = capacity - 1
    }

    fun add(key: Int, amount: Long = 1L) {
        require(key >= 0) { "Numeric diagnostic keys must not be negative" }
        require(amount >= 0L) { "Numeric diagnostic increments must not be negative" }
        var index = mix(key) and mask
        repeat(keys.size) {
            when (keys[index]) {
                EMPTY -> {
                    keys[index] = key
                    counts[index] = amount
                    return
                }
                key -> {
                    counts[index] = saturatingAdd(counts[index], amount)
                    return
                }
            }
            index = (index + 1) and mask
        }
        overflow = saturatingAdd(overflow, amount)
    }

    fun forEach(action: (key: Int, count: Long) -> Unit) {
        for (index in keys.indices) {
            val key = keys[index]
            if (key != EMPTY) action(key, counts[index])
        }
    }

    private fun mix(value: Int): Int {
        var mixed = value
        mixed = mixed xor (mixed ushr 16)
        mixed *= -0x7a143595
        mixed = mixed xor (mixed ushr 13)
        mixed *= -0x3d4d51cb
        return mixed xor (mixed ushr 16)
    }

    private fun saturatingAdd(current: Long, amount: Long): Long =
        if (amount > 0L && current > Long.MAX_VALUE - amount) Long.MAX_VALUE else current + amount

    private companion object {
        const val EMPTY = -1
    }
}
