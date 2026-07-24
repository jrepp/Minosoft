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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkeletalExpressionTest {

    @Test
    fun `evaluate arithmetic conditions variables and functions`() {
        val expression = SkeletalExpression.compile("if(age >= 10 && is_alive, clamp(sin(pi / 2) * 3, 0, 2), -1)")

        assertEquals(
            2.0,
            expression.evaluate(SkeletalExpressionContext(mapOf("age" to 12.0, "is_alive" to 1.0))),
        )
        assertEquals(
            -1.0,
            expression.evaluate(SkeletalExpressionContext(mapOf("age" to 9.0, "is_alive" to 1.0))),
        )
    }

    @Test
    fun `conditional and if evaluation are lazy`() {
        val context = SkeletalExpressionContext(emptyMap())

        assertEquals(4.0, SkeletalExpression.compile("true ? 4 : missing").evaluate(context))
        assertEquals(5.0, SkeletalExpression.compile("if(false, missing, 5)").evaluate(context))
        assertEquals(6.0, SkeletalExpression.compile("catch(missing, 6)").evaluate(context))
    }

    @Test
    fun `random source is caller controlled`() {
        val expression = SkeletalExpression.compile("random() + random()")

        assertEquals(0.5, expression.evaluate(SkeletalExpressionContext(random = { 0.25 })))
    }

    @Test
    fun `unknown values and bounded evaluation fail explicitly`() {
        assertFailsWith<SkeletalExpressionException> {
            SkeletalExpression.compile("missing + 1").evaluate(SkeletalExpressionContext())
        }
        assertFailsWith<SkeletalExpressionException> {
            SkeletalExpression.compile("1 + 2 + 3").evaluate(SkeletalExpressionContext(), operationLimit = 2)
        }
        assertFailsWith<SkeletalExpressionException> {
            SkeletalExpression.compile("unknown_function(1)").evaluate(SkeletalExpressionContext())
        }
    }

    @Test
    fun `parse depth also bounds unary and conditional recursion`() {
        assertFailsWith<SkeletalExpressionException> {
            SkeletalExpression.compile("!".repeat(SkeletalExpression.MAX_PARSE_DEPTH + 1) + "true")
        }
        assertFailsWith<SkeletalExpressionException> {
            SkeletalExpression.compile(
                "true ? 1 : ".repeat(SkeletalExpression.MAX_PARSE_DEPTH + 1) + "0"
            )
        }
    }

    @Test
    fun `malformed numeric literals report expression errors`() {
        assertFailsWith<SkeletalExpressionException> {
            SkeletalExpression.compile("1..2")
        }
    }
}
