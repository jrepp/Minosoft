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

package de.bixilon.minosoft.gui.rendering.skeletal.mesh

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.primitive.IntUtil.toHex
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkeletalMeshUtilTest {

    // Take code from skeletal/shade.glsl
    private fun decodeNormalPart(data: Int): Float {
        if (data < 8) return (data / 8.0f) - 1.0f
        return (data - 8) / 7.0f
    }

    private fun decodeNormal(normal: Int): Vec3f {
        val x = normal and 0x0F
        val y = normal shr 8 and 0x0F
        val z = normal shr 4 and 0x0F
        return Vec3f(decodeNormalPart(x), decodeNormalPart(y), decodeNormalPart(z))
    }


    private fun assertEquals(actual: Vec3f, expected: Vec3f) {
        val delta = expected - actual
        if (delta.length2() < 0.1f) return
        throw AssertionError("Mismatch. Expected $expected, actual $actual")
    }

    private fun assertEquals(actual: Int, expected: Int) {
        if (actual == expected) return
        throw AssertionError("Expected ${expected.toHex()} but got ${actual.toHex()}")
    }

    @Test
    fun `encode max negative normal`() {
        val normal = Vec3f(-1, -1, -1)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0x00)
        assertEquals(decodeNormal(encoded), Vec3f(-1, -1, -1))
    }

    @Test
    fun `encode max positive normal`() {
        val normal = Vec3f(1, 1, 1)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0xFFF)
        assertEquals(decodeNormal(encoded), normal)
    }

    @Test
    fun `correct positive clamping`() {
        val normal = Vec3f(2, 2, 2)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0xFFF)
        assertEquals(decodeNormal(encoded), Vec3f(1, 1, 1))
    }

    @Test
    fun `correct negative clamping`() {
        val normal = Vec3f(-2, -2, -2)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0x00)
        assertEquals(decodeNormal(encoded), Vec3f(-1, -1, -1))
    }

    @Test
    fun `zero`() {
        val normal = Vec3f(0, 0, 0)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0x888)
        assertEquals(decodeNormal(encoded), normal)
    }

    @Test
    fun `zero dot one`() {
        val normal = Vec3f(0.15f, 0.15f, 0.15f)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0x999)
        assertEquals(decodeNormal(encoded), normal)
    }

    @Test
    fun `minus zero dot one`() {
        val normal = Vec3f(-0.1f, -0.1f, -0.1f)
        val encoded = SkeletalMeshUtil.encodeNormal(normal)
        assertEquals(encoded, 0x777)
        assertEquals(decodeNormal(encoded), normal)
    }

    @Test
    fun `face uv midpoint is independent of vertex order`() {
        val midpoint = SkeletalMeshUtil.faceUvMidpoint(
            UnpackedUVArray(
                floatArrayOf(
                    0.75f, 0.25f,
                    0.25f, 0.75f,
                    0.25f, 0.25f,
                    0.75f, 0.75f,
                ),
            ),
        )

        assertEquals(0.5f, midpoint.x)
        assertEquals(0.5f, midpoint.y)
    }

    @Test
    fun `face tangent follows increasing u and retains positive handedness`() {
        val tangent = SkeletalMeshUtil.faceTangent(
            positions = unitQuad(),
            uv = UnpackedUVArray(
                floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f,
                    0.0f, 1.0f,
                ),
            ),
            normal = Vec3f(0.0f, 0.0f, 1.0f),
        )

        assertEquals(1.0f, tangent.x, 1.0e-6f)
        assertEquals(0.0f, tangent.y, 1.0e-6f)
        assertEquals(0.0f, tangent.z, 1.0e-6f)
        assertEquals(1.0f, tangent.w, 1.0e-6f)
    }

    @Test
    fun `face tangent retains mirrored uv handedness`() {
        val tangent = SkeletalMeshUtil.faceTangent(
            positions = unitQuad(),
            uv = UnpackedUVArray(
                floatArrayOf(
                    1.0f, 0.0f,
                    0.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 1.0f,
                ),
            ),
            normal = Vec3f(0.0f, 0.0f, 1.0f),
        )

        assertEquals(-1.0f, tangent.x, 1.0e-6f)
        assertEquals(0.0f, tangent.y, 1.0e-6f)
        assertEquals(0.0f, tangent.z, 1.0e-6f)
        assertEquals(-1.0f, tangent.w, 1.0e-6f)
    }

    private fun unitQuad(): FaceVertexData = floatArrayOf(
        0.0f, 0.0f, 0.0f,
        1.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
    )
}
