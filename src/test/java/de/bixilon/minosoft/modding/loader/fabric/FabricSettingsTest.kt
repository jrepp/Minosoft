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

import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FabricSettingsTest {
    @Test
    fun `settings schemas use owned screen registration and cleanup`() {
        var value = false
        val schema = SettingsSchema(
            "Native settings",
            listOf(
                ConfigEntry(
                    id = "enabled",
                    label = "Enabled",
                    defaultValue = false,
                    control = BooleanConfigControl,
                    read = { value },
                    write = { value = it },
                ),
            ),
        )
        val id = ResourceLocation.of("test:native_settings")
        val registration = FabricSettings.register("test:settings", id, schema)
        try {
            val screen = FabricScreens.registrations().single { it.id == id }
            assertEquals("test:settings", screen.owner)
            assertEquals(schema.title, screen.title)
            assertEquals(listOf(id), FabricSettings.registrations().map(FabricSettingsRegistration::id))
        } finally {
            registration.close()
            registration.close()
        }
        assertTrue(FabricScreens.registrations().none { it.id == id })
        assertTrue(FabricSettings.registrations().none { it.id == id })
    }
}
