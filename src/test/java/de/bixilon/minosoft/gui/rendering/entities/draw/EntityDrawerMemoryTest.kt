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

package de.bixilon.minosoft.gui.rendering.entities.draw

import kotlin.test.Test
import kotlin.test.assertEquals

class EntityDrawerMemoryTest {
    @Test
    fun `frame list releases a peak only after sustained low use`() {
        val trimmer = SustainedLowUseListTrimmer(
            minimumPeak = 8,
            lowUseFrames = 2,
            minimumRetainedCapacity = 2,
        )
        val list = ArrayList<Int>(16).apply { repeat(16, ::add) }

        trimmer.clear(list)
        assertEquals(16, trimmer.estimatedCapacity)
        repeat(2) {
            list += 1
            trimmer.clear(list)
        }

        assertEquals(2, trimmer.estimatedCapacity)
        assertEquals(0, list.size)
    }
}
