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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.container.types.PlayerInventory
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.registries.item.stack.StackableItem
import de.bixilon.minosoft.data.text.TextComponent
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.items.RawItemElement
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.elements.input.TextInputElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.gui.popper.item.ItemInfoPopper
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.CursorShapes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes

class CreativeItemCatalogElement(
    guiRenderer: GUIRenderer,
    private val container: PlayerInventory,
    items: List<Item>,
    private val catalogSize: Vec2f = SIZE,
) : Element(guiRenderer), AbstractLayout<Element> {
    private val columns = ((catalogSize.x - GRID_OFFSET.x * 2.0f) / CELL_SIZE).toInt().coerceAtLeast(COLUMNS)
    private val rows = ((catalogSize.y - GRID_OFFSET.y - FOOTER_HEIGHT) / CELL_SIZE).toInt().coerceAtLeast(ROWS)
    private val pageSize = columns * rows
    private val searchSize = Vec2f(catalogSize.x - SEARCH_OFFSET.x * 2.0f, 10.0f)
    private val pageLabelOffset = Vec2f(6.0f, catalogSize.y - FOOTER_HEIGHT + 1.0f)
    private val pager = CreativeCatalogPager(items, pageSize) { item ->
        val identifier = item.identifier.toString()
        val path = item.identifier.path.replace('_', ' ')
        val translated = guiRenderer.session.language.forceTranslate(item.translationKey).message
        "$identifier $path $translated"
    }
    private val background = ColorElement(guiRenderer, catalogSize, BACKGROUND)
    private val innerBackground = ColorElement(guiRenderer, catalogSize - 2, INNER_BACKGROUND)
    private val slotBackground = ColorElement(guiRenderer, Vec2f(CELL_SIZE - 1), SLOT_BACKGROUND)
    private val search: CatalogSearchElement = CatalogSearchElement(guiRenderer, searchSize, ::onSearchChanged)
        .apply { parent = this@CreativeItemCatalogElement }
    private val searchPlaceholder = TextElement(
        guiRenderer,
        TextComponent("Search creative items...").color(SEARCH_PLACEHOLDER),
        background = null,
        parent = this,
    )
    private val pageLabel = TextElement(guiRenderer, "", background = null, parent = this)
    private val entries = Array(pageSize) { CatalogItemElement(guiRenderer, this) }
    private var searchFocused = false
    val isSearchFocused: Boolean get() = searchFocused

    override var activeElement: Element? = null
    override var activeDragElement: Element? = null

    init {
        _size = catalogSize
        updatePage()
    }

    private fun updatePage() {
        val visible = pager.visible
        for (index in entries.indices) {
            entries[index].stack = visible.getOrNull(index)?.let(::creativeStack)
        }
        val itemLabel = if (pager.resultCount == 1) "item" else "items"
        pageLabel.text = "${pager.resultCount} $itemLabel  Page ${pager.page + 1}/${pager.pageCount}"
        cacheUpToDate = false
    }

    private fun onSearchChanged() {
        if (!pager.search(search.value)) return
        clearHoveredEntry()
        updatePage()
    }

    private fun clearHoveredEntry() {
        val active = activeElement
        if (active !is CatalogItemElement) return
        active.onMouseLeave()
        activeElement = null
    }

    fun clearSearchFocus() {
        setSearchFocused(false)
    }

    private fun setSearchFocused(focused: Boolean) {
        if (focused == searchFocused) return
        searchFocused = focused
        if (focused) search.showCursor() else search.hideCursor()
        cacheUpToDate = false
    }

    private fun creativeStack(item: Item, single: Boolean = false): ItemStack {
        val count = if (single) 1 else (item as? StackableItem)?.maxStackSize ?: 1
        return ItemStack(item, count)
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        background.render(offset, consumer, options)
        innerBackground.render(offset + 1, consumer, options)
        search.render(offset + SEARCH_OFFSET, consumer, options)
        if (search.value.isEmpty() && !searchFocused) {
            searchPlaceholder.render(offset + SEARCH_OFFSET, consumer, options)
        }

        for ((index, entry) in entries.withIndex()) {
            val entryOffset = entryOffset(index)
            slotBackground.render(offset + entryOffset, consumer, options)
            entry.render(offset + entryOffset, consumer, options)
        }
        pageLabel.render(offset + pageLabelOffset, consumer, options)
    }

    override fun forceSilentApply() {
        cacheUpToDate = false
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        val searchPosition = position - SEARCH_OFFSET
        if (searchPosition.x >= 0.0f && searchPosition.y >= 0.0f && searchPosition.x < searchSize.x && searchPosition.y < searchSize.y) {
            return Pair(search, searchPosition)
        }

        val grid = position - GRID_OFFSET
        if (grid.x < 0.0f || grid.y < 0.0f) return null

        val column = (grid.x / CELL_SIZE).toInt()
        val row = (grid.y / CELL_SIZE).toInt()
        if (column !in 0 until columns || row !in 0 until rows) return null

        val index = row * columns + column
        val entry = entries[index]
        if (entry.stack == null) return null
        return Pair(entry, Vec2f(grid.x - column * CELL_SIZE, grid.y - row * CELL_SIZE))
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (action == MouseActions.PRESS) {
            val target = getAt(position)?.first
            val focusSearch = target === search && CreativeCatalogInput.isPrimary(button)
            setSearchFocused(focusSearch)
        }
        return super<AbstractLayout>.onMouseAction(position, button, action, count)
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (searchFocused) return search.onKey(key, type)
        return super<AbstractLayout>.onKey(key, type)
    }

    override fun onCharPress(char: Int): Boolean {
        setSearchFocused(true)
        return search.onCharPress(char)
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        if (!pager.scroll(scrollOffset.y)) return true

        clearHoveredEntry()
        updatePage()
        return true
    }

    override fun tick() {
        if (searchFocused) search.tick()
    }

    override fun onClose() {
        activeElement?.onMouseLeave()
        activeElement = null
        clearSearchFocus()
        search.onClose()
        super.onClose()
    }

    private fun entryOffset(index: Int): Vec2f {
        return GRID_OFFSET + Vec2f((index % columns) * CELL_SIZE, (index / columns) * CELL_SIZE)
    }

    class CatalogItemElement(
        guiRenderer: GUIRenderer,
        private val catalog: CreativeItemCatalogElement,
    ) : Element(guiRenderer) {
        private val raw = RawItemElement(guiRenderer, RawItemElement.DEFAULT_SIZE, null, this)
        private val hoveredBackground = ColorElement(guiRenderer, Vec2f(CELL_SIZE - 1), HOVERED_BACKGROUND)
        private var popper: ItemInfoPopper? = null
        private var hovered = false

        var stack: ItemStack?
            get() = raw.stack
            set(value) {
                raw.stack = value
            }

        init {
            _parent = catalog
            _size = Vec2f(CELL_SIZE)
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
            if (hovered) {
                hoveredBackground.render(offset, consumer, options)
            }
            raw.render(offset + 1, consumer, options)
        }

        override fun forceSilentApply() {
            raw.silentApply()
            cacheUpToDate = false
        }

        override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
            val stack = stack ?: return false
            context.window.cursorShape = CursorShapes.HAND
            popper = ItemInfoPopper(guiRenderer, absolute, stack).apply { show() }
            hovered = true
            cacheUpToDate = false
            return true
        }

        override fun onMouseLeave(): Boolean {
            context.window.resetCursor()
            popper?.hide()
            popper = null
            hovered = false
            cacheUpToDate = false
            return true
        }

        override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
            if (action != MouseActions.PRESS) return true
            val item = stack?.item ?: return false

            catalog.container.floating = when (button) {
                MouseButtons.LEFT, MouseButtons.MIDDLE -> catalog.creativeStack(item)
                MouseButtons.RIGHT -> catalog.creativeStack(item, single = true)
                else -> return false
            }
            return true
        }
    }

    private class CatalogSearchElement(
        guiRenderer: GUIRenderer,
        private val searchSize: Vec2f,
        onChange: () -> Unit,
    ) : TextInputElement(
        guiRenderer = guiRenderer,
        maxLength = SEARCH_MAX_LENGTH,
        onChangeCallback = onChange,
        cutAtSize = true,
    ) {
        override var size: Vec2f
            get() = searchSize
            set(value) = Unit

        init {
            prefMaxSize = searchSize
            hideCursor()
            forceSilentApply()
        }
    }

    companion object {
        const val COLUMNS = 9
        const val ROWS = 7
        const val PAGE_SIZE = COLUMNS * ROWS
        const val CELL_SIZE = 18.0f
        val SIZE = CreativeInventoryLayout.COMPACT_BODY_SIZE

        private val GRID_OFFSET = Vec2f(4, 17)
        private val SEARCH_OFFSET = Vec2f(6, 4)
        private const val FOOTER_HEIGHT = 18.0f
        private const val SEARCH_MAX_LENGTH = 64
        private val BACKGROUND = RGBAColor(0xC6, 0xC6, 0xC6, 0xFF)
        private val INNER_BACKGROUND = RGBAColor(0x20, 0x20, 0x20, 0xF0)
        private val SLOT_BACKGROUND = RGBAColor(0x48, 0x48, 0x48, 0xFF)
        private val HOVERED_BACKGROUND = RGBAColor(0xFF, 0xFF, 0xFF, 0x80)
        private val SEARCH_PLACEHOLDER = RGBAColor(0xA0, 0xA0, 0xA0, 0xFF)
    }
}
