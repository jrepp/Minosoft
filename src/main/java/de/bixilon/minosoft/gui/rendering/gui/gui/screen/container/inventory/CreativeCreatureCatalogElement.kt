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
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.TextComponent
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.input.cycle.CycleSelectorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.gui.dragged.Dragged
import de.bixilon.minosoft.gui.rendering.gui.gui.elements.input.TextInputElement
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.animal.SheepRenderer
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.system.window.CursorShapes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import kotlin.math.PI

internal data class CreativeCreatureEntry(
    val type: EntityType,
    val label: String,
    val searchableText: String,
    val previewModel: ResourceLocation? = null,
    val spawnVariant: CreatureSpawnVariant = CreatureSpawnVariant.DEFAULT,
)

internal class CreaturePreviewRotationState(
    yaw: Float = 0.0f,
) {
    var yaw: Float = normalize(yaw)
        private set
    var showingDefault: Boolean = false
        private set

    fun tick() {
        if (showingDefault) return
        yaw = normalize(yaw + IDLE_RADIANS_PER_TICK)
    }

    fun showDefault() {
        yaw = 0.0f
        showingDefault = true
    }

    fun beginDrag() {
        showingDefault = false
    }

    fun drag(horizontalPixels: Float) {
        if (!horizontalPixels.isFinite()) return
        showingDefault = false
        yaw = normalize(yaw + horizontalPixels * DRAG_RADIANS_PER_PIXEL)
    }

    companion object {
        internal val FULL_ROTATION = (PI * 2.0).toFloat()
        internal val IDLE_RADIANS_PER_TICK = FULL_ROTATION / (20.0f * 20.0f)
        internal const val DRAG_RADIANS_PER_PIXEL = 0.02f

        private fun normalize(value: Float): Float {
            val wrapped = value % FULL_ROTATION
            return if (wrapped < 0.0f) wrapped + FULL_ROTATION else wrapped
        }
    }
}

