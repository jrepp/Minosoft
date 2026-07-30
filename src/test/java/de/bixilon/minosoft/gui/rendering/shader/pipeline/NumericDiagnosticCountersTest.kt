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

import kotlin.test.Test
import kotlin.test.assertEquals

class NumericDiagnosticCountersTest {
    @Test
    fun `counter table records collisions without allocation-backed growth`() {
        val counters = NumericDiagnosticCounters(capacity = 4)
        counters.add(0)
        counters.add(4, 2L)
        counters.add(8, 3L)
        counters.add(0, 5L)

        val snapshot = linkedMapOf<Int, Long>()
        counters.forEach(snapshot::put)
        assertEquals(6L, snapshot[0])
        assertEquals(2L, snapshot[4])
        assertEquals(3L, snapshot[8])
        assertEquals(0L, counters.overflow)
    }

    @Test
    fun `counter table bounds excess diagnostic cardinality`() {
        val counters = NumericDiagnosticCounters(capacity = 2)
        counters.add(1)
        counters.add(2)
        counters.add(3, 4L)

        assertEquals(4L, counters.overflow)
    }
}
