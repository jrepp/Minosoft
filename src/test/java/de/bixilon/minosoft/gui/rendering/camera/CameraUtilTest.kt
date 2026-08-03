/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.camera

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraUtilTest {

    @Test
    fun perspective() {
        val mat = CameraUtil.perspective(60.0f, 1.5f, 0.1f, 100.0f)
        val expected = Mat4f(
            -0.10407997f, 0.0f, 0.0f, 0.0f,
            0.0f, -0.15611996f, 0.0f, 0.0f,
            0.0f, 0.0f, -1.002002f, -0.2002002f,
            0.0f, 0.0f, -1.0f, 0.0f,
        )

        assertEquals(mat, expected)
    }

    @Test
    fun `look at`() {
        val mat = CameraUtil.lookAt(Vec3f(3, 4, 5), Vec3f(1, 2, 3).normalize(), Vec3f(0, 0, 1))
        val expected = Mat4f(
            -0.78523135f, 0.6192025f, 0.0f, -0.12111592f,
            -0.42677248f, -0.5412044f, 0.7245433f, -0.17758131f,
            0.44863898f, 0.5689341f, 0.68922925f, -7.0677996f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )

        assertEquals(mat, expected)
    }

    @Test
    fun `look at remains finite and orthogonal at vertical pitch`() {
        for (centerY in listOf(-1.0f, 1.0f)) {
            val mat = CameraUtil.lookAt(Vec3f.EMPTY, Vec3f(0.0f, centerY, 0.0f), Vec3f(0.0f, 1.0f, 0.0f))
            val side = Vec3f(mat[0, 0], mat[0, 1], mat[0, 2])
            val up = Vec3f(mat[1, 0], mat[1, 1], mat[1, 2])
            val forward = Vec3f(mat[2, 0], mat[2, 1], mat[2, 2])

            assertTrue((0 until 4).all { row -> (0 until 4).all { column -> mat[row, column].isFinite() } })
            assertEquals(1.0f, side.length(), 1.0e-6f)
            assertEquals(1.0f, up.length(), 1.0e-6f)
            assertEquals(1.0f, forward.length(), 1.0e-6f)
            assertEquals(0.0f, side dot up, 1.0e-6f)
            assertEquals(0.0f, side dot forward, 1.0e-6f)
            assertEquals(0.0f, up dot forward, 1.0e-6f)
        }
    }

    @Test
    fun `look at uses a finite bootstrap basis before the camera direction is populated`() {
        val mat = CameraUtil.lookAt(Vec3f.EMPTY, Vec3f.EMPTY, Vec3f(0.0f, 1.0f, 0.0f))

        assertTrue((0 until 4).all { row -> (0 until 4).all { column -> mat[row, column].isFinite() } })
        assertEquals(1.0f, Vec3f(mat[0, 0], mat[0, 1], mat[0, 2]).length(), 1.0e-6f)
        assertEquals(1.0f, Vec3f(mat[1, 0], mat[1, 1], mat[1, 2]).length(), 1.0e-6f)
        assertEquals(1.0f, Vec3f(mat[2, 0], mat[2, 1], mat[2, 2]).length(), 1.0e-6f)
    }
}
