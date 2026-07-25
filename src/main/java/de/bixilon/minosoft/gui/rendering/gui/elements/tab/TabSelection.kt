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

class TabSelection<T : Any>(
    tabs: List<T>,
    initial: T = tabs.firstOrNull() ?: throw IllegalArgumentException("Tabs must not be empty."),
) {
    val tabs = tabs.toList()
    var selected: T = initial
        private set

    init {
        require(this.tabs.isNotEmpty()) { "Tabs must not be empty." }
        require(this.tabs.size <= MAX_TABS) { "A tab selection may contain at most $MAX_TABS tabs." }
        require(this.tabs.distinct().size == this.tabs.size) { "Tabs must be unique." }
        require(initial in this.tabs) { "Initial tab must be one of the available tabs." }
    }

    fun select(tab: T): Boolean {
        require(tab in tabs) { "Unknown tab: $tab" }
        if (selected == tab) return false
        selected = tab
        return true
    }

    fun move(delta: Int): Boolean {
        if (delta == 0 || tabs.size == 1) return false
        val current = tabs.indexOf(selected)
        return select(tabs[Math.floorMod(current + delta, tabs.size)])
    }

    companion object {
        const val MAX_TABS = 256
    }
}
