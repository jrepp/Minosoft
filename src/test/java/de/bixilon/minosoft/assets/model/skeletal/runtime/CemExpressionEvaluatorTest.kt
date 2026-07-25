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

    @Test
    fun `evaluates ordered EMF render outputs`() {
        val frame = CemExpressionEvaluator(
            listOf(
                SkeletalExpressionBinding("root", "render.shadow_size", "0.75"),
                SkeletalExpressionBinding("root", "render.shadow_opacity", "render.shadow_size / 2"),
                SkeletalExpressionBinding("root", "render.shadow_offset_x", "2"),
                SkeletalExpressionBinding("root", "render.leash_offset_y", "-1"),
            ),
        ).evaluate()

        assertEquals(0.75f, frame.render.getValue(CemRenderProperty.SHADOW_SIZE))
        assertEquals(0.375f, frame.render.getValue(CemRenderProperty.SHADOW_OPACITY))
        assertEquals(2.0f, frame.render.getValue(CemRenderProperty.SHADOW_OFFSET_X))
        assertEquals(-1.0f, frame.render.getValue(CemRenderProperty.LEASH_OFFSET_Y))
    }

    @Test
    fun `passes raw NBT calls through the per-frame entity context`() {
        val frame = CemExpressionEvaluator(
            listOf(
                SkeletalExpressionBinding(
                    "root",
                    "this.visible",
                    "nbt(Items.*.id,ipattern:minecraft:d*)",
                ),
            ),
        ).evaluate(
            SkeletalExpressionContext(
                rawFunctionResolver = { name, arguments ->
                    if (name == "nbt" && arguments == listOf("Items.*.id", "ipattern:minecraft:d*")) 1.0 else 0.0
                },
            ),
        )

        assertEquals(
            1.0f,
            frame.transforms.getValue("root").getValue(CemTransformProperty.VISIBLE),
        )
    }

    @Test
    fun `part lookups read live absolute values and resolve aliases`() {
        val evaluator = CemExpressionEvaluator(
            listOf(
                SkeletalExpressionBinding("body", "var.snapshot", "this.tx + arm.ry + this.sx"),
                SkeletalExpressionBinding("body", "this.rz", "arm.ry + 0.25"),
                SkeletalExpressionBinding("body", "var.after", "this.rz"),
            ),
            aliases = mapOf("body" to "torso", "arm" to "left_arm"),
            knownParts = setOf("torso", "left_arm"),
        )
        val parts = mapOf(
            "torso" to properties(tx = 12.0f, sx = 1.5f),
            "left_arm" to properties(ry = 0.75f),
        )

        val frame = evaluator.evaluate(partValues = parts)

        assertEquals(14.25, frame.variables.getValue("var.snapshot"))
        assertEquals(1.0f, frame.transforms.getValue("torso").getValue(CemTransformProperty.ROTATE_Z))
        assertEquals(1.0, frame.variables.getValue("var.after"))
    }

    @Test
    fun `missing part lookups use EMF zero while missing assignment targets fail`() {
        val frame = CemExpressionEvaluator(
            listOf(SkeletalExpressionBinding("root", "var.missing", "ghost.sx + ghost.visible")),
        ).evaluate()
        assertEquals(0.0, frame.variables.getValue("var.missing"))

        assertFailsWith<IllegalArgumentException> {
            CemExpressionEvaluator(
                listOf(SkeletalExpressionBinding("root", "ghost.rx", "1")),
                knownParts = setOf("root"),
            )
        }
    }

    private fun properties(
        tx: Float = 0.0f,
        ty: Float = 0.0f,
        tz: Float = 0.0f,
        rx: Float = 0.0f,
        ry: Float = 0.0f,
        rz: Float = 0.0f,
        sx: Float = 1.0f,
        sy: Float = 1.0f,
        sz: Float = 1.0f,
        visible: Float = 1.0f,
        hidden: Float = 0.0f,
    ) = mapOf(
        CemTransformProperty.TRANSLATE_X to tx,
        CemTransformProperty.TRANSLATE_Y to ty,
        CemTransformProperty.TRANSLATE_Z to tz,
        CemTransformProperty.ROTATE_X to rx,
        CemTransformProperty.ROTATE_Y to ry,
        CemTransformProperty.ROTATE_Z to rz,
        CemTransformProperty.SCALE_X to sx,
        CemTransformProperty.SCALE_Y to sy,
        CemTransformProperty.SCALE_Z to sz,
        CemTransformProperty.VISIBLE to visible,
        CemTransformProperty.VISIBLE_BOXES to hidden,
    )
}
