/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.cycle.CycleSelectorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.items.RawItemElement
import de.bixilon.minosoft.gui.rendering.gui.elements.grid.ClippedVirtualGridElement
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.scroll.ClippedScrollPanelElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.gui.elements.input.TextInputElement
import de.bixilon.minosoft.gui.rendering.gui.gui.popper.item.ItemInfoPopper
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.CursorShapes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import de.bixilon.minosoft.recipes.Ingredient
import de.bixilon.minosoft.recipes.Recipe
import de.bixilon.minosoft.recipes.StoneCuttingRecipe
import de.bixilon.minosoft.recipes.crafting.ShapedRecipe
import de.bixilon.minosoft.recipes.crafting.ShapelessRecipe
import de.bixilon.minosoft.recipes.heat.HeatRecipe
import de.bixilon.minosoft.recipes.smithing.AbstractSmithingRecipe
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicBoolean

object JeiCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:jei-17.3.1.5-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(
        FabricHostCapability.CONTAINER_SCREEN_EXTENSIONS,
        FabricHostCapability.RECIPE_VIEWER,
    )
    override val functionality = FabricFunctionalityCatalog.JEI

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "jei" &&
            metadata.version == "17.3.1.5" &&
            metadata.environment == "*" &&
            metadata.entrypoints == setOf("client", "jei_mod_plugin", "main") &&
            metadata.mixins == 1 &&
            metadata.accessWidener == "jei.accesswidener" &&
            metadata.nestedJars == 0
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported JEI artifact: ${probe.metadata.version}" }
        val invoked = AtomicBoolean()
        scope.own(
            FabricScreens.register(id, SCREEN_ID, "Just Enough Items") { renderer ->
                FabricModDiagnostics.hookInvoked(id, HOOK, 0L)
                if (invoked.compareAndSet(false, true)) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                        "JEI_HOOK_INVOKED hook=$HOOK recipes=${renderer.session.registries.recipes.size}"
                    }
                }
                JeiRecipeMenu(renderer)
            },
        )
        scope.own(
            FabricContainerScreenExtensions.register(id, EXTENSION_ID) { context ->
                FabricContainerScreenExtension(
                    offset = Vec2f(-85.0f, 8.0f),
                    element = JeiIngredientOverlayElement(context.renderer),
                    creativeTabLabel = "Recipes",
                )
            },
        )
        scope.own(
            FabricSettings.register(id, minosoft("jei_options"), "JEI settings") {
                SettingsSchema(
                    title = "JEI settings",
                    entries = listOf(
                        ConfigEntry(
                            id = "bookmarks",
                            label = "Enable recipe bookmarks",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = JeiOptions::bookmarksEnabled,
                            write = JeiOptions::setBookmarksEnabled,
                        ),
                        ConfigEntry(
                            id = "ingredient_tooltips",
                            label = "Ingredient tooltips",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = JeiOptions::tooltipsEnabled,
                            write = JeiOptions::setTooltipsEnabled,
                        ),
                    ),
                    persist = JeiOptions::persist,
                )
            },
        )
        FabricModDiagnostics.hookInstalled(id, HOOK)
        scope.own(AutoCloseable { FabricModDiagnostics.hookUninstalled(id, HOOK) })
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "JEI_HOOK_INSTALLED hook=$HOOK source=synchronized-recipe-registry"
        }
    }

    private val SCREEN_ID = minosoft("jei_recipes")
    private val EXTENSION_ID = minosoft("jei_container")
    private const val HOOK = "recipe-viewer"
}

