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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FabricFunctionalityCatalogTest {

    @Test
    fun `every built-in adapter catalogs unique functionality`() {
        for (adapter in FabricCompatibilityAdapters.snapshot()) {
            val functionality = adapter.functionality

            assertTrue(functionality.isNotEmpty(), adapter.id)
            assertEquals(functionality.size, functionality.map(FabricFunctionality::id).distinct().size, adapter.id)
            assertTrue(functionality.all { it.id.isNotBlank() && it.title.isNotBlank() && it.detail.isNotBlank() }, adapter.id)
        }
    }

    @Test
    fun `every advertised capability has a mapped or partial catalog entry`() {
        for (adapter in FabricCompatibilityAdapters.snapshot()) {
            for (capability in adapter.capabilities) {
                val functionality = adapter.functionality.singleOrNull { it.id == capability.wireName }

                assertNotNull(functionality, "${adapter.id} does not catalog ${capability.wireName}")
                assertTrue(functionality.status != FabricFunctionalityStatus.UNMAPPED, "${adapter.id} advertises an unmapped capability")
            }
        }
    }

    @Test
    fun `menu targets point at an implemented or partial host mapping`() {
        val menuEntries = FabricCompatibilityAdapters.snapshot().flatMap(FabricCompatibilityAdapter::functionality).filter { it.menu != null }

        assertTrue(menuEntries.isNotEmpty())
        assertTrue(menuEntries.all { it.status != FabricFunctionalityStatus.UNMAPPED })
        assertTrue(menuEntries.all { it.hostMapping != null })
    }

    @Test
    fun `sodium catalog distinguishes mapped and unmapped surfaces`() {
        val summary = FabricFunctionalitySummary.of(SodiumCompatibilityAdapter.functionality)

        assertEquals(3, summary.mapped)
        assertTrue(summary.partial > 0)
        assertTrue(summary.unmapped > summary.mapped)
        assertEquals(FabricFunctionalityStatus.MAPPED, SodiumCompatibilityAdapter.functionality.single { it.id == "brightness" }.status)
        assertEquals(FabricFunctionalityStatus.UNMAPPED, SodiumCompatibilityAdapter.functionality.single { it.id == "persistent-mapping" }.status)
    }

    @Test
    fun `known blocked artifacts retain honest front end catalogs`() {
        fun catalog(id: String) = FabricFunctionalityCatalog.known(
            FabricMetadata(id, "1.0.0", id, "client", emptySet(), emptyMap(), emptySet(), 0, null, emptyList(), "test"),
        )

        assertTrue(catalog("iris").isNotEmpty())
        assertTrue(catalog("iris").any { it.status == FabricFunctionalityStatus.PARTIAL })
        assertTrue(catalog("jei").isNotEmpty())
        assertTrue(catalog("jei").any { it.status == FabricFunctionalityStatus.MAPPED })
        assertTrue(catalog("modmenu").any { it.status == FabricFunctionalityStatus.PARTIAL })
        assertTrue(catalog("xaerominimap").any { it.status == FabricFunctionalityStatus.PARTIAL })
        assertTrue(catalog("xaerominimap").any { it.status == FabricFunctionalityStatus.UNMAPPED })
        assertTrue(catalog("xaeroworldmap").any { it.status == FabricFunctionalityStatus.PARTIAL })
        assertTrue(catalog("xaeroworldmap").any { it.status == FabricFunctionalityStatus.UNMAPPED })
        assertTrue(catalog("inventorymanagement").any { it.status == FabricFunctionalityStatus.PARTIAL })
        assertTrue(catalog("inventorymanagement").any { it.status == FabricFunctionalityStatus.UNMAPPED })
        assertEquals(
            FabricFunctionalityStatus.PARTIAL,
            catalog("inventorymanagement").single { it.id == "server-protocol" }.status,
        )
        assertTrue(catalog("unknown").isEmpty())
    }
}
