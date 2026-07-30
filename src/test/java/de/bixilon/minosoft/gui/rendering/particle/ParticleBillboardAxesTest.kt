/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.particle

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ParticleBillboardAxesTest {

    @Test
    fun `billboard axes use view rotation without projection scaling`() {
        val view = CameraUtil.lookAt(
            Vec3f(3.0f, 4.0f, 5.0f),
            Vec3f(-1.0f, 2.0f, -3.0f),
            Vec3f(0.0f, 1.0f, 0.0f),
        )
        val (right, up) = particleBillboardAxes(view)
        val projected = CameraUtil.perspective(70.0f, 2.0f, 0.05f, 512.0f) * view

        assertEquals(1.0f, right.length(), 0.00001f)
        assertEquals(1.0f, up.length(), 0.00001f)
        assertEquals(0.0f, right dot up, 0.00001f)
        assertNotEquals(
            Vec3f(projected[0, 0], projected[0, 1], projected[0, 2]),
            right,
        )
        assertNotEquals(
            Vec3f(projected[1, 0], projected[1, 1], projected[1, 2]),
            up,
        )

        val normal = (right cross up).normalize()
        val uvBitangent = up * -1.0f
        val triangleNormal = (uvBitangent cross right).normalize()

        assertEquals(1.0f, triangleNormal dot normal, 0.00001f)
        assertEquals(-1.0f, (right cross uvBitangent) dot normal, 0.00001f)
    }
}
