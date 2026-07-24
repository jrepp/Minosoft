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

package de.bixilon.minosoft.gui.rendering.entities.renderer.display

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayQuaternionTest {

    @Test
    fun `quaternion rotation composes onto display matrix`() {
        val half = sqrt(0.5f)
        val matrix = MMat4f()
        matrix.rotateQuaternionAssign(Vec4f(0.0f, half, 0.0f, half))

        assertEquals(0.0f, matrix[0, 0], 0.0001f)
        assertEquals(1.0f, matrix[0, 2], 0.0001f)
        assertEquals(-1.0f, matrix[2, 0], 0.0001f)
        assertEquals(0.0f, matrix[2, 2], 0.0001f)
    }

    @Test
    fun `quaternion rotation normalizes input and ignores invalid values`() {
        val normalized = MMat4f()
        normalized.rotateQuaternionAssign(Vec4f(0.0f, 2.0f, 0.0f, 2.0f))
        assertEquals(1.0f, normalized[0, 2], 0.0001f)

        val invalid = MMat4f()
        invalid.rotateQuaternionAssign(Vec4f(Float.NaN, 0.0f, 0.0f, 1.0f))
        assertTrue(invalid == MMat4f())
    }
}
