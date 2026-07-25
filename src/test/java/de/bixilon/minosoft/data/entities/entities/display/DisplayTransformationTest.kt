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

package de.bixilon.minosoft.data.entities.entities.display

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.entities.EntityRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DisplayTransformationTest {

    @Test
    fun `interpolation clamps vectors and normalizes quaternions`() {
        val start = DisplayTransformation()
        val end = DisplayTransformation(
            translation = Vec3f(2, 4, 6),
            scale = Vec3f(3),
            leftRotation = Vec4f(0, 1, 0, 0),
        )
        val middle = start.interpolate(end, 0.5f)

        assertEquals(Vec3f(1, 2, 3), middle.translation)
        assertEquals(Vec3f(2), middle.scale)
        val length = middle.leftRotation.run { x * x + y * y + z * z + w * w }
        assertEquals(1.0f, length, 0.0001f)
        assertEquals(end, start.interpolate(end, 2.0f))
        assertEquals(start, start.interpolate(end, Float.NaN))
    }

    @Test
    fun `stateful interpolation honors start delay and tick duration`() {
        val state = DisplayTransformationInterpolator()
        val target = DisplayTransformation(translation = Vec3f(4, 0, 0))
        state.target(target, startDelayTicks = 2, durationTicks = 4)

        assertEquals(Vec3f.EMPTY, state.advance(100.milliseconds).translation)
        assertEquals(Vec3f(1, 0, 0), state.advance(50.milliseconds).translation)
        assertEquals(target, state.advance(1_000.milliseconds))
    }

    @Test
    fun `negative interpolation start delta begins partway through the transition`() {
        val state = DisplayTransformationInterpolator()
        state.target(
            DisplayTransformation(translation = Vec3f(4, 0, 0)),
            startDelayTicks = -1,
            durationTicks = 4,
        )

        assertEquals(Vec3f(1, 0, 0), state.advance(0.milliseconds).translation)
    }

    @Test
    fun `shadow radius and strength share display interpolation timing`() {
        val state = DisplayShadowInterpolator(DisplayShadowState(radius = 0.0f, strength = 0.0f))
        state.target(DisplayShadowState(radius = 4.0f, strength = 1.0f), startDelayTicks = 0, durationTicks = 4)

        assertEquals(DisplayShadowState(radius = 2.0f, strength = 0.5f), state.advance(100.milliseconds))
        assertEquals(DisplayShadowState(radius = 4.0f, strength = 1.0f), state.advance(1_000.milliseconds))
    }

    @Test
    fun `text opacity and background channels interpolate together`() {
        val state = DisplayTextStyleInterpolator(DisplayTextStyle(opacity = 0, background = 0x00000000))
        state.target(
            DisplayTextStyle(opacity = 200, background = 0x80402010.toInt()),
            startDelayTicks = 0,
            durationTicks = 4,
        )

        assertEquals(
            DisplayTextStyle(opacity = 100, background = 0x40201008),
            state.advance(100.milliseconds),
        )
    }

    @Test
    fun `teleport interpolation uses shortest yaw path and vanilla duration cap`() {
        val state = DisplayPoseInterpolator(DisplayPose(Vec3d.EMPTY, EntityRotation(170.0f, 0.0f)))
        state.target(
            DisplayPose(Vec3d(59.0, 0.0, 0.0), EntityRotation(-170.0f, 20.0f)),
            durationTicks = 100,
        )

        val afterOneTick = state.advance(50.milliseconds)
        assertEquals(1.0, afterOneTick.position.x, 0.000001)
        assertEquals(20.0f / 59.0f, afterOneTick.rotation.pitch, 0.0001f)
        assertTrue(afterOneTick.rotation.yaw > 170.0f || afterOneTick.rotation.yaw < -170.0f)
        assertEquals(59.0, state.advance(3_000.milliseconds).position.x, 0.000001)
    }
}