internal class JeiRecipeMenu(
    guiRenderer: GUIRenderer,
    initialQuery: String = "",
) : Menu(guiRenderer, preferredElementWidth = 320.0f) {
    private val allRecipes = guiRenderer.session.registries.recipes.toList()
        .mapNotNull { recipe ->
            guiRenderer.session.registries.recipes.getResourceLocation(recipe)?.let { RecipeDisplay.of(it, recipe) }
        }
        .sortedBy { it.identifier.toString() }
    private val categories = listOf(ALL_CATEGORIES) + allRecipes.map(RecipeDisplay::category).distinct().sorted()
    private val search = TextInputElement(guiRenderer, maxLength = 128).apply {
        prefMaxSize = Vec2f(WIDTH, 14.0f)
    }
    private val category = CycleSelectorElement(
        guiRenderer,
        options = categories,
        initialValue = ALL_CATEGORIES,
        label = { if (it == ALL_CATEGORIES) "All categories" else it.replace('_', ' ') },
    ) { refreshRecipes() }
    private val status = TextElement(guiRenderer, "", background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
    private val panel = ClippedScrollPanelElement(guiRenderer, Vec2f(WIDTH, VIEWPORT_HEIGHT))
    private val bookmarkFilter = ButtonElement(guiRenderer, "Bookmarks: all", onSubmit = ::toggleBookmarkFilter)
    private val transfer = ButtonElement(guiRenderer, "Transfer selected recipe", disabled = true, onSubmit = ::transferSelected)
    private var bookmarksOnly = false
    private var selected: ResourceLocation? = null

    init {
        this += TextElement(
            guiRenderer,
            "Just Enough Items",
            background = null,
            properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f),
        )
        this += status
        this += TextElement(guiRenderer, "Search recipes", background = null)
        search.onChangeCallback = ::refreshRecipes
        search.value = initialQuery
        this += search
        this += category
        if (JeiOptions.bookmarksEnabled()) this += bookmarkFilter
        this += transfer
        this += panel
        this += ButtonElement(guiRenderer, "Back") { guiRenderer.gui.pop() }
        refreshRecipes()
    }

    private fun toggleBookmarkFilter() {
        bookmarksOnly = !bookmarksOnly
        bookmarkFilter.text = if (bookmarksOnly) "Bookmarks: only" else "Bookmarks: all"
        refreshRecipes()
    }

    private fun refreshRecipes() {
        val terms = search.value.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val selectedCategory = category.value
        val visible = allRecipes.filter { recipe ->
            (selectedCategory == ALL_CATEGORIES || recipe.category == selectedCategory) &&
                (!bookmarksOnly || JeiOptions.bookmarked(recipe.identifier)) &&
                (terms.isEmpty() || recipe.searchText.let { text -> terms.all(text::contains) })
        }
        panel.setRows(visible.map { recipe ->
            JeiRecipeCardElement(
                guiRenderer,
                recipe,
                selected = recipe.identifier == selected,
                bookmarked = JeiOptions.bookmarked(recipe.identifier),
                onSelect = {
                    selected = recipe.identifier
                    refreshRecipes()
                },
                onBookmark = {
                    JeiOptions.toggleBookmark(recipe.identifier)
                    JeiOptions.persist()
                    refreshRecipes()
                },
            )
        })
        val selectedRecipe = allRecipes.firstOrNull { it.identifier == selected }
        transfer.disabled = selectedRecipe == null || !FabricRecipeTransfers.supported(
            FabricRecipeTransferContext(guiRenderer, selectedRecipe.recipe),
        )
        status.text = "${visible.size}/${allRecipes.size} synchronized recipes"
    }

    private fun transferSelected() {
        val recipe = allRecipes.firstOrNull { it.identifier == selected } ?: return
        status.text = if (FabricRecipeTransfers.transfer(FabricRecipeTransferContext(guiRenderer, recipe.recipe))) {
            "Transferred ${recipe.identifier}."
        } else {
            "§cNo active container transfer handler accepted ${recipe.identifier}."
        }
    }

    private companion object {
        const val WIDTH = 320.0f
        const val VIEWPORT_HEIGHT = 190.0f
        const val ALL_CATEGORIES = "*"
    }
}

