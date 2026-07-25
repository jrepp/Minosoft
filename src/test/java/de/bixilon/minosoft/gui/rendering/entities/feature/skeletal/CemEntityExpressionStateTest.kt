/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.Vec3d
import kotlin.test.Test
import kotlin.test.assertEquals

class CemEntityExpressionStateTest {

    @Test
    fun `limb state follows vanilla update and partial tick interpolation`() {
        val state = CemLimbState()

        assertEquals(CemLimbSample.ZERO, state.sample(0, Vec3d.EMPTY, 0.0f, active = true, baby = false))

        val moving = state.sample(1, Vec3d(0.25, 0.0, 0.0), 0.5f, active = true, baby = false)
        assertEquals(0.2, moving.swing, 0.000001)
        assertEquals(0.2, moving.speed, 0.000001)

        // Multiple render frames in the same entity tick must not advance the animator.
        assertEquals(moving, state.sample(1, Vec3d(1.0, 0.0, 0.0), 0.5f, active = true, baby = false))

        val stoppedBaby = state.sample(2, Vec3d(0.25, 0.0, 0.0), 1.0f, active = true, baby = true)
        assertEquals(1.92, stoppedBaby.swing, 0.000001)
        assertEquals(0.24, stoppedBaby.speed, 0.000001)
    }

    @Test
    fun `riding state exposes zero limb values`() {
        val state = CemLimbState()
        state.sample(0, Vec3d.EMPTY, 0.0f, active = true, baby = false)

        assertEquals(
            CemLimbSample.ZERO,
            state.sample(1, Vec3d(1.0, 0.0, 0.0), 1.0f, active = false, baby = false),
        )
    }

    @Test
    fun `EMF counters identifiers dimensions and head angles match pinned semantics`() {
        assertEquals(5.25, CemEntityExpressionMath.tickValue(27_725, 0.25f, 27_720))
        assertEquals(2.0, CemEntityExpressionMath.frameCounter(27_722))
        assertEquals(7.0, CemEntityExpressionMath.entityId(-27_727))
        assertEquals(-1.0, CemEntityExpressionMath.dimension("minecraft:the_nether"))
        assertEquals(1.0, CemEntityExpressionMath.dimension("minecraft:the_end"))
        assertEquals(0.0, CemEntityExpressionMath.dimension("example:custom"))
        assertEquals(20.0, CemEntityExpressionMath.relativeHeadYaw(-170.0f, 170.0f))
        assertEquals(-20.0, CemEntityExpressionMath.relativeHeadYaw(170.0f, -170.0f))
    }

    @Test
    fun `movement projection follows entity yaw`() {
        val forward = CemEntityExpressionMath.movement(0.0, 1.0, 0.0f)
        assertEquals(1.0, forward.forward, 0.000001)
        assertEquals(0.0, forward.strafing, 0.000001)

        val strafe = CemEntityExpressionMath.movement(1.0, 0.0, 0.0f)
        assertEquals(0.0, strafe.forward, 0.000001)
        assertEquals(-1.0, strafe.strafing, 0.000001)
        assertEquals(CemMovementSample.ZERO, CemEntityExpressionMath.movement(0.0, 0.0, 90.0f))
    }

    @Test
    fun `wet state requires water or exposed rain in a rain biome`() {
        assertEquals(true, CemEntityExpressionMath.wet(true, false, false, false, null, 0, 0))
        assertEquals(true, CemEntityExpressionMath.wet(false, true, true, true, 64, 63, 65))
        assertEquals(false, CemEntityExpressionMath.wet(false, true, true, true, 64, 62, 63))
        assertEquals(false, CemEntityExpressionMath.wet(false, true, true, false, 64, 65, 66))
        assertEquals(false, CemEntityExpressionMath.wet(false, true, false, true, 64, 65, 66))
        assertEquals(false, CemEntityExpressionMath.wet(false, true, true, true, null, 65, 66))
    }

    @Test
    fun `in-ground state requires a stopped colliding arrow`() {
        assertEquals(true, CemEntityExpressionMath.inGround(true, false, 0.0, true))
        assertEquals(false, CemEntityExpressionMath.inGround(true, true, 0.0, true))
        assertEquals(false, CemEntityExpressionMath.inGround(true, false, 1.0, true))
        assertEquals(false, CemEntityExpressionMath.inGround(true, false, 0.0, false))
        assertEquals(false, CemEntityExpressionMath.inGround(false, false, 0.0, true))
    }
}
