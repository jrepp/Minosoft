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

package de.bixilon.minosoft.config.settings

import java.util.Locale

class SettingsFilter(
    private val schema: SettingsSchema,
) {
    var category: String = schema.categories.first().id
        set(value) {
            require(schema.categories.any { it.id == value }) { "Unknown settings category: $value" }
            field = value
        }
    var query: String = ""
    var selectedEntryId: String? = null
        private set

    val visibleEntries: List<ConfigEntry<*>>
        get() {
            val terms = query.trim().lowercase(Locale.ROOT).split(WHITESPACE).filter(String::isNotEmpty)
            return schema.entries.filter { entry ->
                entry.category == category && (terms.isEmpty() || searchableText(entry).let { text -> terms.all(text::contains) })
            }
        }

    fun select(entryId: String?) {
        require(entryId == null || schema.entries.any { it.id == entryId }) { "Unknown settings entry: $entryId" }
        selectedEntryId = entryId
    }

    fun reconcileSelection(): String? {
        val visible = visibleEntries
        if (visible.none { it.id == selectedEntryId }) selectedEntryId = visible.firstOrNull()?.id
        return selectedEntryId
    }

    private fun searchableText(entry: ConfigEntry<*>): String {
        return buildString {
            append(entry.id)
            append(' ')
            append(entry.label)
            entry.description?.let {
                append(' ')
                append(it)
            }
        }.lowercase(Locale.ROOT)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
