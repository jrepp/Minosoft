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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.mods

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.language.IntegratedLanguage
import de.bixilon.minosoft.data.language.LanguageUtil.i18n
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.spacer.SpacerElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.lighting.LightingMenu
import de.bixilon.minosoft.modding.loader.fabric.FabricFunctionality
import de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityStatus
import de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalitySummary
import de.bixilon.minosoft.modding.loader.fabric.FabricHostMenu

class FabricModFunctionalityMenu(
    guiRenderer: GUIRenderer,
    private val modName: String,
    functionality: List<FabricFunctionality>,
    page: Int = 0,
) : Menu(guiRenderer, preferredElementWidth = 320.0f) {
    private val entries = functionality.sortedWith(compareBy(FabricFunctionality::area, FabricFunctionality::status, FabricFunctionality::title))

    init {
        val pages = maxOf(1, (entries.size + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE)
        val safePage = page.coerceIn(0, pages - 1)
        val summary = FabricFunctionalitySummary.of(entries)

        this += TextElement(guiRenderer, modName, background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += TextElement(
            guiRenderer,
            IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.mods.counts"), summary.mapped, summary.partial, summary.unmapped),
            background = null,
            properties = TextRenderProperties(HorizontalAlignments.CENTER),
        )
        this += SpacerElement(guiRenderer, Vec2f(0, 6))

        if (safePage == 0) {
            for (menu in entries.mapNotNull(FabricFunctionality::menu).distinct()) {
                this += ButtonElement(guiRenderer, menuLabel(menu)) { openMenu(guiRenderer, menu) }
            }
        }

        val start = safePage * ENTRIES_PER_PAGE
        for (entry in entries.drop(start).take(ENTRIES_PER_PAGE)) {
            val restart = if (entry.restartRequired) " *" else ""
            this += TextElement(guiRenderer, "[${entry.status.wireName}] ${entry.title}$restart", background = null)
        }

        if (safePage + 1 < pages) {
            this += ButtonElement(guiRenderer, IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.mods.next"), safePage + 2, pages)) {
                guiRenderer.gui.push(FabricModFunctionalityMenu(guiRenderer, modName, entries, safePage + 1))
            }
        }
        this += ButtonElement(guiRenderer, "menu.mods.back".i18n()) { guiRenderer.gui.pop() }
    }

    private fun menuLabel(menu: FabricHostMenu) = when (menu) {
        FabricHostMenu.LIGHTING -> "menu.mods.open_lighting".i18n()
    }

    private fun openMenu(guiRenderer: GUIRenderer, menu: FabricHostMenu) = when (menu) {
        FabricHostMenu.LIGHTING -> guiRenderer.gui.push(LightingMenu)
    }

    private companion object {
        const val ENTRIES_PER_PAGE = 7
    }
}
