/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransformInstancePivotTest {

    @Test
    fun `base rotation leaves its joint pivot invariant`() {
        val pivot = Vec3f(1.0f, 2.0f, 3.0f)
        val transform = TransformInstance(
            id = 1,
            pivot = pivot,
            children = emptyMap(),
            baseRotation = Vec3f((PI / 2.0).toFloat(), 0.0f, 0.0f),
        )
        val identity = MMat4f().apply { clearAssign() }

        transform.reset()
        transform.transform(identity.unsafe)
        val result = transform.renderMatrix.unsafe * pivot

        assertEquals(pivot.x, result.x, 0.0001f)
        assertEquals(pivot.y, result.y, 0.0001f)
        assertEquals(pivot.z, result.z, 0.0001f)
    }

    @Test
    fun `rendered pose copies by transform name rather than buffer id`() {
        val sourceArm = TransformInstance(1, Vec3f.EMPTY, emptyMap())
        val sourceLeg = TransformInstance(2, Vec3f.EMPTY, emptyMap())
        val source = TransformInstance(
            0,
            Vec3f.EMPTY,
            mapOf("arm" to sourceArm, "leg" to sourceLeg),
        )
        sourceArm.matrix.translateAssign(Vec3f(1.0f, 2.0f, 3.0f))
        sourceLeg.matrix.translateAssign(Vec3f(4.0f, 5.0f, 6.0f))
        source.transform(MMat4f().apply { clearAssign() }.unsafe)

        val destinationArm = TransformInstance(8, Vec3f.EMPTY, emptyMap())
        val destinationLeg = TransformInstance(7, Vec3f.EMPTY, emptyMap())
        val destination = TransformInstance(
            6,
            Vec3f.EMPTY,
            mapOf("leg" to destinationLeg, "arm" to destinationArm),
        )

        destination.copyRenderedPoseFrom(source)

        assertContentEquals(sourceArm.renderMatrix._0.array, destinationArm.renderMatrix._0.array)
        assertContentEquals(sourceLeg.renderMatrix._0.array, destinationLeg.renderMatrix._0.array)
    }

    @Test
    fun `pose copy rejects a structurally incompatible mesh`() {
        val source = TransformInstance(0, Vec3f.EMPTY, emptyMap())
        val destination = TransformInstance(
            0,
            Vec3f.EMPTY,
            mapOf("head" to TransformInstance(1, Vec3f.EMPTY, emptyMap())),
        )

        assertFailsWith<IllegalArgumentException> {
            destination.copyRenderedPoseFrom(source)
        }
    }
}
