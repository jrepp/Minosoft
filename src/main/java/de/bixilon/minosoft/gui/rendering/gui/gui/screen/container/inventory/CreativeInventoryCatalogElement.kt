/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.container.types.PlayerInventory
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.CursorShapes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes

data class CreativeInventoryExternalCatalogTab(
    val label: String,
    val element: Element,
)

private data class CreativeCatalogPage(
    val label: String,
    val element: Element,
    val owned: Boolean,
    val isSearchFocused: () -> Boolean = { false },
    val clearSearchFocus: () -> Unit = {},
)

class CreativeInventoryCatalogElement(
    guiRenderer: GUIRenderer,
    container: PlayerInventory,
    items: List<Item>,
    externalTabs: List<CreativeInventoryExternalCatalogTab> = emptyList(),
    private val bodySize: Vec2f = CreativeInventoryLayout.COMPACT_BODY_SIZE,
) : Element(guiRenderer), AbstractLayout<Element> {
    private val itemCatalog = CreativeItemCatalogElement(guiRenderer, container, items, bodySize)
    private val creatureCatalog = CreativeCreatureCatalogElement(guiRenderer, bodySize)
    private val bodyBackground = ColorElement(guiRenderer, bodySize, BODY_BACKGROUND)
    private val pages = buildList {
        add(
            CreativeCatalogPage(
                "Items",
                itemCatalog,
                owned = true,
                isSearchFocused = { itemCatalog.isSearchFocused },
                clearSearchFocus = itemCatalog::clearSearchFocus,
            ),
        )
        add(
            CreativeCatalogPage(
                "Creatures",
                creatureCatalog,
                owned = true,
                isSearchFocused = { creatureCatalog.isSearchFocused },
                clearSearchFocus = creatureCatalog::clearSearchFocus,
            ),
        )
        externalTabs.forEach { add(CreativeCatalogPage(it.label, it.element, owned = false)) }
    }.onEach { it.element.parent = this@CreativeInventoryCatalogElement }
    private val tabWidth = (bodySize.x - TAB_GAP * (pages.size - 1)) / pages.size
    private val tabs = pages.mapIndexed { index, page ->
        CatalogTabElement(guiRenderer, page.label, index, tabWidth, ::select).apply {
            parent = this@CreativeInventoryCatalogElement
        }
    }
    private var selected = 0

    val isSearchFocused: Boolean
        get() = pages[selected].isSearchFocused()

    override var activeElement: Element? = null
    override var activeDragElement: Element? = null

    init {
        _size = Vec2f(bodySize.x, bodySize.y + TAB_HEIGHT)
        updateTabs()
    }

    private fun select(index: Int) {
        if (selected == index) return
        clearActive()
        clearSearchFocus()
        selected = index
        updateTabs()
        cacheUpToDate = false
    }

    private fun updateTabs() {
        tabs.forEachIndexed { index, tab -> tab.selected = selected == index }
    }

    private fun activeCatalog(): Element = pages[selected].element

    private fun clearActive() {
        activeElement?.onMouseLeave()
        activeElement = null
    }

    fun clearSearchFocus() {
        pages.forEach { it.clearSearchFocus() }
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        tabs.forEachIndexed { index, tab ->
            tab.render(offset + Vec2f(index * (tabWidth + TAB_GAP), 0.0f), consumer, options)
        }
        bodyBackground.render(offset + BODY_OFFSET, consumer, options)
        activeCatalog().render(offset + BODY_OFFSET, consumer, options)
    }

    override fun forceSilentApply() {
        tabs.forEach(Element::silentApply)
        pages.forEach { it.element.silentApply() }
        cacheUpToDate = false
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        if (position.y < TAB_HEIGHT) {
            for ((index, tab) in tabs.withIndex()) {
                val x = position.x - index * (tabWidth + TAB_GAP)
                if (x >= 0.0f && x < tabWidth) return tab to Vec2f(x, position.y)
            }
            return null
        }
        val body = position - BODY_OFFSET
        if (body.x < 0.0f || body.y < 0.0f || body.x >= bodySize.x || body.y >= bodySize.y) return null
        val catalog = activeCatalog()
        if (body.x >= catalog.size.x || body.y >= catalog.size.y) return null
        return catalog to body
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (key == KeyCodes.KEY_TAB && type == KeyChangeTypes.PRESS) {
            select((selected + 1) % pages.size)
            return true
        }
        return activeCatalog().onKey(key, type)
    }

    override fun onCharPress(char: Int): Boolean = activeCatalog().onCharPress(char)

    override fun tick() = activeCatalog().tick()

    override fun onClose() {
        clearActive()
        pages.filter(CreativeCatalogPage::owned).forEach { it.element.onClose() }
        tabs.forEach(Element::onClose)
        super.onClose()
    }

    private class CatalogTabElement(
        guiRenderer: GUIRenderer,
        label: String,
        private val index: Int,
        private val width: Float,
        private val select: (Int) -> Unit,
    ) : Element(guiRenderer) {
        private val text = TextElement(guiRenderer, label, background = null, parent = this)
        private val background = ColorElement(guiRenderer, Vec2f(width, TAB_HEIGHT), TAB_BACKGROUND)
        private val selectedBackground = ColorElement(guiRenderer, Vec2f(width, TAB_HEIGHT), TAB_SELECTED)
        private var hovered = false
        var selected = false
            set(value) {
                if (field == value) return
                field = value
                cacheUpToDate = false
            }

        init {
            _size = Vec2f(width, TAB_HEIGHT)
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
            (if (selected || hovered) selectedBackground else background).render(offset, consumer, options)
            text.render(offset + Vec2f((width - text.size.x) / 2.0f, 4.0f), consumer, options)
        }

        override fun forceSilentApply() {
            text.silentApply()
            cacheUpToDate = false
        }

        override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
            hovered = true
            context.window.cursorShape = CursorShapes.HAND
            cacheUpToDate = false
            return true
        }

        override fun onMouseLeave(): Boolean {
            hovered = false
            context.window.resetCursor()
            cacheUpToDate = false
            return true
        }

        override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
            if (action != MouseActions.PRESS || !CreativeCatalogInput.isPrimary(button)) return false
            select(index)
            return true
        }
    }

    companion object {
        const val TAB_HEIGHT = 18.0f
        const val TAB_GAP = 2.0f
        val BODY_OFFSET = Vec2f(0.0f, TAB_HEIGHT)
        private val TAB_BACKGROUND = RGBAColor(0x45, 0x45, 0x45, 0xF0)
        private val TAB_SELECTED = RGBAColor(0x78, 0x78, 0x78, 0xFF)
        private val BODY_BACKGROUND = RGBAColor(0x20, 0x20, 0x20, 0xF0)
    }
}
