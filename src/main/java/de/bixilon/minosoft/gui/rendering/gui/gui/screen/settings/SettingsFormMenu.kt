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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.settings

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.settings.SettingsApplyResult
import de.bixilon.minosoft.config.settings.SettingsFilter
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.config.settings.SettingsSession
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.scroll.ClippedScrollPanelElement
import de.bixilon.minosoft.gui.rendering.gui.elements.tab.TabBarElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.elements.input.TextInputElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu

class SettingsFormMenu(
    guiRenderer: GUIRenderer,
    val schema: SettingsSchema,
) : Menu(guiRenderer, preferredElementWidth = FORM_WIDTH) {
    val session = SettingsSession(schema)
    private val filter = SettingsFilter(schema)
    private val status = TextElement(
        guiRenderer,
        "",
        background = null,
        properties = TextRenderProperties(HorizontalAlignments.CENTER),
    )
    private val rows = schema.entries.map { ConfigEntryElements.create(guiRenderer, session, it, ::refresh) }
    private val rowsByEntry = schema.entries.zip(rows).toMap()
    private val panel = ClippedScrollPanelElement(guiRenderer, Vec2f(FORM_WIDTH, VIEWPORT_HEIGHT))
    private val tabs = TabBarElement(
        guiRenderer = guiRenderer,
        tabs = schema.categories,
        label = { it.label },
    ) {
        filter.category = it.id
        updateVisibleRows()
    }
    private val search = TextInputElement(guiRenderer, maxLength = SEARCH_MAX_LENGTH).apply {
        prefMaxSize = Vec2f(FORM_WIDTH, SEARCH_HEIGHT)
        onChangeCallback = {
            filter.query = value
            updateVisibleRows()
        }
    }
    private val applyButton = ButtonElement(guiRenderer, "Apply", disabled = true, onSubmit = ::applyChanges)
    private var lastStatus: String? = null

    init {
        this += TextElement(
            guiRenderer,
            schema.title,
            background = null,
            properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f),
        )
        this += status
        if (schema.categories.size > 1) this += tabs
        this += TextElement(guiRenderer, "Search settings", background = null)
        this += search
        this += panel
        this += ButtonElement(guiRenderer, "Reset") {
            session.reset()
            lastStatus = null
            refresh()
        }
        this += applyButton
        this += ButtonElement(guiRenderer, "Cancel") {
            session.cancel()
            guiRenderer.gui.pop()
        }
        updateVisibleRows()
        refresh()
    }

    override fun onOpen() {
        session.reload()
        lastStatus = null
        refresh()
        super.onOpen()
    }

    override fun onClose() {
        session.cancel()
        super.onClose()
    }

    private fun applyChanges() {
        lastStatus = when (val result = session.apply()) {
            is SettingsApplyResult.Applied -> {
                if (result.changed.isEmpty()) {
                    "No changes to apply."
                } else if (result.restartRequired.isNotEmpty()) {
                    "Applied ${result.changed.size} change(s). Restart required."
                } else {
                    "Applied ${result.changed.size} change(s)."
                }
            }
            is SettingsApplyResult.Invalid -> "§c${result.errors.values.first()}"
            is SettingsApplyResult.Failed -> {
                val message = result.error.message?.take(MAX_STATUS_LENGTH) ?: result.error::class.java.simpleName
                "§cCould not apply settings: $message"
            }
        }
        refresh()
    }

    private fun refresh() {
        val errors = session.validationErrors()
        for (row in rows) row.refresh(errors[row.entry])
        applyButton.disabled = !session.canApply
        status.text = lastStatus ?: when {
            errors.isNotEmpty() -> "§c${errors.values.first()}"
            session.restartRequired -> "Unsaved changes. Restart required after apply."
            session.dirty -> "Unsaved changes."
            else -> ""
        }
    }

    private fun updateVisibleRows() {
        panel.setRows(filter.visibleEntries.mapNotNull(rowsByEntry::get))
        filter.reconcileSelection()
    }

    private companion object {
        const val FORM_WIDTH = 260.0f
        const val VIEWPORT_HEIGHT = 180.0f
        const val MAX_STATUS_LENGTH = 160
        const val SEARCH_MAX_LENGTH = 128
        const val SEARCH_HEIGHT = 14.0f
    }
}
