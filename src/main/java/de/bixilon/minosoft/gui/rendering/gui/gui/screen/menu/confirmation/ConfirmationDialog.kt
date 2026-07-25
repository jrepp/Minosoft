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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.confirmation

import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement

data class DialogAction(
    val label: Any,
    val closeAfterAction: Boolean = true,
    val action: () -> Unit,
)

class ConfirmationDialog(
    guiRenderer: GUIRenderer,
    title: Any,
    message: Any,
    actions: List<DialogAction>,
) : AbstractConfirmationMenu(guiRenderer, title, message) {
    private val actions = actions.toList()

    init {
        require(this.actions.isNotEmpty()) { "A confirmation dialog must contain at least one action." }
        require(this.actions.size <= MAX_ACTIONS) { "A confirmation dialog may contain at most $MAX_ACTIONS actions." }
        initButtons()
    }

    override fun createButtons(): Array<ButtonElement> {
        return actions.map { action ->
            ButtonElement(guiRenderer, action.label) {
                action.action()
                if (action.closeAfterAction) close()
            }
        }.toTypedArray()
    }

    private companion object {
        const val MAX_ACTIONS = 16
    }
}
