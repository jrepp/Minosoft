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

package de.bixilon.minosoft.assets.model.texture.entity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityTextureRulesTest {

    @Test
    fun `weight totals do not overflow`() {
        val rule = EntityTextureRule(
            index = 1,
            suffixes = listOf(1, 2),
            weights = listOf(Int.MAX_VALUE, Int.MAX_VALUE),
        )

        assertTrue(rule.select(EntityTextureContext(seed = 1)) in rule.suffixes)
    }

    @Test
    fun `pathological regular expressions have bounded matching work`() {
        val condition = EntityTextureCondition("name", "regex:(a+)+")
        val context = EntityTextureContext(
            seed = 1,
            strings = mapOf("name" to listOf("a".repeat(512) + "!")),
        )

        assertFalse(EntityTextureConditions.matches(condition, context))
    }
}
