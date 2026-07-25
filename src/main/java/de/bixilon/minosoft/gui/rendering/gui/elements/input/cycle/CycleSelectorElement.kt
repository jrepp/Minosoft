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

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.atlas.Atlas.Companion.get
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments.Companion.getOffset
import de.bixilon.minosoft.gui.rendering.gui.elements.VerticalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.VerticalAlignments.Companion.getOffset
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.AtlasImageElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.CursorShapes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes

class CycleSelectorElement<T : Any>(
    guiRenderer: GUIRenderer,
    options: List<T>,
    initialValue: T,
    private val label: (T) -> Any = { it.toString() },
    disabled: Boolean = false,
    private val onChange: (T) -> Unit,
) : Element(guiRenderer) {
    val options: List<T> = options.toList()
    private val atlas = guiRenderer.atlas[minecraft("elements/button")]
    private val disabledAtlas = atlas["disabled"]
    private val normalAtlas = atlas["normal"]
    private val hoveredAtlas = atlas["hovered"]
    private val textElement: TextElement
    private var selectedIndex: Int
    private var hovered = false
        set(value) {
            if (field == value) return
            field = value
            forceApply()
        }

    var disabled: Boolean = disabled
        set(value) {
            if (field == value) return
            field = value
            forceApply()
        }

    val value: T get() = options[selectedIndex]
    override val canFocus: Boolean get() = !disabled

    init {
        require(this.options.isNotEmpty()) { "A cycle selector must contain at least one option." }
        require(this.options.size <= MAX_OPTIONS) { "A cycle selector may contain at most $MAX_OPTIONS options." }
        require(this.options.distinct().size == this.options.size) { "Cycle selector options must be unique." }
        selectedIndex = this.options.indexOf(initialValue)
        require(selectedIndex >= 0) { "Initial cycle selector value is not an available option." }
        textElement = TextElement(guiRenderer, label(value), background = null, parent = this)
        size = Vec2f(textElement.size.x + TEXT_PADDING * 2.0f, HEIGHT)
    }

    fun setValue(value: T, notify: Boolean = true) {
        val index = options.indexOf(value)
        require(index >= 0) { "Cycle selector value is not an available option." }
        setIndex(index, notify)
    }

    fun move(delta: Int) {
        if (disabled) return
        setIndex(CycleSelection.move(selectedIndex, options.size, delta), notify = true)
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        val texture = when {
            disabled -> disabledAtlas
            hovered -> hoveredAtlas
            else -> normalAtlas
        } ?: guiRenderer.context.textures.whiteTexture
        AtlasImageElement(guiRenderer, texture, size = size).render(offset, consumer, options)
        val textSize = textElement.size
        textElement.render(
            offset + Vec2f(
                HorizontalAlignments.CENTER.getOffset(size.x, textSize.x),
                VerticalAlignments.CENTER.getOffset(size.y, textSize.y),
            ),
            consumer,
            options,
        )
    }

    override fun forceSilentApply() {
        textElement.silentApply()
        cacheUpToDate = false
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (disabled || action != MouseActions.PRESS) return true
        when (button) {
            MouseButtons.LEFT -> move(1)
            MouseButtons.RIGHT -> move(-1)
            else -> Unit
        }
        return true
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (disabled || !hovered || type == KeyChangeTypes.RELEASE) return true
        when (key) {
            KeyCodes.KEY_ENTER, KeyCodes.KEY_RIGHT -> move(1)
            KeyCodes.KEY_LEFT -> move(-1)
            KeyCodes.KEY_HOME -> setIndex(0, notify = true)
            KeyCodes.KEY_END -> setIndex(options.lastIndex, notify = true)
            else -> Unit
        }
        return true
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        if (disabled) return true
        when {
            scrollOffset.y > 0.0f -> move(-1)
            scrollOffset.y < 0.0f -> move(1)
        }
        return true
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        hovered = true
        if (!disabled) context.window.cursorShape = CursorShapes.HAND
        return true
    }

    override fun onMouseLeave(): Boolean {
        hovered = false
        context.window.resetCursor()
        return true
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    private fun setIndex(index: Int, notify: Boolean) {
        if (selectedIndex == index) return
        selectedIndex = index
        textElement.text = label(value)
        if (notify) {
            if (guiRenderer.session.profiles.audio.gui.button) {
                guiRenderer.session.world.audio?.play2D(CLICK_SOUND)
            }
            onChange(value)
        }
        forceApply()
    }

    companion object {
        private val CLICK_SOUND = minecraft("ui.button.click")
        private const val HEIGHT = 20.0f
        private const val TEXT_PADDING = 4.0f
        const val MAX_OPTIONS = 4_096
    }
}
