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

package de.bixilon.minosoft.gui.rendering.camera.arm

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class FirstPersonItemTransformTest {
    @Test
    fun `main arm selects matching vanilla display transform`() {
        assertEquals(FirstPersonItemTransform.displayPosition(Arms.RIGHT), DisplayPositions.FIRST_PERSON_RIGHT_HAND)
        assertEquals(FirstPersonItemTransform.displayPosition(Arms.LEFT), DisplayPositions.FIRST_PERSON_LEFT_HAND)
        assertEquals(Arms.LEFT, FirstPersonItemTransform.opposite(Arms.RIGHT))
        assertEquals(Arms.RIGHT, FirstPersonItemTransform.opposite(Arms.LEFT))
    }

    @Test
    fun `base held item pose mirrors across the camera`() {
        val right = FirstPersonItemTransform.create(Arms.RIGHT, Mat4f(), false, null) * Vec3f(0.5f)
        val left = FirstPersonItemTransform.create(Arms.LEFT, Mat4f(), false, null) * Vec3f(0.5f)

        assertTrue(abs(right.x + left.x) < 0.0001f)
        assertTrue(abs(right.y - left.y) < 0.0001f)
        assertTrue(abs(right.z - left.z) < 0.0001f)
    }

    @Test
    fun `offhand display explicitly mirrors right hand grip`() {
        val right = MMat4f().apply {
            translateAssign(Vec3f(0.25f, -0.5f, 0.75f))
            rotateYAssign(0.4f)
            rotateZAssign(-0.2f)
        }.unsafe
        val left = FirstPersonItemTransform.mirrorRightHandDisplay(right)

        assertTrue(abs(left[0, 3] + right[0, 3]) < 0.0001f)
        assertTrue(abs(left[1, 3] - right[1, 3]) < 0.0001f)
        assertTrue(abs(left[2, 3] - right[2, 3]) < 0.0001f)
        assertTrue(abs(left[0, 2] + right[0, 2]) < 0.0001f)
        assertTrue(abs(left[2, 0] + right[2, 0]) < 0.0001f)
    }

    @Test
    fun `swing follows vanilla cadence and mirrors by arm`() {
        val start = FirstPersonItemTransform.swingOffset(Arms.RIGHT, 0.0f)
        val quarter = FirstPersonItemTransform.swingOffset(Arms.RIGHT, 0.25f)
        val halfRight = FirstPersonItemTransform.swingOffset(Arms.RIGHT, 0.5f)
        val halfLeft = FirstPersonItemTransform.swingOffset(Arms.LEFT, 0.5f)
        val end = FirstPersonItemTransform.swingOffset(Arms.RIGHT, 1.0f)
        val rotation = FirstPersonItemTransform.swingRotation(Arms.RIGHT, 0.5f)

        assertEquals(Vec3f.EMPTY, start)
        assertEquals(Vec3f.EMPTY, end)
        assertTrue(quarter.x < 0.0f)
        assertTrue(quarter.y < 0.0f)
        assertTrue(quarter.z < 0.0f)
        assertTrue(abs(halfRight.x + halfLeft.x) < 0.0001f)
        assertTrue(abs(halfRight.y - halfLeft.y) < 0.0001f)
        assertTrue(rotation.x < -60.0f)
        assertTrue(rotation.y < 0.0f)
        assertTrue(rotation.z < 0.0f)
    }
}
