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

package de.bixilon.minosoft.gui.rendering.gui.gui.dragged.elements.item

class RightDragDistribution {
    private val slots = linkedSetOf<Int>()
    var active: Boolean = false
        private set

    fun start(slot: Int) {
        slots.clear()
        slots += slot
        active = true
    }

    fun add(slot: Int): Boolean {
        if (!active) {
            return false
        }
        return slots.add(slot)
    }

    fun finish(): List<Int> {
        val result = slots.toList()
        cancel()
        return result
    }

    fun cancel() {
        active = false
        slots.clear()
    }
}
