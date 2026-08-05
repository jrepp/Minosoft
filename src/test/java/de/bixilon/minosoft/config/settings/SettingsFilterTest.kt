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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsFilterTest {
    @Test
    fun `filters within category using id label and description`() {
        val schema = schema()
        val filter = SettingsFilter(schema)

        filter.query = "shadow quality"
        assertEquals(filter.visibleEntries.map { it.id }, listOf("shadows"))

        filter.category = "advanced"
        filter.query = "driver workaround"
        assertEquals(filter.visibleEntries.map { it.id }, listOf("driver"))
    }

    @Test
    fun `selection is preserved while visible and reconciled otherwise`() {
        val filter = SettingsFilter(schema())
        filter.select("shadows")
        assertEquals(filter.reconcileSelection(), "shadows")

        filter.query = "nothing matches"
        assertNull(filter.reconcileSelection())

        filter.query = ""
        assertEquals(filter.reconcileSelection(), "shadows")
    }

    @Test
    fun `global-search schemas find entries outside the selected category`() {
        val source = schema()
        val filter = SettingsFilter(
            SettingsSchema(
                title = source.title,
                entries = source.entries,
                categories = source.categories,
                searchAcrossCategories = true,
            ),
        )

        filter.query = "driver workaround"

        assertEquals(listOf("driver"), filter.visibleEntries.map { it.id })
        filter.query = ""
        assertEquals(listOf("shadows"), filter.visibleEntries.map { it.id })
    }

    @Test
    fun `schema rejects entries with missing categories`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SettingsSchema(
                "Broken",
                listOf(booleanEntry("missing", "missing")),
                categories = listOf(SettingsCategory.GENERAL),
            )
        }
    }

    private fun schema() = SettingsSchema(
        title = "Graphics",
        entries = listOf(
            booleanEntry("shadows", SettingsCategory.GENERAL_ID, "Shadow Quality", "Controls entity shadow quality"),
            booleanEntry("driver", "advanced", "Driver checks", "Compatibility workaround"),
        ),
        categories = listOf(SettingsCategory.GENERAL, SettingsCategory("advanced", "Advanced")),
    )

    private fun booleanEntry(id: String, category: String, label: String = id, description: String? = null) = ConfigEntry(
        id = id,
        label = label,
        description = description,
        defaultValue = true,
        control = BooleanConfigControl,
        read = { true },
        write = {},
        category = category,
    )
}
