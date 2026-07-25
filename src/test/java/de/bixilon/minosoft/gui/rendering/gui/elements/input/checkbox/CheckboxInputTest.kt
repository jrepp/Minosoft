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

package de.bixilon.minosoft.gui.rendering.gui.elements.input.checkbox

import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckboxInputTest {

    @Test
    fun `toggle inverts both boolean states`() {
        assertTrue(CheckboxInput.toggled(false))
        assertFalse(CheckboxInput.toggled(true))
    }

    @Test
    fun `only enabled primary mouse presses toggle`() {
        assertTrue(CheckboxInput.togglesOnMouse(MouseButtons.LEFT, MouseActions.PRESS, disabled = false))
        assertFalse(CheckboxInput.togglesOnMouse(MouseButtons.LEFT, MouseActions.RELEASE, disabled = false))
        assertFalse(CheckboxInput.togglesOnMouse(MouseButtons.RIGHT, MouseActions.PRESS, disabled = false))
        assertFalse(CheckboxInput.togglesOnMouse(MouseButtons.LEFT, MouseActions.PRESS, disabled = true))
    }

    @Test
    fun `only enabled hovered enter presses toggle`() {
        assertTrue(CheckboxInput.togglesOnKey(KeyCodes.KEY_ENTER, KeyChangeTypes.PRESS, hovered = true, disabled = false))
        assertFalse(CheckboxInput.togglesOnKey(KeyCodes.KEY_ENTER, KeyChangeTypes.RELEASE, hovered = true, disabled = false))
        assertFalse(CheckboxInput.togglesOnKey(KeyCodes.KEY_SPACE, KeyChangeTypes.PRESS, hovered = true, disabled = false))
        assertFalse(CheckboxInput.togglesOnKey(KeyCodes.KEY_ENTER, KeyChangeTypes.PRESS, hovered = false, disabled = false))
        assertFalse(CheckboxInput.togglesOnKey(KeyCodes.KEY_ENTER, KeyChangeTypes.PRESS, hovered = true, disabled = true))
    }
}
