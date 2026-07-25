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

package de.bixilon.minosoft.gui.rendering.gui.elements.input.cycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CycleSelectionTest {
    @Test
    fun `selection wraps in both directions`() {
        assertEquals(0, CycleSelection.move(2, 3, 1))
        assertEquals(2, CycleSelection.move(0, 3, -1))
    }

    @Test
    fun `large deltas cannot overflow`() {
        assertEquals(1, CycleSelection.move(0, 3, Int.MAX_VALUE))
        assertEquals(1, CycleSelection.move(0, 3, Int.MIN_VALUE))
    }

    @Test
    fun `invalid current index is clamped before moving`() {
        assertEquals(1, CycleSelection.move(-20, 3, 1))
        assertEquals(0, CycleSelection.move(20, 3, 1))
    }

    @Test
    fun `empty selectors are rejected`() {
        assertFailsWith<IllegalArgumentException> { CycleSelection.move(0, 0, 1) }
    }
}
