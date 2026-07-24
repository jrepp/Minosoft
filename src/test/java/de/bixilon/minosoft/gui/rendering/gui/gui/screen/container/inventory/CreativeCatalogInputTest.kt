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

import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreativeCatalogInputTest {

    @Test
    fun `both left mouse aliases focus the search bar`() {
        assertTrue(CreativeCatalogInput.isPrimary(MouseButtons.LEFT))
        assertTrue(CreativeCatalogInput.isPrimary(MouseButtons.BUTTON_1))
        assertFalse(CreativeCatalogInput.isPrimary(MouseButtons.RIGHT))
    }

    @Test
    fun `character input is captured only by the creative catalog`() {
        assertTrue(CreativeCatalogInput.capturesCharacters(creative = true))
        assertFalse(CreativeCatalogInput.capturesCharacters(creative = false))
    }
}
