/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.modding.loader.fabric

import kotlin.test.Test
import kotlin.test.assertEquals

class SodiumTerrainProviderDescriptorTest {
    @Test
    fun `terrain runtime identity remains compatible`() {
        val descriptor = SodiumCompatibilityAdapter.TERRAIN_DESCRIPTOR

        assertEquals("minosoft:sodium-compatible-terrain", descriptor.owner.value)
        assertEquals("sodium-0.5.8-adapter-minosoft-core", descriptor.implementation)
    }
}
