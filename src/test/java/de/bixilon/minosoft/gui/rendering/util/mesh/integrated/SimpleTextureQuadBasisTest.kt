/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.util.mesh.integrated

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SimpleTextureQuadBasisTest {

    @Test
    fun `projected shadow basis follows the horizontal quad and uv axes`() {
        val basis = SimpleTextureQuadBasis.calculate(
            position0 = Vec3f(0.0f, 2.0f, 0.0f),
            position1 = Vec3f(0.0f, 2.0f, 1.0f),
            position2 = Vec3f(1.0f, 2.0f, 1.0f),
            uv0 = Vec2f(0.0f, 0.0f),
            uv1 = Vec2f(0.0f, 1.0f),
            uv2 = Vec2f(1.0f, 1.0f),
            uv3 = Vec2f(1.0f, 0.0f),
        )

        assertVector(0.0f, 1.0f, 0.0f, basis.normal)
        assertEquals(0.5f, basis.midUv.x, EPSILON)
        assertEquals(0.5f, basis.midUv.y, EPSILON)
        assertEquals(1.0f, basis.tangent.x, EPSILON)
        assertEquals(0.0f, basis.tangent.y, EPSILON)
        assertEquals(0.0f, basis.tangent.z, EPSILON)
        assertEquals(-1.0f, basis.tangent.w, EPSILON)
    }

    @Test
    fun `beacon face basis follows increasing u and height`() {
        val basis = SimpleTextureQuadBasis.calculate(
            position0 = Vec3f(0.0f, 0.0f, 0.0f),
            position1 = Vec3f(1.0f, 0.0f, 0.0f),
            position2 = Vec3f(1.0f, 4.0f, 0.0f),
            uv0 = Vec2f(0.0f, 0.0f),
            uv1 = Vec2f(1.0f, 0.0f),
            uv2 = Vec2f(1.0f, 2.0f),
            uv3 = Vec2f(0.0f, 2.0f),
        )

        assertVector(0.0f, 0.0f, 1.0f, basis.normal)
        assertEquals(0.5f, basis.midUv.x, EPSILON)
        assertEquals(1.0f, basis.midUv.y, EPSILON)
        assertEquals(1.0f, basis.tangent.x, EPSILON)
        assertEquals(0.0f, basis.tangent.y, EPSILON)
        assertEquals(0.0f, basis.tangent.z, EPSILON)
        assertEquals(1.0f, basis.tangent.w, EPSILON)
    }

    @Test
    fun `mirrored flame uv preserves negative tangent handedness`() {
        val basis = SimpleTextureQuadBasis.calculate(
            position0 = Vec3f(0.0f, 0.0f, 0.0f),
            position1 = Vec3f(1.0f, 0.0f, 0.0f),
            position2 = Vec3f(1.0f, 1.0f, 0.0f),
            uv0 = Vec2f(1.0f, 0.0f),
            uv1 = Vec2f(0.0f, 0.0f),
            uv2 = Vec2f(0.0f, 1.0f),
            uv3 = Vec2f(1.0f, 1.0f),
        )

        assertVector(0.0f, 0.0f, 1.0f, basis.normal)
        assertEquals(0.5f, basis.midUv.x, EPSILON)
        assertEquals(0.5f, basis.midUv.y, EPSILON)
        assertEquals(-1.0f, basis.tangent.x, EPSILON)
        assertEquals(0.0f, basis.tangent.y, EPSILON)
        assertEquals(0.0f, basis.tangent.z, EPSILON)
        assertEquals(-1.0f, basis.tangent.w, EPSILON)
    }

    private fun assertVector(x: Float, y: Float, z: Float, actual: Vec3f) {
        assertEquals(x, actual.x, EPSILON)
        assertEquals(y, actual.y, EPSILON)
        assertEquals(z, actual.z, EPSILON)
    }

    private companion object {
        const val EPSILON = 1.0e-6f
    }
}