private class JeiIngredientOverlayElement(
    guiRenderer: GUIRenderer,
) : Element(guiRenderer), AbstractLayout<Element> {
    private val allItems: List<Item> = guiRenderer.session.registries.item
        .distinctBy { it.identifier }
        .filterNot { it.identifier.namespace == "minecraft" && it.identifier.path == "air" }
        .sortedBy { it.identifier.toString() }
    private val recipes = ButtonElement(guiRenderer, "Recipes") {
        guiRenderer.gui.push(JeiRecipeMenu(guiRenderer))
    }.apply {
        size = Vec2f(WIDTH, BUTTON_HEIGHT)
        parent = this@JeiIngredientOverlayElement
    }
    private val search = TextInputElement(guiRenderer, maxLength = 64).apply {
        prefMaxSize = Vec2f(WIDTH, SEARCH_HEIGHT)
        parent = this@JeiIngredientOverlayElement
    }
    private val grid = ClippedVirtualGridElement(
        guiRenderer = guiRenderer,
        viewportSize = Vec2f(WIDTH, GRID_HEIGHT),
        columns = COLUMNS,
        cellHeight = CELL_SIZE,
        createCell = { item: Item ->
            JeiIngredientCellElement(
                guiRenderer,
                ItemStack(item, 1),
                bookmarked = JeiOptions.ingredientBookmarked(item.identifier),
                select = { guiRenderer.gui.push(JeiRecipeMenu(guiRenderer, item.identifier.toString())) },
                bookmark = {
                    JeiOptions.toggleIngredientBookmark(item.identifier)
                    JeiOptions.persist()
                    refresh()
                },
            )
        },
    ).apply { parent = this@JeiIngredientOverlayElement }

    override var activeElement: Element? = null
    override var activeDragElement: Element? = null

    init {
        _size = Vec2f(WIDTH, BUTTON_HEIGHT + SPACING + SEARCH_HEIGHT + SPACING + GRID_HEIGHT)
        search.onChangeCallback = ::refresh
        refresh()
    }

    private fun refresh() {
        val terms = search.value.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val filtered = allItems.filter { item ->
            val text = "${item.identifier} ${item.identifier.path.replace('_', ' ')}".lowercase()
            terms.all(text::contains)
        }.sortedWith(compareByDescending<Item> { JeiOptions.ingredientBookmarked(it.identifier) }.thenBy { it.identifier.toString() })
        grid.setItems(filtered)
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        recipes.render(offset, consumer, options)
        search.render(offset + SEARCH_OFFSET, consumer, options)
        grid.render(offset + GRID_OFFSET, consumer, options)
    }

    override fun forceSilentApply() {
        recipes.silentApply()
        search.silentApply()
        grid.silentApply()
        cacheUpToDate = false
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        if (position.y < BUTTON_HEIGHT) return recipes to position
        val searchPosition = position - SEARCH_OFFSET
        if (searchPosition.x >= 0.0f && searchPosition.y >= 0.0f && searchPosition.x < WIDTH && searchPosition.y < SEARCH_HEIGHT) {
            return search to searchPosition
        }
        val gridPosition = position - GRID_OFFSET
        if (gridPosition.x >= 0.0f && gridPosition.y >= 0.0f && gridPosition.x < WIDTH && gridPosition.y < GRID_HEIGHT) {
            return grid to gridPosition
        }
        return null
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
    }

    override fun tick() {
        search.tick()
        grid.tick()
    }

    override fun onOpen() {
        recipes.onOpen()
        search.onOpen()
        grid.onOpen()
    }

    override fun onHide() {
        recipes.onHide()
        search.onHide()
        grid.onHide()
    }

    override fun onClose() {
        recipes.onClose()
        search.onClose()
        grid.onClose()
    }

    private companion object {
        const val WIDTH = 78.0f
        const val BUTTON_HEIGHT = 16.0f
        const val SEARCH_HEIGHT = 12.0f
        const val GRID_HEIGHT = 108.0f
        const val CELL_SIZE = 18.0f
        const val COLUMNS = 4
        const val SPACING = 2.0f
        val SEARCH_OFFSET = Vec2f(0.0f, BUTTON_HEIGHT + SPACING)
        val GRID_OFFSET = Vec2f(0.0f, BUTTON_HEIGHT + SPACING + SEARCH_HEIGHT + SPACING)
    }
}

