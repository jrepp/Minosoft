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

sealed interface SettingsApplyResult {
    data class Applied(
        val changed: Set<ConfigEntry<*>>,
        val restartRequired: Set<ConfigEntry<*>>,
    ) : SettingsApplyResult

    data class Invalid(
        val errors: Map<ConfigEntry<*>, String>,
    ) : SettingsApplyResult

    data class Failed(
        val error: Throwable,
    ) : SettingsApplyResult
}

class SettingsSession(
    val schema: SettingsSchema,
) : ConfigValues {
    private val entries = schema.entries.toSet()
    private val baseline = linkedMapOf<ConfigEntry<*>, Any>()
    private val staged = linkedMapOf<ConfigEntry<*>, Any>()

    init {
        reload()
    }

    override operator fun <T : Any> get(entry: ConfigEntry<T>): T {
        require(entry in entries) { "Setting does not belong to this form: ${entry.id}" }
        @Suppress("UNCHECKED_CAST")
        return staged.getValue(entry) as T
    }

    fun <T : Any> set(entry: ConfigEntry<T>, value: T) {
        require(entry in entries) { "Setting does not belong to this form: ${entry.id}" }
        staged[entry] = value
    }

    fun enabled(entry: ConfigEntry<*>): Boolean {
        require(entry in entries) { "Setting does not belong to this form: ${entry.id}" }
        return entry.enabledWhen(this)
    }

    fun validationErrors(): Map<ConfigEntry<*>, String> {
        val errors = linkedMapOf<ConfigEntry<*>, String>()
        for (entry in schema.entries) {
            if (!enabled(entry)) continue
            validationError(entry)?.let { errors[entry] = it }
        }
        return errors
    }

    val dirtyEntries: Set<ConfigEntry<*>>
        get() = schema.entries.filterTo(linkedSetOf()) { baseline[it] != staged[it] }

    val dirty: Boolean get() = dirtyEntries.isNotEmpty()
    val canApply: Boolean get() = dirty && validationErrors().isEmpty()
    val restartRequired: Boolean get() = dirtyEntries.any(ConfigEntry<*>::restartRequired)

    fun reset() {
        for (entry in schema.entries) staged[entry] = entry.defaultValue
    }

    fun cancel() {
        staged.clear()
        staged.putAll(baseline)
    }

    fun reload() {
        val loaded = linkedMapOf<ConfigEntry<*>, Any>()
        for (entry in schema.entries) loaded[entry] = entry.readAny()
        baseline.clear()
        baseline.putAll(loaded)
        staged.clear()
        staged.putAll(loaded)
    }

    fun apply(): SettingsApplyResult {
        val errors = validationErrors()
        if (errors.isNotEmpty()) return SettingsApplyResult.Invalid(errors)

        val changed = dirtyEntries
        if (changed.isEmpty()) return SettingsApplyResult.Applied(emptySet(), emptySet())

        val previous = linkedMapOf<ConfigEntry<*>, Any>()
        for (entry in changed) previous[entry] = entry.readAny()

        val attempted = mutableListOf<ConfigEntry<*>>()
        try {
            for (entry in schema.entries) {
                if (entry !in changed) continue
                attempted += entry
                entry.writeAny(staged.getValue(entry))
            }
            schema.persist()
        } catch (error: Throwable) {
            rollback(attempted, previous, error)
            return SettingsApplyResult.Failed(error)
        }

        baseline.clear()
        baseline.putAll(staged)
        return SettingsApplyResult.Applied(
            changed = changed,
            restartRequired = changed.filterTo(linkedSetOf(), ConfigEntry<*>::restartRequired),
        )
    }

    private fun validationError(entry: ConfigEntry<*>): String? {
        return entry.validationError(staged.getValue(entry), this)
    }

    private fun rollback(
        attempted: List<ConfigEntry<*>>,
        previous: Map<ConfigEntry<*>, Any>,
        failure: Throwable,
    ) {
        for (entry in attempted.asReversed()) {
            try {
                entry.writeAny(previous.getValue(entry))
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
        }
    }

    private fun ConfigEntry<*>.readAny(): Any = read()

    private fun ConfigEntry<*>.writeAny(value: Any) {
        @Suppress("UNCHECKED_CAST")
        (write as (Any) -> Unit)(value)
    }

    private fun ConfigEntry<*>.validationError(value: Any, values: ConfigValues): String? {
        @Suppress("UNCHECKED_CAST")
        this as ConfigEntry<Any>
        return control.validate(value) ?: validate(value, values)
    }
}
