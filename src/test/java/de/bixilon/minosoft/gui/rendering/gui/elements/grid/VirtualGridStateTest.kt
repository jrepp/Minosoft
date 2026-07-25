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

package de.bixilon.minosoft.gui.rendering.gui.elements.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VirtualGridStateTest {
    @Test
    fun `computes content geometry and only visible cell range`() {
        val grid = VirtualGridState(columns = 4, cellHeight = 10.0f)
        assertEquals(grid.rowCount(9), 3)
        assertEquals(grid.contentHeight(9), 30.0f)
        assertEquals(grid.visibleItems(100, scrollOffset = 15.0f, viewportHeight = 20.0f), GridCellRange(4, 16))
    }

    @Test
    fun `hit testing includes scroll offset and rejects empty cells`() {
        val grid = VirtualGridState(columns = 3, cellHeight = 12.0f)
        assertEquals(grid.indexAt(x = 21.0f, y = 2.0f, cellWidth = 10.0f, scrollOffset = 12.0f, itemCount = 8), 5)
        assertNull(grid.indexAt(x = 21.0f, y = 14.0f, cellWidth = 10.0f, scrollOffset = 12.0f, itemCount = 8))
    }
}
