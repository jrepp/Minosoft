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

package de.bixilon.minosoft.data.entities.entities.player

import java.util.concurrent.atomic.AtomicLongArray

class ArmSwingState(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val started = AtomicLongArray(Arms.VALUES.size).apply {
        for (index in 0 until length()) set(index, NO_SWING)
    }

    fun swing(arm: Arms) {
        val now = nowNanos()
        while (true) {
            val start = started.get(arm.ordinal)
            if (start != NO_SWING && (now - start).coerceAtLeast(0L) < DURATION_NANOS) return
            if (started.compareAndSet(arm.ordinal, start, now)) return
        }
    }

    fun progress(arm: Arms): Float? {
        val start = started.get(arm.ordinal)
        if (start == NO_SWING) return null
        val elapsed = (nowNanos() - start).coerceAtLeast(0L)
        if (elapsed >= DURATION_NANOS) {
            started.compareAndSet(arm.ordinal, start, NO_SWING)
            return null
        }
        return elapsed.toFloat() / DURATION_NANOS
    }

    companion object {
        const val DURATION_NANOS = 200_000_000L
        private const val NO_SWING = Long.MIN_VALUE
    }
}
