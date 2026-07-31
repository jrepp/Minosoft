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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderStatsTest {
    @Test
    fun `experimental presentation retains real timing statistics`() {
        val stats = ExperimentalRenderStats()

        stats.startFrame()
        stats.endDraw()
        stats.endFrame()

        val timing = stats.timingSnapshot
        assertEquals(1, timing.samples)
        assertTrue(timing.medianFrameNanos >= 0L)
        assertTrue(timing.p95FrameNanos >= timing.medianFrameNanos)
        assertTrue(timing.medianDrawNanos >= 0L)
        assertTrue(timing.p95DrawNanos >= timing.medianDrawNanos)
    }
}
