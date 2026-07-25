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

object CycleSelection {
    fun move(index: Int, optionCount: Int, delta: Int): Int {
        require(optionCount > 0) { "A cycle selector must contain at least one option." }
        val current = index.coerceIn(0, optionCount - 1)
        return Math.floorMod(current.toLong() + delta.toLong(), optionCount.toLong()).toInt()
    }
}
