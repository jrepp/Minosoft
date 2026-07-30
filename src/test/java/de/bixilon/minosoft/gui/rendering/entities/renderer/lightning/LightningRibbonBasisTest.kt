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

package de.bixilon.minosoft.gui.rendering.entities.renderer.lightning

import de.bixilon.kmath.vec.vec3.f.Vec3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LightningRibbonBasisTest {

    @Test
    fun `vertical crossed ribbons retain their two authored face normals`() {
        val basis = LightningRibbonBasis.calculate(0.0f, 2.0f, 0.0f)

        assertVector(0.0f, 0.0f, 1.0f, basis.xRibbonNormal)
        assertVector(-1.0f, 0.0f, 0.0f, basis.zRibbonNormal)
    }

    @Test
    fun `jagged crossed ribbon normals remain normalized and perpendicular`() {
        val direction = Vec3f(0.6f, 2.0f, -0.4f)
        val basis = LightningRibbonBasis.calculate(direction.x, direction.y, direction.z)

        assertEquals(1.0f, basis.xRibbonNormal.length(), EPSILON)
        assertEquals(1.0f, basis.zRibbonNormal.length(), EPSILON)
        assertEquals(0.0f, dot(basis.xRibbonNormal, Vec3f(1.0f, 0.0f, 0.0f)), EPSILON)
        assertEquals(0.0f, dot(basis.zRibbonNormal, Vec3f(0.0f, 0.0f, 1.0f)), EPSILON)
        assertEquals(0.0f, dot(basis.xRibbonNormal, direction), EPSILON)
        assertEquals(0.0f, dot(basis.zRibbonNormal, direction), EPSILON)
    }

    private fun dot(left: Vec3f, right: Vec3f): Float =
        left.x * right.x + left.y * right.y + left.z * right.z

    private fun assertVector(x: Float, y: Float, z: Float, actual: Vec3f) {
        assertEquals(x, actual.x, EPSILON)
        assertEquals(y, actual.y, EPSILON)
        assertEquals(z, actual.z, EPSILON)
    }

    private companion object {
        const val EPSILON = 1.0e-6f
    }
}
