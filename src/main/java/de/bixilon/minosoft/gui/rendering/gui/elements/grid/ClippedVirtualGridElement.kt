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

package de.bixilon.minosoft.gui.rendering.gui.elements.grid

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.scroll.ScrollViewportState
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.GuiClipRect
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.ClippedGuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer

class ClippedVirtualGridElement<T : Any>(
    guiRenderer: GUIRenderer,
    viewportSize: Vec2f,
    columns: Int,
    cellHeight: Float,
    items: List<T> = emptyList(),
    private val createCell: (T) -> Element,
) : Element(guiRenderer), AbstractLayout<Element> {
    private val state = VirtualGridState(columns, cellHeight)
    private val scroll = ScrollViewportState()
    private val cells = linkedMapOf<Int, Element>()
    private var items = items.toList()
    private var opened = false

    override var activeElement: Element? = null
    override var activeDragElement: Element? = null
    override val canFocus: Boolean get() = cells.values.any(Element::canFocus)

    init {
        require(this.items.size <= MAX_ITEMS) { "A virtual grid may contain at most $MAX_ITEMS items." }
        size = viewportSize
    }

    fun setItems(items: List<T>) {
        require(items.size <= MAX_ITEMS) { "A virtual grid may contain at most $MAX_ITEMS items." }
        clearCells()
        this.items = items.toList()
        scroll.scrollTo(0.0f)
        forceApply()
    }

    override fun forceSilentApply() {
        scroll.update(state.contentHeight(items.size), size.y)
        synchronizeCells()
        val cellWidth = cellWidth()
        for (cell in cells.values) {
            val next = Vec2f(cellWidth, state.cellHeight)
            if (cell.prefMaxSize != next) cell.prefMaxSize = next
            if (cell.size != next) cell.size = next
            cell.silentApply()
        }
        _prefSize = Vec2f(size.x, state.contentHeight(items.size))
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        if (size.x <= 0.0f || size.y <= 0.0f) return
        synchronizeCells()
        val clipped = ClippedGuiVertexConsumer(consumer, GuiClipRect(offset, offset + size))
        for ((index, cell) in cells) cell.render(offset + cellOffset(index), clipped, options)
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        if (position.x < 0.0f || position.y < 0.0f || position.x >= size.x || position.y >= size.y) return null
        val index = state.indexAt(position.x, position.y, cellWidth(), scroll.offset, items.size) ?: return null
        val cell = cells[index] ?: create(index)
        return cell to (position - cellOffset(index))
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        val target = getAt(position)
        if (target?.first?.onScroll(target.second, scrollOffset) == true) return true
        val delta = if (scrollOffset.y != 0.0f) -scrollOffset.y else -scrollOffset.x
        val previous = scroll.offset
        scroll.scrollBy(delta * PIXELS_PER_SCROLL)
        if (scroll.offset != previous) {
            activeElement?.onMouseLeave()
            activeElement = null
            synchronizeCells()
            forceApply()
        }
        return true
    }

    override fun tick() {
        synchronizeCells()
        cells.values.forEach(Element::tick)
    }

    override fun onOpen() {
        synchronizeCells()
        opened = true
        cells.values.forEach(Element::onOpen)
    }

    override fun onHide() {
        opened = false
        activeElement?.onMouseLeave()
        activeElement = null
        cells.values.forEach(Element::onHide)
    }

    override fun onClose() {
        opened = false
        clearCells()
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    private fun synchronizeCells() {
        val range = state.visibleItems(items.size, scroll.offset, size.y)
        val remove = cells.keys.filter { it < range.first || it >= range.lastExclusive }
        for (index in remove) cells.remove(index)?.let {
            if (it === activeElement) {
                it.onMouseLeave()
                activeElement = null
            }
            it.onClose()
            it.parent = null
        }
        for (index in range.first until range.lastExclusive) if (index !in cells) create(index)
    }

    private fun create(index: Int): Element {
        return createCell(items[index]).also {
            it.parent = this
            cells[index] = it
            if (opened) it.onOpen()
        }
    }

    private fun clearCells() {
        activeElement?.onMouseLeave()
        activeElement = null
        activeDragElement = null
        cells.values.forEach {
            it.onClose()
            it.parent = null
        }
        cells.clear()
    }

    private fun cellWidth(): Float = if (state.columns == 0) 0.0f else size.x / state.columns

    private fun cellOffset(index: Int): Vec2f {
        return Vec2f(
            (index % state.columns) * cellWidth(),
            (index / state.columns) * state.cellHeight - scroll.offset,
        )
    }

    private companion object {
        const val MAX_ITEMS = 1_000_000
        const val PIXELS_PER_SCROLL = 12.0f
    }
}
