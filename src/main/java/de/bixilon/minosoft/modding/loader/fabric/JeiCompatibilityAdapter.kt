/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.spacer.SpacerElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu
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
                    element = ButtonElement(context.renderer, "Recipes") {
                        FabricScreens.open(context.renderer, SCREEN_ID)
                    }.apply {
                        size = Vec2f(78.0f, 16.0f)
                    },
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
    page: Int = 0,
) : Menu(guiRenderer, preferredElementWidth = 320.0f) {
    init {
        val recipes = guiRenderer.session.registries.recipes.toList()
            .mapNotNull { recipe -> guiRenderer.session.registries.recipes.getResourceLocation(recipe)?.let { it to recipe } }
            .sortedBy { it.first.toString() }
        val pages = maxOf(1, (recipes.size + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE)
        val safePage = page.coerceIn(0, pages - 1)

        this += TextElement(
            guiRenderer,
            "Just Enough Items",
            background = null,
            properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f),
        )
        this += TextElement(
            guiRenderer,
            "${recipes.size} synchronized recipes - page ${safePage + 1}/$pages",
            background = null,
            properties = TextRenderProperties(HorizontalAlignments.CENTER),
        )
        this += SpacerElement(guiRenderer, Vec2f(0.0f, 6.0f))

        for ((identifier, recipe) in recipes.drop(safePage * ENTRIES_PER_PAGE).take(ENTRIES_PER_PAGE)) {
            val category = recipe.category?.name?.lowercase()?.replace('_', ' ') ?: "other"
            this += TextElement(guiRenderer, "$identifier - $category", background = null)
        }
        if (safePage > 0) {
            this += ButtonElement(guiRenderer, "Previous") {
                replacePage(guiRenderer, safePage - 1)
            }
        }
        if (safePage + 1 < pages) {
            this += ButtonElement(guiRenderer, "Next") {
                replacePage(guiRenderer, safePage + 1)
            }
        }
        this += ButtonElement(guiRenderer, "Back") { guiRenderer.gui.pop() }
    }

    private companion object {
        const val ENTRIES_PER_PAGE = 8

        fun replacePage(guiRenderer: GUIRenderer, page: Int) {
            guiRenderer.gui.pop()
            guiRenderer.gui.push(JeiRecipeMenu(guiRenderer, page))
        }
    }
}