class CreativeCreatureCatalogElement(
    guiRenderer: GUIRenderer,
    private val catalogSize: Vec2f = SIZE,
) : Element(guiRenderer), AbstractLayout<Element> {
    private val pageSize = ((catalogSize.y - LIST_OFFSET.y - FOOTER_HEIGHT) / ROW_HEIGHT)
        .toInt()
        .coerceAtLeast(PAGE_SIZE)
    private val listWidth = (catalogSize.x * LIST_WIDTH_SHARE).coerceIn(LIST_WIDTH, MAX_LIST_WIDTH)
    private val searchSize = Vec2f(catalogSize.x - SEARCH_OFFSET.x * 2.0f, 10.0f)
    private val previewOffset = Vec2f(listWidth + 7.0f, LIST_OFFSET.y)
    private val previewSize = Vec2f(
        catalogSize.x - previewOffset.x - 4.0f,
        catalogSize.y - PREVIEW_FOOTER_HEIGHT,
    )
    private val previewContentSize = previewSize - PREVIEW_PADDING * 2.0f
    private val selectionLabelOffset = Vec2f(previewOffset.x + 2.0f, previewOffset.y + previewSize.y + 4.0f)
    private val animationOffset = Vec2f(previewOffset.x + 2.0f, selectionLabelOffset.y + 13.0f)
    private val defaultRotationOffset = Vec2f(previewOffset.x + 2.0f, animationOffset.y + 22.0f)
    private val spawnOffset = Vec2f(previewOffset.x + 2.0f, defaultRotationOffset.y + 20.0f)
    private val spawnSize = Vec2f(previewSize.x - 4.0f, 18.0f)
    private val statusOffset = Vec2f(previewOffset.x + 2.0f, spawnOffset.y + spawnSize.y + 3.0f)
    private val pageLabelOffset = Vec2f(6.0f, catalogSize.y - FOOTER_HEIGHT + 1.0f)
    private val skeletal = context.models.skeletal
    private val creatures = guiRenderer.session.registries.entityType
        .distinctBy(EntityType::identifier)
        .flatMap(::entries)
        .sortedBy { it.type.identifier.toString() }
    private val pager = CreativeCatalogPager(creatures, pageSize, CreativeCreatureEntry::searchableText)
    private val background = ColorElement(guiRenderer, catalogSize, BACKGROUND)
    private val innerBackground = ColorElement(guiRenderer, catalogSize - 2, INNER_BACKGROUND)
    private val previewBackground = ColorElement(guiRenderer, previewSize, PREVIEW_BACKGROUND)
    private val search = CreatureSearchElement(guiRenderer, searchSize, ::onSearchChanged).apply {
        parent = this@CreativeCreatureCatalogElement
    }
    private val searchPlaceholder = TextElement(
        guiRenderer,
        TextComponent("Search creatures...").color(SEARCH_PLACEHOLDER),
        background = null,
        parent = this,
    )
    private val pageLabel = TextElement(guiRenderer, "", background = null, parent = this)
    private val selectionLabel = TextElement(guiRenderer, "", background = null, parent = this)
    private val statusLabel = TextElement(guiRenderer, "", background = null, parent = this)
    private val entries = Array(pageSize) { CreatureEntryElement(guiRenderer, this) }
    private var animationSelector: CycleSelectorElement<String>? = null
    private val preview = CreaturePreviewElement(
        guiRenderer,
        previewSize,
        PREVIEW_PADDING,
        previewContentSize,
        ::updateAnimationOptions,
    )
        .apply { parent = this@CreativeCreatureCatalogElement }
    private val defaultRotation = PreviewActionElement(
        guiRenderer,
        spawnSize,
        "Show default rotation",
        preview::showDefaultRotation,
    ).apply { parent = this@CreativeCreatureCatalogElement }
    private val spawn = SpawnCreatureElement(guiRenderer, spawnSize, ::spawnSelected).apply {
        parent = this@CreativeCreatureCatalogElement
    }
    private var selected: CreativeCreatureEntry? = creatures.firstOrNull()
    private var searchFocused = false
    val isSearchFocused: Boolean get() = searchFocused

    override var activeElement: Element? = null
    override var activeDragElement: Element? = null

    init {
        _size = catalogSize
        updatePage()
        updateSelection()
    }

    private fun entries(type: EntityType): List<CreativeCreatureEntry> {
        val model = skeletal.previewModel(type.identifier) ?: return emptyList()
        if (model.preview.isEmpty) return emptyList()
        val translated = type.translationKey
            ?.let(guiRenderer.session.language::forceTranslate)
            ?.message
            ?.takeIf(String::isNotBlank)
        val label = translated ?: type.identifier.path
            .split('_')
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        val searchable = "${type.identifier} ${type.identifier.path.replace('_', ' ')} $label"
        if (type.identifier != SHEEP) {
            return listOf(CreativeCreatureEntry(type, label, searchable))
        }
        val woolly = skeletal[SheepRenderer.SHEEP_WOOLLY_PREVIEW]
            ?.takeUnless { it.preview.isEmpty }
            ?.let {
                CreativeCreatureEntry(
                    type,
                    "$label (Woolly)",
                    "$searchable woolly unsheared",
                    SheepRenderer.SHEEP_WOOLLY_PREVIEW,
                    CreatureSpawnVariant.SHEEP_WOOLLY,
                )
            }
        val sheared = CreativeCreatureEntry(
            type,
            "$label (Sheared)",
            "$searchable sheared",
            spawnVariant = CreatureSpawnVariant.SHEEP_SHEARED,
        )
        return listOfNotNull(woolly, sheared)
    }

    private fun updatePage() {
        val visible = pager.visible
        for (index in entries.indices) entries[index].entry = visible.getOrNull(index)
        val label = if (pager.resultCount == 1) "creature" else "creatures"
        pageLabel.text = "${pager.resultCount} $label  ${pager.page + 1}/${pager.pageCount}"
        cacheUpToDate = false
    }

    private fun updateSelection() {
        val selected = selected
        preview.entity = selected?.type?.identifier
        preview.modelOverride = selected?.previewModel
        selectionLabel.text = selected?.label?.ellipsize(SELECTION_LABEL_LENGTH) ?: "No creature selected"
        val permitted = selected != null && CreatureSpawnCommand.available(guiRenderer.session)
        spawn.disabled = !permitted
        statusLabel.text = when {
            selected == null -> "No matching model"
            permitted -> "Spawns 3 blocks ahead"
            else -> "Server permission required"
        }
        cacheUpToDate = false
    }

    private fun updateAnimationOptions(animations: List<String>) {
        animationSelector?.onClose()
        val options = listOf(NEUTRAL_ANIMATION) + animations.sorted()
        animationSelector = CycleSelectorElement(
            guiRenderer,
            options = options,
            initialValue = NEUTRAL_ANIMATION,
            label = { "Animation: ${animationLabel(it)}" },
        ) { selected ->
            preview.selectAnimation(selected.takeUnless { it == NEUTRAL_ANIMATION })
        }.apply {
            size = Vec2f(spawnSize.x, 20.0f)
            parent = this@CreativeCreatureCatalogElement
            disabled = animations.isEmpty()
        }
        cacheUpToDate = false
    }

    private fun select(entry: CreativeCreatureEntry) {
        if (selected == entry) return
        selected = entry
        entries.forEach { it.selected = it.entry == entry }
        updateSelection()
    }

    private fun spawnSelected() {
        val selected = selected ?: return
        try {
            CreatureSpawnCommand.send(guiRenderer.session, selected.type.identifier, selected.spawnVariant)
            statusLabel.text = "Requested ${selected.label}"
        } catch (error: Throwable) {
            statusLabel.text = error.message?.ellipsize(28) ?: "Spawn request failed"
        }
        cacheUpToDate = false
    }

    private fun onSearchChanged() {
        if (!pager.search(search.value)) return
        clearHoveredEntry()
        updatePage()
        selected = pager.visible.firstOrNull()
        entries.forEach { it.selected = it.entry == selected }
        updateSelection()
    }

    private fun clearHoveredEntry() {
        val active = activeElement
        if (active !is CreatureEntryElement) return
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

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        background.render(offset, consumer, options)
        innerBackground.render(offset + 1, consumer, options)
        search.render(offset + SEARCH_OFFSET, consumer, options)
        if (search.value.isEmpty() && !searchFocused) {
            searchPlaceholder.render(offset + SEARCH_OFFSET, consumer, options)
        }
        for ((index, entry) in entries.withIndex()) {
            entry.render(offset + entryOffset(index), consumer, options)
        }
        previewBackground.render(offset + previewOffset, consumer, options)
        preview.render(offset + previewOffset, consumer, options)
        selectionLabel.render(offset + selectionLabelOffset, consumer, options)
        animationSelector?.render(offset + animationOffset, consumer, options)
        defaultRotation.render(offset + defaultRotationOffset, consumer, options)
        spawn.render(offset + spawnOffset, consumer, options)
        statusLabel.render(offset + statusOffset, consumer, options)
        pageLabel.render(offset + pageLabelOffset, consumer, options)
    }

    override fun forceSilentApply() {
        search.silentApply()
        entries.forEach(Element::silentApply)
        preview.silentApply()
        selectionLabel.silentApply()
        animationSelector?.silentApply()
        defaultRotation.silentApply()
        spawn.silentApply()
        statusLabel.silentApply()
        pageLabel.silentApply()
        cacheUpToDate = false
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        val searchPosition = position - SEARCH_OFFSET
        if (searchPosition.inside(searchSize)) return search to searchPosition

        val grid = position - LIST_OFFSET
        if (grid.x >= 0.0f && grid.x < listWidth && grid.y >= 0.0f) {
            val row = (grid.y / ROW_HEIGHT).toInt()
            if (row in entries.indices) {
                val entry = entries[row]
                if (entry.entry != null) return entry to Vec2f(grid.x, grid.y - row * ROW_HEIGHT)
            }
        }

        val previewPosition = position - previewOffset
        if (previewPosition.inside(previewSize)) return preview to previewPosition

        val animationPosition = position - animationOffset
        animationSelector?.let {
            if (animationPosition.inside(it.size)) return it to animationPosition
        }

        val defaultRotationPosition = position - defaultRotationOffset
        if (defaultRotationPosition.inside(defaultRotation.size)) {
            return defaultRotation to defaultRotationPosition
        }

        val spawnPosition = position - spawnOffset
        if (spawnPosition.inside(spawnSize)) return spawn to spawnPosition
        return null
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (action == MouseActions.PRESS) {
            val focusSearch = getAt(position)?.first === search && CreativeCatalogInput.isPrimary(button)
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
        preview.tick()
        val permitted = selected != null && CreatureSpawnCommand.available(guiRenderer.session)
        if (spawn.disabled == permitted) {
            spawn.disabled = !permitted
            updateSelection()
        }
    }

    override fun onClose() {
        activeElement?.onMouseLeave()
        activeElement = null
        clearSearchFocus()
        search.onClose()
        entries.forEach(Element::onClose)
        preview.onClose()
        animationSelector?.onClose()
        animationSelector = null
        defaultRotation.onClose()
        spawn.onClose()
        super.onClose()
    }

    private fun entryOffset(index: Int): Vec2f = LIST_OFFSET + Vec2f(0.0f, index * ROW_HEIGHT)

    private inner class CreatureEntryElement(
        guiRenderer: GUIRenderer,
        private val catalog: CreativeCreatureCatalogElement,
    ) : Element(guiRenderer) {
        private val normal = ColorElement(guiRenderer, Vec2f(listWidth, ROW_HEIGHT - 1.0f), ENTRY_BACKGROUND)
        private val highlighted = ColorElement(guiRenderer, Vec2f(listWidth, ROW_HEIGHT - 1.0f), ENTRY_HIGHLIGHTED)
        private val text = TextElement(guiRenderer, "", background = null, parent = this)
        private var hovered = false
        var selected = false
            set(value) {
                if (field == value) return
                field = value
                cacheUpToDate = false
            }
        var entry: CreativeCreatureEntry? = null
            set(value) {
                field = value
                text.text = value?.label?.ellipsize(ENTRY_LABEL_LENGTH) ?: ""
                selected = value != null && value == catalog.selected
                cacheUpToDate = false
            }

        init {
            _parent = catalog
            _size = Vec2f(listWidth, ROW_HEIGHT)
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
            (if (selected || hovered) highlighted else normal).render(offset, consumer, options)
            text.render(offset + Vec2f(3.0f, 4.0f), consumer, options)
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
            val entry = entry ?: return false
            if (action != MouseActions.PRESS || !CreativeCatalogInput.isPrimary(button)) return false
            catalog.select(entry)
            return true
        }
    }

    private class CreaturePreviewElement(
        guiRenderer: GUIRenderer,
        private val previewSize: Vec2f,
        private val previewPadding: Vec2f,
        private val previewContentSize: Vec2f,
        private val onAnimationsChanged: (List<String>) -> Unit,
    ) : Element(guiRenderer) {
        private val skeletal = context.models.skeletal
        private val rotation = CreaturePreviewRotationState()
        private val drag = PreviewDrag()
        private var model: BakedSkeletalModel? = null
        private var player: de.bixilon.minosoft.gui.rendering.skeletal.preview.SkeletalPreviewPlayer? = null
        var modelOverride: ResourceLocation? = null
            set(value) {
                if (field == value) return
                field = value
                refresh()
                cacheUpToDate = false
            }
        var entity: ResourceLocation? = null
            set(value) {
                if (field == value) return
                field = value
                refresh()
                // Shared geometry routes (for example Naturalist birds) still
                // select textures by the exact entity identifier.
                cacheUpToDate = false
            }

        init {
            _size = previewSize
        }

        private fun resolve(): BakedSkeletalModel? =
            modelOverride?.let(skeletal::get) ?: entity?.let(skeletal::previewModel)

        private fun refresh() {
            val resolved = resolve()
            if (model === resolved) return
            model = resolved
            player = resolved?.preview?.createPlayer()
            onAnimationsChanged(resolved?.preview?.animations?.keys?.toList().orEmpty())
            cacheUpToDate = false
        }

        fun selectAnimation(animation: String?) {
            player?.select(animation)
            cacheUpToDate = false
        }

        fun showDefaultRotation() {
            rotation.showDefault()
            cacheUpToDate = false
        }

        override fun tick() {
            refresh()
            player?.tick()
            if (drag.visible) return
            rotation.tick()
            cacheUpToDate = false
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
            val model = model ?: return
            val entity = entity ?: return
            model.preview.render(
                offset + previewPadding,
                previewContentSize,
                consumer,
                options,
                skeletal.previewTextures(entity, model),
                rotation.yaw,
                transforms = player?.matrices ?: emptyMap(),
            )
        }

        override fun forceSilentApply() {
            cacheUpToDate = false
        }

        override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
            context.window.cursorShape = CursorShapes.HORIZONTAL_RESIZE
            return true
        }

        override fun onMouseLeave(): Boolean {
            context.window.resetCursor()
            return true
        }

        override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
            if (!CreativeCatalogInput.isPrimary(button) || action != MouseActions.PRESS) return false
            rotation.beginDrag()
            drag.previousX = guiRenderer.currentMousePosition.x
            drag.show()
            return true
        }

        override fun onClose() {
            drag.close()
            super.onClose()
        }

        private inner class PreviewDrag : Dragged(guiRenderer) {
            var previousX = 0.0f

            init {
                size = Vec2f.EMPTY
            }

            override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) = Unit

            override fun forceSilentApply() {
                cacheUpToDate = false
            }

            override fun onDragMove(position: Vec2f, target: Element?) {
                val delta = position.x - previousX
                previousX = position.x
                rotation.drag(delta)
                this@CreaturePreviewElement.cacheUpToDate = false
            }

            override fun onDragMouseAction(
                position: Vec2f,
                button: MouseButtons,
                action: MouseActions,
                count: Int,
                target: Element?,
            ) {
                if (!CreativeCatalogInput.isPrimary(button) || action != MouseActions.RELEASE) return
                onDragMove(position, target)
                close()
            }
        }
    }

    private class PreviewActionElement(
        guiRenderer: GUIRenderer,
        private val buttonSize: Vec2f,
        label: String,
        private val action: () -> Unit,
    ) : Element(guiRenderer) {
        private val normal = ColorElement(guiRenderer, buttonSize, ACTION_BACKGROUND)
        private val highlighted = ColorElement(guiRenderer, buttonSize, ACTION_HIGHLIGHTED)
        private val text = TextElement(
            guiRenderer,
            if (buttonSize.x >= FULL_ACTION_LABEL_WIDTH) label else "Default view",
            background = null,
            parent = this,
        )
        private var hovered = false

        init {
            _size = buttonSize
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
            (if (hovered) highlighted else normal).render(offset, consumer, options)
            text.render(offset + Vec2f((buttonSize.x - text.size.x) / 2.0f, 5.0f), consumer, options)
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
            this.action()
            return true
        }
    }

    private class SpawnCreatureElement(
        guiRenderer: GUIRenderer,
        private val buttonSize: Vec2f,
        private val spawn: () -> Unit,
    ) : Element(guiRenderer) {
        private val normal = ColorElement(guiRenderer, buttonSize, SPAWN_BACKGROUND)
        private val highlighted = ColorElement(guiRenderer, buttonSize, SPAWN_HIGHLIGHTED)
        private val disabledBackground = ColorElement(guiRenderer, buttonSize, SPAWN_DISABLED)
        private val text = TextElement(guiRenderer, "Spawn", background = null, parent = this)
        private var hovered = false
        var disabled = false
            set(value) {
                if (field == value) return
                field = value
                cacheUpToDate = false
            }

        init {
            _size = buttonSize
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
            when {
                disabled -> disabledBackground
                hovered -> highlighted
                else -> normal
            }.render(offset, consumer, options)
            text.render(offset + Vec2f((buttonSize.x - text.size.x) / 2.0f, 5.0f), consumer, options)
        }

        override fun forceSilentApply() {
            text.silentApply()
            cacheUpToDate = false
        }

        override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
            if (disabled) return true
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
            if (disabled || action != MouseActions.PRESS || !CreativeCatalogInput.isPrimary(button)) return false
            spawn()
            return true
        }
    }

    private class CreatureSearchElement(
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
        const val PAGE_SIZE = 7
        const val ROW_HEIGHT = 17.0f
        const val LIST_WIDTH = 84.0f
        val SIZE = CreativeInventoryLayout.COMPACT_BODY_SIZE

        private val SEARCH_OFFSET = Vec2f(6.0f, 4.0f)
        private val LIST_OFFSET = Vec2f(4.0f, 18.0f)
        private val PREVIEW_PADDING = Vec2f(3.0f)
        private const val LIST_WIDTH_SHARE = 0.42f
        private const val MAX_LIST_WIDTH = 140.0f
        private const val FOOTER_HEIGHT = 17.0f
        private const val PREVIEW_FOOTER_HEIGHT = 108.0f
        private const val SEARCH_MAX_LENGTH = 64
        private const val ENTRY_LABEL_LENGTH = 14
        private const val SELECTION_LABEL_LENGTH = 12
        private const val NEUTRAL_ANIMATION = "<neutral>"
        private val SHEEP = ResourceLocation("minecraft", "sheep")

        private val BACKGROUND = RGBAColor(0xC6, 0xC6, 0xC6, 0xFF)
        private val INNER_BACKGROUND = RGBAColor(0x20, 0x20, 0x20, 0xF0)
        private val PREVIEW_BACKGROUND = RGBAColor(0x10, 0x10, 0x10, 0xFF)
        private val ENTRY_BACKGROUND = RGBAColor(0x3A, 0x3A, 0x3A, 0xFF)
        private val ENTRY_HIGHLIGHTED = RGBAColor(0x72, 0x72, 0x72, 0xFF)
        private val SPAWN_BACKGROUND = RGBAColor(0x36, 0x72, 0x3A, 0xFF)
        private val SPAWN_HIGHLIGHTED = RGBAColor(0x4C, 0x98, 0x52, 0xFF)
        private val SPAWN_DISABLED = RGBAColor(0x4A, 0x4A, 0x4A, 0xFF)
        private val ACTION_BACKGROUND = RGBAColor(0x48, 0x48, 0x64, 0xFF)
        private val ACTION_HIGHLIGHTED = RGBAColor(0x68, 0x68, 0x8C, 0xFF)
        private const val FULL_ACTION_LABEL_WIDTH = 118.0f
        private val SEARCH_PLACEHOLDER = RGBAColor(0xA0, 0xA0, 0xA0, 0xFF)

        private fun Vec2f.inside(size: Vec2f): Boolean =
            x >= 0.0f && y >= 0.0f && x < size.x && y < size.y

        private fun String.ellipsize(maximum: Int): String {
            if (length <= maximum) return this
            if (maximum <= 1) return take(maximum)
            return take(maximum - 1) + "…"
        }

        private fun animationLabel(name: String): String {
            if (name == NEUTRAL_ANIMATION) return "Neutral pose"
            return name.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}