private class JeiIngredientCellElement(
    guiRenderer: GUIRenderer,
    private val stack: ItemStack,
    private val bookmarked: Boolean,
    private val select: () -> Unit,
    private val bookmark: () -> Unit,
) : Element(guiRenderer) {
    private val raw = RawItemElement(guiRenderer, RawItemElement.DEFAULT_SIZE, stack, this)
    private val background = ColorElement(
        guiRenderer,
        Vec2f(18.0f),
        if (bookmarked) RGBAColor(90, 75, 25, 230) else RGBAColor(35, 35, 40, 230),
    )
    private var popper: ItemInfoPopper? = null

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        background.size = size
        background.render(offset, consumer, options)
        raw.render(offset + 1.0f, consumer, options)
    }

    override fun forceSilentApply() {
        raw.silentApply()
        cacheUpToDate = false
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        context.window.cursorShape = CursorShapes.HAND
        if (JeiOptions.tooltipsEnabled()) popper = ItemInfoPopper(guiRenderer, absolute, stack).apply { show() }
        return true
    }

    override fun onMouseLeave(): Boolean {
        context.window.resetCursor()
        popper?.hide()
        popper = null
        return true
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (action != MouseActions.PRESS) return true
        when (button) {
            MouseButtons.LEFT -> select()
            MouseButtons.RIGHT -> if (JeiOptions.bookmarksEnabled()) bookmark()
            else -> Unit
        }
        return true
    }

    override fun onClose() {
        popper?.hide()
        popper = null
    }
}

private data class RecipeDisplay(
    val identifier: ResourceLocation,
    val category: String,
    val ingredients: List<ItemStack>,
    val result: ItemStack?,
    val recipe: Recipe,
) {
    val searchText = buildString {
        append(identifier.toString())
        append(' ')
        append(category)
        for (ingredient in ingredients) {
            append(' ')
            append(ingredient.item.identifier)
        }
        result?.let {
            append(' ')
            append(it.item.identifier)
        }
    }.lowercase()

    companion object {
        fun of(identifier: ResourceLocation, recipe: Recipe): RecipeDisplay {
            val ingredients: List<Ingredient>
            val result: ItemStack?
            when (recipe) {
                is ShapedRecipe -> {
                    ingredients = recipe.ingredients.toList()
                    result = recipe.result
                }
                is ShapelessRecipe -> {
                    ingredients = recipe.ingredients.toList()
                    result = recipe.result
                }
                is HeatRecipe -> {
                    ingredients = listOf(recipe.ingredient)
                    result = recipe.result
                }
                is StoneCuttingRecipe -> {
                    ingredients = listOf(recipe.ingredient)
                    result = recipe.result
                }
                is AbstractSmithingRecipe -> {
                    ingredients = listOf(recipe.ingredient)
                    result = recipe.result
                }
                else -> {
                    ingredients = emptyList()
                    result = null
                }
            }
            return RecipeDisplay(
                identifier = identifier,
                category = recipe.category?.name?.lowercase() ?: recipe::class.java.simpleName.removeSuffix("Recipe").lowercase(),
                ingredients = ingredients.mapNotNull { it.stacks.firstOrNull { stack -> stack != null } },
                result = result,
                recipe = recipe,
            )
        }
    }
}

private class JeiRecipeCardElement(
    guiRenderer: GUIRenderer,
    private val recipe: RecipeDisplay,
    selected: Boolean,
    bookmarked: Boolean,
    private val onSelect: () -> Unit,
    private val onBookmark: () -> Unit,
) : Element(guiRenderer), AbstractLayout<JeiStackElement> {
    private val background = ColorElement(
        guiRenderer,
        Vec2f(WIDTH, HEIGHT),
        if (selected) SELECTED_BACKGROUND else BACKGROUND,
    )
    private val title = TextElement(
        guiRenderer,
        "${if (bookmarked) "★ " else ""}${recipe.identifier}  [${recipe.category.replace('_', ' ')}]",
        background = null,
        parent = this,
    )
    private val stacks = buildList {
        recipe.ingredients.take(MAX_INGREDIENTS).forEachIndexed { index, stack ->
            add(JeiStackElement(guiRenderer, stack).apply {
                parent = this@JeiRecipeCardElement
                offset = Vec2f(PADDING + index * CELL_SIZE, TITLE_HEIGHT)
            })
        }
        recipe.result?.let { stack ->
            add(JeiStackElement(guiRenderer, stack).apply {
                parent = this@JeiRecipeCardElement
                offset = Vec2f(WIDTH - PADDING - CELL_SIZE, TITLE_HEIGHT)
            })
        }
    }

    override var activeElement: JeiStackElement? = null
    override var activeDragElement: JeiStackElement? = null

    init {
        _size = Vec2f(WIDTH, HEIGHT)
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        background.render(offset, consumer, options)
        title.render(offset + Vec2f(PADDING, 2.0f), consumer, options)
        for (stack in stacks) stack.render(offset + stack.offset, consumer, options)
    }

    override fun forceSilentApply() {
        title.silentApply()
        stacks.forEach(Element::silentApply)
        cacheUpToDate = false
    }

    override fun getAt(position: Vec2f): Pair<JeiStackElement, Vec2f>? {
        for (stack in stacks) {
            val inner = position - stack.offset
            if (inner.x >= 0.0f && inner.y >= 0.0f && inner.x < CELL_SIZE && inner.y < CELL_SIZE) return stack to inner
        }
        return null
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (action == MouseActions.PRESS && getAt(position) == null) {
            when (button) {
                MouseButtons.LEFT -> onSelect()
                MouseButtons.RIGHT -> if (JeiOptions.bookmarksEnabled()) onBookmark()
                else -> Unit
            }
            return true
        }
        return super<AbstractLayout>.onMouseAction(position, button, action, count)
    }

    private companion object {
        const val WIDTH = 320.0f
        const val HEIGHT = 42.0f
        const val CELL_SIZE = 18.0f
        const val TITLE_HEIGHT = 15.0f
        const val PADDING = 4.0f
        const val MAX_INGREDIENTS = 14
        val BACKGROUND = RGBAColor(25, 25, 30, 225)
        val SELECTED_BACKGROUND = RGBAColor(45, 70, 115, 235)
    }
}

