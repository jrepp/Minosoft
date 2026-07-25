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
import de.bixilon.minosoft.gui.rendering.gui.elements.input.cycle.CycleSelectorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.scroll.ClippedScrollPanelElement
import de.bixilon.minosoft.gui.rendering.gui.elements.spacer.SpacerElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.elements.input.TextInputElement
import de.bixilon.minosoft.gui.rendering.gui.gui.popper.text.TextPopper
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu
import de.bixilon.minosoft.modding.loader.fabric.FabricModProbe
import de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalitySummary
import de.bixilon.minosoft.modding.loader.fabric.FabricDependency
import de.bixilon.minosoft.modding.loader.fabric.FabricPackLoader
import de.bixilon.minosoft.modding.loader.fabric.FabricScreens
import de.bixilon.minosoft.modding.loader.fabric.FabricSettings

class FabricModSettingsMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 260.0f) {
    private val activation = FabricPackLoader.current()
    private val search = TextInputElement(guiRenderer, maxLength = 128).apply {
        prefMaxSize = Vec2f(WIDTH, 14.0f)
    }
    private val filter = CycleSelectorElement(
        guiRenderer,
        options = ModFilter.entries,
        initialValue = ModFilter.ALL,
        label = ModFilter::label,
    ) { refreshMods() }
    private val results = TextElement(
        guiRenderer,
        "",
        background = null,
        properties = TextRenderProperties(HorizontalAlignments.CENTER),
    )
    private val panel = ClippedScrollPanelElement(guiRenderer, Vec2f(WIDTH, VIEWPORT_HEIGHT))

    init {
        this += TextElement(guiRenderer, "menu.mods.title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += SpacerElement(guiRenderer, Vec2f(0, 10))

        if (activation == null) {
            this += TextElement(guiRenderer, "menu.mods.none".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
        } else {
            this += TextElement(guiRenderer, activation.report.pack.name, background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
            this += ButtonElement(guiRenderer, "menu.mods.diagnostics.open".i18n()) {
                guiRenderer.gui.push(FabricModDiagnosticsMenu(guiRenderer))
            }
            this += TextElement(guiRenderer, "Search installed mods", background = null)
            search.onChangeCallback = ::refreshMods
            this += search
            this += filter
            this += results
            this += panel
            refreshMods()
        }

        this += ButtonElement(guiRenderer, "menu.mods.back".i18n()) { guiRenderer.gui.pop() }
    }

    private fun refreshMods() {
        val activation = activation ?: return
        val terms = search.value.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val settingsByOwner = FabricSettings.registrations().groupBy { it.owner }
        val selectedFilter = filter.value
        val probes = activation.report.mods
            .filter { probe ->
                val metadata = probe.metadata
                val searchable = buildString {
                    append(metadata.id)
                    append(' ')
                    append(metadata.name)
                    append(' ')
                    append(metadata.version)
                    append(' ')
                    append(metadata.description.orEmpty())
                    append(' ')
                    append(metadata.badges.joinToString(" "))
                }.lowercase()
                terms.all(searchable::contains) && when (selectedFilter) {
                    ModFilter.ALL -> true
                    ModFilter.CONFIGURABLE -> probe.adapter?.id in settingsByOwner
                    ModFilter.LIBRARIES -> "library" in metadata.badges || probe.metadata.id in dependencyOnlyIds(probes = activation.report.mods)
                    ModFilter.BLOCKED -> !probe.activatable
                }
            }
            .sortedWith(compareBy<FabricModProbe>({ it.metadata.parent != null }, { it.metadata.parent ?: it.metadata.id }, { it.metadata.name }))

        panel.setRows(probes.map { probe ->
            val settings = probe.adapter?.id?.let(settingsByOwner::get).orEmpty()
            ModEntryButtonElement(guiRenderer, probe, settings.isNotEmpty()) {
                if (settings.isNotEmpty()) {
                    FabricScreens.open(guiRenderer, settings.first().id)
                } else {
                    guiRenderer.gui.push(FabricModFunctionalityMenu(guiRenderer, probe.metadata.name, probe.functionality))
                }
            }
        })
        results.text = "${probes.size}/${activation.report.mods.size} installed mods"
    }

    private fun dependencyOnlyIds(probes: List<FabricModProbe>): Set<String> {
        val roots = probes.mapTo(hashSetOf()) { it.metadata.id }
        return probes.flatMap { it.metadata.dependencies["depends"].orEmpty() }
            .map(FabricDependency::id)
            .filterTo(hashSetOf()) { it in roots }
    }

    private enum class ModFilter(val label: String) {
        ALL("All mods"),
        CONFIGURABLE("Configurable"),
        LIBRARIES("Libraries"),
        BLOCKED("Blocked"),
    }

    private companion object {
        const val WIDTH = 300.0f
        const val VIEWPORT_HEIGHT = 210.0f
    }
}

private class ModEntryButtonElement(
    guiRenderer: GUIRenderer,
    private val probe: FabricModProbe,
    private val configurable: Boolean,
    open: () -> Unit,
) : ButtonElement(guiRenderer, label(probe, configurable), onSubmit = open) {
    private var popper: TextPopper? = null

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        super.onMouseEnter(position, absolute)
        popper = TextPopper(guiRenderer, absolute, description(probe, configurable)).apply { show() }
        return true
    }

    override fun onMouseLeave(): Boolean {
        popper?.hide()
        popper = null
        return super.onMouseLeave()
    }

    override fun onClose() {
        popper?.hide()
        popper = null
        super.onClose()
    }

    private companion object {
        fun label(probe: FabricModProbe, configurable: Boolean): String {
            val metadata = probe.metadata
            val summary = FabricFunctionalitySummary.of(probe.functionality)
            val icon = if (metadata.icon != null) "▣ " else ""
            val indent = if (metadata.parent != null) "  ↳ " else ""
            val badges = metadata.badges.takeIf(Set<String>::isNotEmpty)?.joinToString(prefix = " [", postfix = "]") ?: ""
            val config = if (configurable) " ⚙" else ""
            return "$indent$icon${metadata.name} ${metadata.version}$badges$config  ${summary.mapped}/${summary.total}"
        }

        fun description(probe: FabricModProbe, configurable: Boolean): String {
            val metadata = probe.metadata
            return buildString {
                append(metadata.description ?: metadata.id)
                append("\n\n")
                append("ID: ${metadata.id}")
                metadata.parent?.let { append("\nParent: $it") }
                metadata.dependencies["depends"].orEmpty().takeIf(List<FabricDependency>::isNotEmpty)?.let {
                    append("\nDepends: ")
                    append(it.joinToString { dependency -> dependency.id })
                }
                metadata.badges.takeIf(Set<String>::isNotEmpty)?.let {
                    append("\nBadges: ")
                    append(it.joinToString())
                }
                append(if (configurable) "\nClick to configure." else "\nClick to inspect mapping coverage.")
            }
        }
    }
}
