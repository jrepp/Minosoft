/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemExpressionFrame
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemTransformProperty
import kotlin.test.Test
import kotlin.test.assertEquals

class CemExpressionManagerTest {

    @Test
    fun `expression transform values replace live part properties`() {
        val transform = TransformInstance(
            id = 1,
            pivot = Vec3f(0.0f, 0.75f, 0.0f),
            children = emptyMap(),
            baseRotation = Vec3f(0.1f, 0.2f, 0.3f),
            baseScale = Vec3f(2.0f, 3.0f, 4.0f),
        )
        transform.reset()
        transform.recordTranslationPixels(Vec3f(1.0f, 2.0f, 3.0f))
        transform.recordRotation(Vec3f(0.4f, 0.5f, 0.6f))
        val before = transform.partProperties()
        val frame = CemExpressionFrame(
            transforms = mapOf(
                "part" to mapOf(
                    CemTransformProperty.TRANSLATE_X to 20.0f,
                    CemTransformProperty.ROTATE_Y to -0.75f,
                    CemTransformProperty.SCALE_Z to 8.0f,
                ),
            ),
            variables = emptyMap(),
            render = emptyMap(),
        )

        frame.apply(mapOf("part" to transform), mapOf("part" to before))

        val after = transform.partProperties()
        assertEquals(20.0f, after.getValue(CemTransformProperty.TRANSLATE_X))
        assertEquals(before.getValue(CemTransformProperty.TRANSLATE_Y), after.getValue(CemTransformProperty.TRANSLATE_Y))
        assertEquals(-0.75f, after.getValue(CemTransformProperty.ROTATE_Y))
        assertEquals(before.getValue(CemTransformProperty.ROTATE_X), after.getValue(CemTransformProperty.ROTATE_X))
        assertEquals(8.0f, after.getValue(CemTransformProperty.SCALE_Z))
        assertEquals(before.getValue(CemTransformProperty.SCALE_X), after.getValue(CemTransformProperty.SCALE_X))
    }

    @Test
    fun `visible boxes hides only local cubes while visible hides descendants`() {
        val child = TransformInstance(2, Vec3f.EMPTY, emptyMap())
        val parent = TransformInstance(1, Vec3f.EMPTY, mapOf("child" to child))
        parent.reset()
        var before = parent.partProperties()

        CemExpressionFrame(
            transforms = mapOf("parent" to mapOf(CemTransformProperty.VISIBLE_BOXES to 1.0f)),
            variables = emptyMap(),
            render = emptyMap(),
        ).apply(mapOf("parent" to parent), mapOf("parent" to before))
        parent.transform(Mat4f.EMPTY)

        assertEquals(0.0f, parent.renderMatrix[0, 0])
        assertEquals(1.0f, child.renderMatrix[0, 0])

        parent.reset()
        before = parent.partProperties()
        CemExpressionFrame(
            transforms = mapOf("parent" to mapOf(CemTransformProperty.VISIBLE to 0.0f)),
            variables = emptyMap(),
            render = emptyMap(),
        ).apply(mapOf("parent" to parent), mapOf("parent" to before))
        parent.transform(Mat4f.EMPTY)

        assertEquals(0.0f, parent.renderMatrix[0, 0])
        assertEquals(0.0f, child.renderMatrix[0, 0])
    }
}
