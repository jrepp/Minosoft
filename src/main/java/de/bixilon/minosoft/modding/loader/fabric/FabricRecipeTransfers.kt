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

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.recipes.Recipe

data class FabricRecipeTransferContext(
    val renderer: GUIRenderer,
    val recipe: Recipe,
)

interface FabricRecipeTransferHandler {
    fun supports(context: FabricRecipeTransferContext): Boolean
    fun transfer(context: FabricRecipeTransferContext): Boolean
}

object FabricRecipeTransfers {
    private val handlers = FabricHookRegistry<FabricRecipeTransferHandler>("recipe-transfers")

    fun register(owner: String, handler: FabricRecipeTransferHandler): AutoCloseable = handlers.register(owner, handler)

    fun supported(context: FabricRecipeTransferContext): Boolean =
        handlers.snapshot().any { it.hook.supports(context) }

    fun transfer(context: FabricRecipeTransferContext): Boolean {
        for (handler in handlers.snapshot()) {
            if (handler.hook.supports(context) && handler.hook.transfer(context)) return true
        }
        return false
    }
}
