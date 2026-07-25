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

object CheckboxInput {

    fun toggled(state: Boolean): Boolean = !state

    fun togglesOnMouse(button: MouseButtons, action: MouseActions, disabled: Boolean): Boolean {
        return !disabled && button == MouseButtons.LEFT && action == MouseActions.PRESS
    }

    fun togglesOnKey(key: KeyCodes, type: KeyChangeTypes, hovered: Boolean, disabled: Boolean): Boolean {
        return hovered && !disabled && key == KeyCodes.KEY_ENTER && type == KeyChangeTypes.PRESS
    }
}
