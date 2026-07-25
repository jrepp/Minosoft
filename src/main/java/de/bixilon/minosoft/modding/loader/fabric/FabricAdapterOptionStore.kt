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

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.profile.ProfileOptions
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Properties

internal class FabricAdapterOptionStore(
    id: String,
    defaults: Map<String, String>,
) {
    private val path = ProfileOptions.path.resolve("fabric-options").resolve("$id.properties")
    private val defaults = defaults.toMap()
    private val values = linkedMapOf<String, String>()

    init {
        require(ID_PATTERN.matches(id)) { "Invalid Fabric option store id: $id" }
        require(this.defaults.size <= MAX_ENTRIES) { "Fabric option store exceeds $MAX_ENTRIES entries." }
        require(this.defaults.keys.all(KEY_PATTERN::matches)) { "Fabric option store contains an invalid key." }
        values.putAll(this.defaults)
        load()
    }

    @Synchronized
    fun boolean(key: String): Boolean = value(key).toBooleanStrictOrNull() ?: default(key).toBooleanStrict()

    @Synchronized
    fun integer(key: String): Int = value(key).toIntOrNull() ?: default(key).toInt()

    @Synchronized
    fun string(key: String): String = value(key)

    @Synchronized
    fun set(key: String, value: Boolean) = set(key, value.toString())

    @Synchronized
    fun set(key: String, value: Int) = set(key, value.toString())

    @Synchronized
    fun set(key: String, value: String) {
        require(value.length <= MAX_VALUE_LENGTH) { "Fabric option value exceeds $MAX_VALUE_LENGTH characters." }
        default(key)
        values[key] = value
    }

    @Synchronized
    fun persist() {
        Files.createDirectories(path.parent)
        val properties = Properties()
        for ((key, value) in values) properties.setProperty(key, value)
        val temporary = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "Minosoft source-native Fabric adapter options") }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun load() {
        if (!Files.isRegularFile(path)) return
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        for (key in defaults.keys) {
            properties.getProperty(key)?.takeIf { it.length <= MAX_VALUE_LENGTH }?.let { values[key] = it }
        }
    }

    private fun value(key: String): String = values[key] ?: default(key)

    private fun default(key: String): String = requireNotNull(defaults[key]) { "Unknown Fabric option: $key" }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        val KEY_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{0,127}")
        const val MAX_ENTRIES = 4_096
        const val MAX_VALUE_LENGTH = 16_384
    }
}
