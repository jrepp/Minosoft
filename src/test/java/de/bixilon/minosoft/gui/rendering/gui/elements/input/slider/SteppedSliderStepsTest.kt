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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SteppedSliderStepsTest {

    @Test
    fun `position maps to nearest discrete step`() {
        assertEquals(0, SteppedSliderSteps.fromPosition(3.0f, 106.0f, 0, 10, 6.0f))
        assertEquals(5, SteppedSliderSteps.fromPosition(53.0f, 106.0f, 0, 10, 6.0f))
        assertEquals(10, SteppedSliderSteps.fromPosition(103.0f, 106.0f, 0, 10, 6.0f))
    }

    @Test
    fun `position clamps beyond both ends`() {
        assertEquals(2, SteppedSliderSteps.fromPosition(-100.0f, 106.0f, 2, 8, 6.0f))
        assertEquals(8, SteppedSliderSteps.fromPosition(200.0f, 106.0f, 2, 8, 6.0f))
    }

    @Test
    fun `handle offsets span available travel`() {
        assertEquals(0.0f, SteppedSliderSteps.handleOffset(0, 106.0f, 0, 10, 6.0f))
        assertEquals(50.0f, SteppedSliderSteps.handleOffset(5, 106.0f, 0, 10, 6.0f))
        assertEquals(100.0f, SteppedSliderSteps.handleOffset(10, 106.0f, 0, 10, 6.0f))
    }

    @Test
    fun `single-value and narrow sliders remain stable`() {
        assertEquals(4, SteppedSliderSteps.fromPosition(500.0f, 2.0f, 4, 4, 6.0f))
        assertEquals(0.0f, SteppedSliderSteps.handleOffset(4, 2.0f, 4, 4, 6.0f))
    }

    @Test
    fun `full integer step ranges do not overflow`() {
        assertEquals(Int.MIN_VALUE, SteppedSliderSteps.fromPosition(0.0f, 100.0f, Int.MIN_VALUE, Int.MAX_VALUE, 0.0f))
        assertEquals(Int.MAX_VALUE, SteppedSliderSteps.fromPosition(100.0f, 100.0f, Int.MIN_VALUE, Int.MAX_VALUE, 0.0f))
        assertEquals(100.0f, SteppedSliderSteps.handleOffset(Int.MAX_VALUE, 100.0f, Int.MIN_VALUE, Int.MAX_VALUE, 0.0f))
    }

    @Test
    fun `non-finite pointer positions fall back to the minimum`() {
        assertEquals(2, SteppedSliderSteps.fromPosition(Float.NaN, 100.0f, 2, 8, 6.0f))
    }
}
