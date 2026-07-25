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

sealed interface ConfigControl<T : Any> {
    fun validate(value: T): String? = null
}

data object BooleanConfigControl : ConfigControl<Boolean>

class TextConfigControl(
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) : ConfigControl<String> {
    init {
        require(maxLength in 1..MAX_LENGTH) { "Text setting length must be in 1..$MAX_LENGTH: $maxLength" }
    }

    override fun validate(value: String): String? {
        return if (value.length <= maxLength) null else "Must contain at most $maxLength characters."
    }

    companion object {
        const val DEFAULT_MAX_LENGTH = 256
        const val MAX_LENGTH = 16_384
    }
}

class SteppedConfigControl<T : Any>(
    values: List<T>,
    val label: (T) -> Any = { it.toString() },
) : ConfigControl<T> {
    val values: List<T> = values.toList()

    init {
        require(this.values.isNotEmpty()) { "A stepped setting must provide at least one value." }
        require(this.values.size <= MAX_VALUES) { "A stepped setting may provide at most $MAX_VALUES values." }
        require(this.values.distinct().size == this.values.size) { "Stepped setting values must be unique." }
    }

    override fun validate(value: T): String? {
        return if (value in values) null else "Value is not one of the available steps."
    }

    companion object {
        const val MAX_VALUES = 4_096
    }
}

class CycleConfigControl<T : Any>(
    options: List<T>,
    val label: (T) -> Any = { it.toString() },
) : ConfigControl<T> {
    val options: List<T> = options.toList()

    init {
        require(this.options.isNotEmpty()) { "A cycle setting must provide at least one option." }
        require(this.options.size <= MAX_OPTIONS) { "A cycle setting may provide at most $MAX_OPTIONS options." }
        require(this.options.distinct().size == this.options.size) { "Cycle setting options must be unique." }
    }

    override fun validate(value: T): String? {
        return if (value in options) null else "Value is not one of the available options."
    }

    companion object {
        const val MAX_OPTIONS = 4_096
    }
}

interface ConfigValues {
    operator fun <T : Any> get(entry: ConfigEntry<T>): T
}

class ConfigEntry<T : Any>(
    val id: String,
    val label: String,
    val description: String? = null,
    val defaultValue: T,
    val control: ConfigControl<T>,
    val read: () -> T,
    val write: (T) -> Unit,
    val validate: (value: T, values: ConfigValues) -> String? = { _, _ -> null },
    val enabledWhen: (ConfigValues) -> Boolean = { true },
    val restartRequired: Boolean = false,
    val category: String = SettingsCategory.GENERAL_ID,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid setting id: $id" }
        require(id.length <= MAX_ID_LENGTH) { "Setting id exceeds $MAX_ID_LENGTH characters: $id" }
        require(label.isNotBlank()) { "Setting label must not be blank: $id" }
        require(description == null || description.length <= MAX_DESCRIPTION_LENGTH) {
            "Setting description exceeds $MAX_DESCRIPTION_LENGTH characters: $id"
        }
        require(control.validate(defaultValue) == null) { "Default value is invalid for setting $id." }
        require(ID_PATTERN.matches(category)) { "Invalid setting category: $category" }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]*")
        private const val MAX_ID_LENGTH = 128
        private const val MAX_DESCRIPTION_LENGTH = 4_096
    }
}

data class SettingsCategory(
    val id: String,
    val label: String,
    val description: String? = null,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid settings category id: $id" }
        require(label.isNotBlank()) { "Settings category label must not be blank: $id" }
        require(description == null || description.length <= MAX_DESCRIPTION_LENGTH) {
            "Settings category description exceeds $MAX_DESCRIPTION_LENGTH characters: $id"
        }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]*")
        const val GENERAL_ID = "general"
        val GENERAL = SettingsCategory(GENERAL_ID, "General")
        private const val MAX_DESCRIPTION_LENGTH = 4_096
    }
}

class SettingsSchema(
    val title: String,
    entries: List<ConfigEntry<*>>,
    categories: List<SettingsCategory> = listOf(SettingsCategory.GENERAL),
    val persist: () -> Unit = {},
) {
    val entries: List<ConfigEntry<*>> = entries.toList()
    val categories: List<SettingsCategory> = categories.toList()

    init {
        require(title.isNotBlank()) { "Settings title must not be blank." }
        require(title.length <= MAX_TITLE_LENGTH) { "Settings title exceeds $MAX_TITLE_LENGTH characters." }
        require(this.entries.size <= MAX_ENTRIES) { "A settings form may contain at most $MAX_ENTRIES entries." }
        require(this.entries.map(ConfigEntry<*>::id).distinct().size == this.entries.size) {
            "Settings entry ids must be unique."
        }
        require(this.categories.isNotEmpty()) { "A settings form must contain at least one category." }
        require(this.categories.size <= MAX_CATEGORIES) { "A settings form may contain at most $MAX_CATEGORIES categories." }
        require(this.categories.map(SettingsCategory::id).distinct().size == this.categories.size) {
            "Settings category ids must be unique."
        }
        val categoryIds = this.categories.mapTo(hashSetOf(), SettingsCategory::id)
        require(this.entries.all { it.category in categoryIds }) {
            "Every settings entry must reference a category declared by the form."
        }
    }

    companion object {
        const val MAX_ENTRIES = 4_096
        const val MAX_CATEGORIES = 256
        private const val MAX_TITLE_LENGTH = 256
    }
}
