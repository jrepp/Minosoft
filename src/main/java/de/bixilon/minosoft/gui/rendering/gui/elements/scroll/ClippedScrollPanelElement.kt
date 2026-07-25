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

package de.bixilon.minosoft.gui.rendering.gui.elements.scroll

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.GuiClipRect
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.ClippedGuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import de.bixilon.minosoft.gui.rendering.util.vec.vec4.Vec4fUtil.bottom
import de.bixilon.minosoft.gui.rendering.util.vec.vec4.Vec4fUtil.left
import de.bixilon.minosoft.gui.rendering.util.vec.vec4.Vec4fUtil.right
import de.bixilon.minosoft.gui.rendering.util.vec.vec4.Vec4fUtil.top

class ClippedScrollPanelElement(
    guiRenderer: GUIRenderer,
    viewportSize: Vec2f,
    rows: List<Element> = emptyList(),
    spacing: Float = DEFAULT_SPACING,
) : Element(guiRenderer), AbstractLayout<Element> {
    private data class PositionedRow(
        val element: Element,
        val start: Float,
        val contentStart: Float,
        val end: Float,
    )

    private val rows = mutableListOf<Element>()
    private var positionedRows = emptyList<PositionedRow>()
    val scroll = ScrollViewportState()

    override var activeElement: Element? = null
    override var activeDragElement: Element? = null
    override val canFocus: Boolean get() = rows.any(Element::canFocus)

    var spacing: Float = spacing
        set(value) {
            require(value.isFinite() && value >= 0.0f) { "Scroll panel spacing must be finite and non-negative." }
            if (field == value) return
            field = value
            forceApply()
        }

    init {
        require(viewportSize.x.isFinite() && viewportSize.y.isFinite() && viewportSize.x >= 0.0f && viewportSize.y >= 0.0f) {
            "Scroll panel viewport must be finite and non-negative."
        }
        require(spacing.isFinite() && spacing >= 0.0f) { "Scroll panel spacing must be finite and non-negative." }
        size = viewportSize
        setRows(rows)
    }

    fun setRows(rows: List<Element>) {
        require(rows.size <= MAX_ROWS) { "A scroll panel may contain at most $MAX_ROWS rows." }
        for (row in this.rows) row.parent = null
        this.rows.clear()
        for (row in rows) {
            row.parent = this
            this.rows += row
        }
        activeElement = null
        activeDragElement = null
        forceApply()
    }

    fun focusNext(): Boolean {
        val focusable = rows.filter(Element::canFocus)
        if (focusable.isEmpty()) return false
        val current = focusable.indexOf(activeElement)
        val next = focusable[(current + 1).mod(focusable.size)]
        if (next !== activeElement) {
            activeElement?.onMouseLeave()
            activeElement = next
            next.onMouseEnter(Vec2f.EMPTY, Vec2f.EMPTY)
            reveal(next)
        }
        return true
    }

    override fun forceSilentApply() {
        val width = size.x
        val positioned = ArrayList<PositionedRow>(rows.size)
        var y = 0.0f
        var preferredWidth = 0.0f
        for ((index, row) in rows.withIndex()) {
            val rowMaxSize = Vec2f(width, -1.0f)
            if (row.prefMaxSize != rowMaxSize) row.prefMaxSize = rowMaxSize
            row.silentApply()
            val start = y
            y += row.margin.top
            val contentStart = y
            y += row.size.y
            y += row.margin.bottom
            positioned += PositionedRow(row, start, contentStart, y)
            preferredWidth = maxOf(preferredWidth, row.prefSize.x + row.margin.left + row.margin.right)
            if (index != rows.lastIndex) y += spacing
        }
        positionedRows = positioned
        _prefSize = Vec2f(preferredWidth, y)
        scroll.update(y, size.y)
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        val viewport = size
        if (viewport.x <= 0.0f || viewport.y <= 0.0f) return
        val clipped = ClippedGuiVertexConsumer(consumer, GuiClipRect(offset, offset + viewport))
        for (row in positionedRows) {
            if (!scroll.visible(row.start, row.end, viewport.y)) continue
            row.element.render(
                offset + Vec2f(row.element.margin.left, row.contentStart - scroll.offset),
                clipped,
                options,
            )
        }
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        if (position.x < 0.0f || position.x >= size.x || position.y < 0.0f || position.y >= size.y) return null
        val contentY = position.y + scroll.offset
        val row = positionedRows.binarySearchBy(contentY) { it.start }.let { index ->
            val resolved = if (index >= 0) index else -index - 2
            positionedRows.getOrNull(resolved)
        } ?: return null
        if (contentY < row.contentStart || contentY >= row.contentStart + row.element.size.y) return null
        val x = position.x - row.element.margin.left
        if (x < 0.0f || x >= row.element.size.x) return null
        return row.element to Vec2f(x, contentY - row.contentStart)
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        super<AbstractLayout>.onMouseEnter(position, absolute)
        return true
    }

    override fun onMouseMove(position: Vec2f, absolute: Vec2f): Boolean {
        super<AbstractLayout>.onMouseMove(position, absolute)
        return true
    }

    override fun onMouseLeave(): Boolean {
        super<AbstractLayout>.onMouseLeave()
        return true
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        val pair = getAt(position)
        if (pair?.first?.onScroll(pair.second, scrollOffset) == true) return true
        val delta = when {
            scrollOffset.y != 0.0f -> -scrollOffset.y
            else -> -scrollOffset.x
        }
        val previous = scroll.offset
        scroll.scrollBy(delta * PIXELS_PER_SCROLL)
        if (scroll.offset != previous) {
            activeElement?.onMouseLeave()
            activeElement = null
            cacheUpToDate = false
        }
        return true
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (type != KeyChangeTypes.RELEASE && key == KeyCodes.KEY_TAB) return focusNext()
        return super<AbstractLayout>.onKey(key, type)
    }

    override fun onChildChange(child: Element) {
        forceApply()
    }

    override fun tick() {
        super.tick()
        for (row in positionedRows) {
            if (scroll.visible(row.start, row.end, size.y)) row.element.tick()
        }
    }

    override fun onOpen() {
        for (row in rows) row.onOpen()
    }

    override fun onHide() {
        clearFocus()
        for (row in rows) row.onHide()
    }

    override fun onClose() {
        clearFocus()
        for (row in rows) row.onClose()
    }

    private fun reveal(element: Element) {
        val row = positionedRows.firstOrNull { it.element === element } ?: return
        when {
            row.start < scroll.offset -> scroll.scrollTo(row.start)
            row.end > scroll.offset + size.y -> scroll.scrollTo(row.end - size.y)
        }
        cacheUpToDate = false
    }

    private fun clearFocus() {
        activeElement?.onMouseLeave()
        activeElement = null
        activeDragElement = null
    }

    companion object {
        const val MAX_ROWS = 65_536
        private const val DEFAULT_SPACING = 4.0f
        private const val PIXELS_PER_SCROLL = 12.0f
    }
}
