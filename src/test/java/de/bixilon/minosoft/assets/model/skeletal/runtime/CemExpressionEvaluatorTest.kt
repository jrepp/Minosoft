/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalExpressionBinding
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CemExpressionEvaluatorTest {

    @Test
    fun `evaluates ordered model variables part lookups aliases and visibility`() {
        val evaluator = CemExpressionEvaluator(
            listOf(
                SkeletalExpressionBinding("body", "var.phase", "age * 0.5"),
                SkeletalExpressionBinding("body", "this.rx", "var.phase"),
                SkeletalExpressionBinding("body", "this.tx", "this.rx * 2"),
                SkeletalExpressionBinding("body", "varb.shown", "health > 0"),
                SkeletalExpressionBinding("body", "head.visible", "varb.shown"),
            ),
            aliases = mapOf("body" to "torso", "head" to "skull"),
        )

        val frame = evaluator.evaluate(SkeletalExpressionContext(mapOf("age" to 8.0, "health" to 20.0)))

        assertEquals(4.0f, frame.transforms.getValue("torso").getValue(CemTransformProperty.ROTATE_X))
        assertEquals(8.0f, frame.transforms.getValue("torso").getValue(CemTransformProperty.TRANSLATE_X))
        assertEquals(1.0f, frame.transforms.getValue("skull").getValue(CemTransformProperty.VISIBLE))
        assertEquals(1.0, frame.variables.getValue("varb.shown"))
    }

    @Test
    fun `normalizes boolean variables and rejects unsupported or non-finite results`() {
        val boolean = CemExpressionEvaluator(
            listOf(
                SkeletalExpressionBinding("root", "varb.flag", "2"),
                SkeletalExpressionBinding("root", "this.visible_boxes", "varb.flag"),
            ),
        ).evaluate()
        assertEquals(1.0, boolean.variables.getValue("varb.flag"))
        assertEquals(
            1.0f,
            boolean.transforms.getValue("root").getValue(CemTransformProperty.VISIBLE_BOXES),
        )

        assertFailsWith<IllegalArgumentException> {
            CemExpressionEvaluator(listOf(SkeletalExpressionBinding("root", "this.unknown", "1")))
        }
        assertFailsWith<SkeletalExpressionException> {
            CemExpressionEvaluator(
                listOf(SkeletalExpressionBinding("root", "this.rx", "1 / 0")),
            ).evaluate()
        }
    }
}
