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

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes

class TabBarElement<T : Any>(
    guiRenderer: GUIRenderer,
    tabs: List<T>,
    initial: T = tabs.firstOrNull() ?: throw IllegalArgumentException("Tabs must not be empty."),
    private val label: (T) -> Any = { it.toString() },
    private val changed: (T) -> Unit,
) : Element(guiRenderer), AbstractLayout<ButtonElement> {
    val selection = TabSelection(tabs, initial)
    private val buttons = selection.tabs.map { tab ->
        ButtonElement(guiRenderer, label(tab)) { select(tab) }.apply {
            parent = this@TabBarElement
        }
    }

    override var activeElement: ButtonElement? = null
    override var activeDragElement: ButtonElement? = null
    override val canFocus: Boolean get() = true

    init {
        _size = Vec2f(DEFAULT_WIDTH, HEIGHT)
        refresh()
    }

    fun select(tab: T, notify: Boolean = true): Boolean {
        if (!selection.select(tab)) return false
        refresh()
        if (notify) changed(tab)
        return true
    }

    override fun forceSilentApply() {
        val buttonWidth = size.x / buttons.size
        for (button in buttons) {
            val next = Vec2f(buttonWidth, size.y)
            if (button.size != next) button.size = next
            button.silentApply()
        }
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        val width = size.x / buttons.size
        for ((index, button) in buttons.withIndex()) {
            button.render(offset + Vec2f(index * width, 0.0f), consumer, options)
        }
    }

    override fun getAt(position: Vec2f): Pair<ButtonElement, Vec2f>? {
        if (position.x < 0.0f || position.y < 0.0f || position.x >= size.x || position.y >= size.y) return null
        val width = size.x / buttons.size
        val index = (position.x / width).toInt().coerceAtMost(buttons.lastIndex)
        return buttons[index] to Vec2f(position.x - index * width, position.y)
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (type != KeyChangeTypes.PRESS) return true
        val delta = when (key) {
            KeyCodes.KEY_LEFT, KeyCodes.KEY_UP -> -1
            KeyCodes.KEY_RIGHT, KeyCodes.KEY_DOWN -> 1
            else -> return super<AbstractLayout>.onKey(key, type)
        }
        if (selection.move(delta)) {
            refresh()
            changed(selection.selected)
        }
        return true
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    private fun refresh() {
        for ((index, button) in buttons.withIndex()) {
            val tab = selection.tabs[index]
            button.disabled = tab == selection.selected
            button.text = label(tab)
        }
        cacheUpToDate = false
    }

    private companion object {
        const val DEFAULT_WIDTH = 200.0f
        const val HEIGHT = 16.0f
    }
}
