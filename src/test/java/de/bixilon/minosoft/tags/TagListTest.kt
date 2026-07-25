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

package de.bixilon.minosoft.tags

import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagListTest {

    @Test
    fun `matching exposes every tag containing a registry value`() {
        val plains = Biome(ResourceLocation.of("minecraft:plains"), 0.8f, 0.4f)
        val desert = Biome(ResourceLocation.of("minecraft:desert"), 2.0f, 0.0f)
        val list = TagList(
            mapOf(
                ResourceLocation.of("minecraft:is_overworld") to Tag(setOf(plains, desert)),
                ResourceLocation.of("minecraft:is_dry") to Tag(setOf(desert)),
            ),
        )

        assertEquals(
            setOf(ResourceLocation.of("minecraft:is_overworld"), ResourceLocation.of("minecraft:is_dry")),
            list.matching(desert),
        )
        assertTrue(list.matching(null).isEmpty())
    }
}
