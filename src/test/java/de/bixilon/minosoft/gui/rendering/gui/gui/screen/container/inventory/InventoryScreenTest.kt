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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventoryScreenTest {

    @Test
    fun `pressing e closes the inventory`() {
        assertTrue(InventoryScreen.shouldClose(KeyCodes.KEY_E, KeyChangeTypes.PRESS, searchFocused = false))
        assertFalse(InventoryScreen.shouldClose(KeyCodes.KEY_E, KeyChangeTypes.REPEAT, searchFocused = false))
        assertFalse(InventoryScreen.shouldClose(KeyCodes.KEY_E, KeyChangeTypes.RELEASE, searchFocused = false))
    }

    @Test
    fun `e remains available while typing a creative search`() {
        assertFalse(InventoryScreen.shouldClose(KeyCodes.KEY_E, KeyChangeTypes.PRESS, searchFocused = true))
        assertFalse(InventoryScreen.shouldClose(KeyCodes.KEY_ESCAPE, KeyChangeTypes.PRESS, searchFocused = false))
    }

    @Test
    fun `tab routes to the creative catalog while search is not focused`() {
        assertTrue(
            InventoryScreen.shouldRouteToCreativeCatalog(
                KeyCodes.KEY_TAB,
                KeyChangeTypes.PRESS,
                creative = true,
                searchFocused = false,
            ),
        )
        assertFalse(
            InventoryScreen.shouldRouteToCreativeCatalog(
                KeyCodes.KEY_TAB,
                KeyChangeTypes.PRESS,
                creative = false,
                searchFocused = false,
            ),
        )
        assertFalse(
            InventoryScreen.shouldRouteToCreativeCatalog(
                KeyCodes.KEY_TAB,
                KeyChangeTypes.RELEASE,
                creative = true,
                searchFocused = false,
            ),
        )
    }
}