private class JeiStackElement(
    guiRenderer: GUIRenderer,
    stack: ItemStack,
) : Element(guiRenderer) {
    private val raw = RawItemElement(guiRenderer, RawItemElement.DEFAULT_SIZE, stack, this)
    var offset = Vec2f.EMPTY
    private var popper: ItemInfoPopper? = null

    init {
        _size = Vec2f(18.0f)
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        raw.render(offset + 1.0f, consumer, options)
    }

    override fun forceSilentApply() {
        raw.silentApply()
        cacheUpToDate = false
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        context.window.cursorShape = CursorShapes.HAND
        if (JeiOptions.tooltipsEnabled()) {
            popper = ItemInfoPopper(guiRenderer, absolute, requireNotNull(raw.stack)).apply { show() }
        }
        return true
    }

    override fun onMouseLeave(): Boolean {
        context.window.resetCursor()
        popper?.hide()
        popper = null
        return true
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean = true
}

private object JeiOptions {
    private val store by lazy {
        FabricAdapterOptionStore(
            "jei",
            mapOf(
                "bookmarks_enabled" to "true",
                "ingredient_tooltips" to "true",
                "bookmarks" to "",
                "ingredient_bookmarks" to "",
            ),
        )
    }

    fun bookmarksEnabled() = store.boolean("bookmarks_enabled")
    fun tooltipsEnabled() = store.boolean("ingredient_tooltips")
    fun setBookmarksEnabled(value: Boolean) = store.set("bookmarks_enabled", value)
    fun setTooltipsEnabled(value: Boolean) = store.set("ingredient_tooltips", value)
    fun bookmarked(identifier: ResourceLocation) = identifier.toString() in bookmarks()
    fun ingredientBookmarked(identifier: ResourceLocation) = identifier.toString() in ingredientBookmarks()
    fun toggleBookmark(identifier: ResourceLocation) {
        val bookmarks = bookmarks().toMutableSet()
        if (!bookmarks.add(identifier.toString())) bookmarks.remove(identifier.toString())
        store.set("bookmarks", bookmarks.sorted().joinToString(","))
    }
    fun toggleIngredientBookmark(identifier: ResourceLocation) {
        val bookmarks = ingredientBookmarks().toMutableSet()
        if (!bookmarks.add(identifier.toString())) bookmarks.remove(identifier.toString())
        store.set("ingredient_bookmarks", bookmarks.sorted().joinToString(","))
    }

    fun persist() = store.persist()
    private fun bookmarks() = store.string("bookmarks").split(',').filter(String::isNotBlank).toSet()
    private fun ingredientBookmarks() = store.string("ingredient_bookmarks").split(',').filter(String::isNotBlank).toSet()
}
