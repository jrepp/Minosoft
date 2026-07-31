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

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderTimingWindowTest {
    @Test
    fun `reports nearest-rank median and p95`() {
        val window = RenderTimingWindow(20)
        for (value in 1L..20L) window.add(value)

        assertEquals(20, window.samples)
        assertEquals(10L, window.percentile(0.5))
        assertEquals(19L, window.percentile(0.95))
        assertEquals(RenderTimingWindow.Snapshot(20, 10L, 19L), window.snapshot())
    }

    @Test
    fun `retains only bounded latest samples`() {
        val window = RenderTimingWindow(3)
        for (value in 1L..5L) window.add(value)

        assertEquals(3, window.samples)
        assertEquals(4L, window.percentile(0.5))
        assertEquals(5L, window.percentile(0.95))
        assertEquals(RenderTimingWindow.Snapshot(3, 4L, 5L), window.snapshot())
        assertThrows<IllegalArgumentException> { window.add(-1L) }
    }
}
