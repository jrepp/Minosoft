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

package de.bixilon.minosoft.gui.rendering.gui.elements.tab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabSelectionTest {
    @Test
    fun `selection moves and wraps`() {
        val tabs = TabSelection(listOf("general", "quality", "advanced"))
        assertTrue(tabs.move(-1))
        assertEquals(tabs.selected, "advanced")
        assertTrue(tabs.move(1))
        assertEquals(tabs.selected, "general")
    }

    @Test
    fun `selecting current tab does not notify a change`() {
        val tabs = TabSelection(listOf("general", "advanced"))
        assertFalse(tabs.select("general"))
        assertTrue(tabs.select("advanced"))
    }
}
