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

import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.settings.SettingsFormMenu
import java.util.concurrent.atomic.AtomicBoolean

data class FabricSettingsRegistration(
    val owner: String,
    val id: ResourceLocation,
    val title: String,
    val schemaFactory: (GUIRenderer) -> SettingsSchema,
)

object FabricSettings {
    private val settings = linkedMapOf<ResourceLocation, FabricSettingsRegistration>()

    fun register(
        owner: String,
        id: ResourceLocation,
        schema: SettingsSchema,
    ): AutoCloseable = register(owner, id, schema.title) { schema }

    fun register(
        owner: String,
        id: ResourceLocation,
        title: String,
        schemaFactory: (GUIRenderer) -> SettingsSchema,
    ): AutoCloseable {
        require(title.isNotBlank()) { "Fabric settings title must not be blank." }
        val registration = FabricSettingsRegistration(owner, id, title, schemaFactory)
        val screen = FabricScreens.register(owner, id, title) { renderer ->
            val schema = schemaFactory(renderer)
            require(schema.title == title) {
                "Fabric settings schema title '${schema.title}' does not match its registration title '$title'."
            }
            SettingsFormMenu(renderer, schema)
        }
        try {
            synchronized(this) {
                require(settings.putIfAbsent(id, registration) == null) { "Fabric settings are already registered: $id" }
            }
        } catch (error: Throwable) {
            try {
                screen.close()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            synchronized(this) { settings.remove(id, registration) }
            screen.close()
        }
    }

    @Synchronized
    fun registrations(): List<FabricSettingsRegistration> = settings.values.toList()
}
