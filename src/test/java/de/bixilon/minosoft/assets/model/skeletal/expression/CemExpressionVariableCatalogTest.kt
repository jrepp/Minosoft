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

package de.bixilon.minosoft.assets.model.skeletal.expression

import kotlin.math.E
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CemExpressionVariableCatalogTest {

    @Test
    fun `catalog supplies live values and safe known defaults`() {
        val context = CemExpressionVariableCatalog.context(
            mapOf(
                "age" to 12.5,
                "is_alive" to 1.0,
            ),
        )

        assertEquals(12.5, context.variable("age"))
        assertEquals(1.0, context.variable("is_alive"))
        assertEquals(0.0, context.variable("limb_speed"))
        assertEquals(0.0, context.variable("is_in_gui"))
        assertTrue(context.variable("nan").isNaN())
    }

    @Test
    fun `catalog accepts EMF compatibility spellings`() {
        val context = CemExpressionVariableCatalog.context(
            mapOf(
                "is_aggressive" to 1.0,
                "is_ridden" to 1.0,
                "frame_counter" to 42.0,
            ),
        )

        assertEquals(1.0, context.variable("is_agressive"))
        assertEquals(1.0, context.variable("is_aggresive"))
        assertEquals(1.0, context.variable("is_agresive"))
        assertEquals(1.0, context.variable("is_riden"))
        assertEquals(42.0, context.variable("frame_count"))
    }

    @Test
    fun `catalog preserves explicit failure for unknown names`() {
        assertFailsWith<SkeletalExpressionException> {
            CemExpressionVariableCatalog.context().variable("not_an_emf_variable")
        }
    }

    @Test
    fun `expression constants include Euler's number`() {
        assertEquals(E, SkeletalExpression.compile("e").evaluate(SkeletalExpressionContext()))
    }
}
