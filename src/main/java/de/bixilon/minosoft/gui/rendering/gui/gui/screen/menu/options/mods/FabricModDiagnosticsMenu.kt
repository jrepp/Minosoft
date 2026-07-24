/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
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
import de.bixilon.minosoft.modding.loader.fabric.FabricModDiagnosticSnapshot
import de.bixilon.minosoft.modding.loader.fabric.FabricModDiagnostics
import java.util.Locale

class FabricModDiagnosticsMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 290.0f) {

    init {
        val diagnostics = FabricModDiagnostics.snapshot()
        this += TextElement(guiRenderer, "menu.mods.diagnostics.title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += SpacerElement(guiRenderer, Vec2f(0, 8))

        if (diagnostics == null) {
            this += TextElement(guiRenderer, "menu.mods.diagnostics.none".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
        } else {
            this += TextElement(guiRenderer, diagnostics.packName, background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
            this += TextElement(
                guiRenderer,
                IntegratedLanguage.LANGUAGE.forceTranslate(
                    minosoft("menu.mods.diagnostics.run"),
                    diagnostics.trajectory,
                    diagnostics.generation,
                    formatMillis(diagnostics.preflightNanos),
                ),
                background = null,
                properties = TextRenderProperties(HorizontalAlignments.CENTER),
            )
            this += TextElement(
                guiRenderer,
                IntegratedLanguage.LANGUAGE.forceTranslate(
                    minosoft("menu.mods.diagnostics.performance"),
                    String.format(Locale.ROOT, "%.1f", guiRenderer.context.renderStats.smoothAvgFPS),
                    diagnostics.uptimeNanos / 1_000_000_000L,
                ),
                background = null,
                properties = TextRenderProperties(HorizontalAlignments.CENTER),
            )
            this += SpacerElement(guiRenderer, Vec2f(0, 5))
            for (mod in diagnostics.mods) {
                this += ButtonElement(guiRenderer, modLabel(mod)) {
                    guiRenderer.gui.push(FabricModDiagnosticDetailMenu(guiRenderer, mod))
                }
            }
            this += ButtonElement(guiRenderer, "menu.mods.diagnostics.refresh".i18n()) {
                guiRenderer.gui.pop()
                guiRenderer.gui.push(FabricModDiagnosticsMenu(guiRenderer))
            }
        }
        this += ButtonElement(guiRenderer, "menu.mods.back".i18n()) { guiRenderer.gui.pop() }
    }

    private fun modLabel(mod: FabricModDiagnosticSnapshot) =
        IntegratedLanguage.LANGUAGE.forceTranslate(
            minosoft("menu.mods.diagnostics.mod"),
            mod.name,
            mod.status.wireName,
            mod.hookInvocations,
        )

    private fun formatMillis(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)
}

private class FabricModDiagnosticDetailMenu(
    guiRenderer: GUIRenderer,
    private val mod: FabricModDiagnosticSnapshot,
) : Menu(guiRenderer, preferredElementWidth = 330.0f) {

    init {
        this += TextElement(guiRenderer, mod.name, background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += TextElement(guiRenderer, "${mod.version} · ${mod.status.wireName}", background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
        this += TextElement(guiRenderer, "Adapter: ${mod.adapterId ?: "none"}", background = null)
        this += TextElement(guiRenderer, "Activation: ${formatMicros(mod.activationNanos)} us", background = null)
        mod.failure?.let { this += TextElement(guiRenderer, "Failure: $it", background = null) }
        this += SpacerElement(guiRenderer, Vec2f(0, 6))
        if (mod.hooks.isEmpty()) {
            this += TextElement(guiRenderer, "menu.mods.diagnostics.no_hooks".i18n(), background = null)
        } else {
            for (hook in mod.hooks) {
                val state = if (hook.installed) "installed" else "uninstalled"
                this += TextElement(guiRenderer, "${hook.kind}: $state", background = null)
                this += TextElement(
                    guiRenderer,
                    "  calls ${hook.invocations} · avg ${formatMicros(hook.averageNanos)} us · max ${formatMicros(hook.maxNanos)} us",
                    background = null,
                )
            }
        }
        this += ButtonElement(guiRenderer, "menu.mods.back".i18n()) { guiRenderer.gui.pop() }
    }

    private fun formatMicros(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000.0)
}
