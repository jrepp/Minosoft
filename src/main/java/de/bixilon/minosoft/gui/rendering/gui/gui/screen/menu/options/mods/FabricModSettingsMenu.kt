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
import de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalitySummary
import de.bixilon.minosoft.modding.loader.fabric.FabricPackLoader

class FabricModSettingsMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 260.0f) {

    init {
        this += TextElement(guiRenderer, "menu.mods.title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += SpacerElement(guiRenderer, Vec2f(0, 10))

        val activation = FabricPackLoader.current()
        if (activation == null) {
            this += TextElement(guiRenderer, "menu.mods.none".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
        } else {
            this += TextElement(guiRenderer, activation.report.pack.name, background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
            this += ButtonElement(guiRenderer, "menu.mods.diagnostics.open".i18n()) {
                guiRenderer.gui.push(FabricModDiagnosticsMenu(guiRenderer))
            }
            for (probe in activation.report.mods) {
                val functionality = probe.functionality
                val summary = FabricFunctionalitySummary.of(functionality)
                val label = IntegratedLanguage.LANGUAGE.forceTranslate(
                    minosoft("menu.mods.summary"),
                    probe.metadata.name,
                    summary.mapped,
                    summary.partial,
                    summary.unmapped,
                )
                this += ButtonElement(guiRenderer, label) {
                    guiRenderer.gui.push(FabricModFunctionalityMenu(guiRenderer, probe.metadata.name, functionality))
                }
            }
        }

        this += ButtonElement(guiRenderer, "menu.mods.back".i18n()) { guiRenderer.gui.pop() }
    }
}
