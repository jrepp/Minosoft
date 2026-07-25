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
import de.bixilon.minosoft.data.entities.EntityRotation.Companion.interpolateYaw
import de.bixilon.minosoft.protocol.network.session.play.tick.TickUtil
import kotlin.time.Duration
import kotlin.math.sqrt

data class DisplayTransformation(
    val translation: Vec3f = Vec3f.EMPTY,
    val scale: Vec3f = Vec3f(1.0f),
    val leftRotation: Vec4f = DisplayEntity.IDENTITY_ROTATION,
    val rightRotation: Vec4f = DisplayEntity.IDENTITY_ROTATION,
) {
    fun interpolate(target: DisplayTransformation, delta: Float): DisplayTransformation {
        val t = if (delta.isFinite()) delta.coerceIn(0.0f, 1.0f) else 0.0f
        return DisplayTransformation(
            translation = lerp(translation, target.translation, t),
            scale = lerp(scale, target.scale, t),
            leftRotation = nlerp(leftRotation, target.leftRotation, t),
            rightRotation = nlerp(rightRotation, target.rightRotation, t),
        )
    }

    private fun lerp(from: Vec3f, to: Vec3f, delta: Float) = Vec3f(
        from.x + (to.x - from.x) * delta,
        from.y + (to.y - from.y) * delta,
        from.z + (to.z - from.z) * delta,
    )

    private fun nlerp(from: Vec4f, to: Vec4f, delta: Float): Vec4f {
        val dot = from.x * to.x + from.y * to.y + from.z * to.z + from.w * to.w
        val sign = if (dot < 0.0f) -1.0f else 1.0f
        val x = from.x + (to.x * sign - from.x) * delta
        val y = from.y + (to.y * sign - from.y) * delta
        val z = from.z + (to.z * sign - from.z) * delta
        val w = from.w + (to.w * sign - from.w) * delta
        val length = sqrt(x * x + y * y + z * z + w * w)
        if (!length.isFinite() || length <= 0.000001f) return DisplayEntity.IDENTITY_ROTATION
        return Vec4f(x / length, y / length, z / length, w / length)
    }
}

class DisplayTransformationInterpolator(initial: DisplayTransformation = DisplayTransformation()) {
    private var start = initial
    private var target = initial
    private var elapsedTicks = 0.0f
    private var startDelayTicks = 0
    private var durationTicks = 0

    fun target(value: DisplayTransformation, startDelayTicks: Int, durationTicks: Int) {
        if (value == target && startDelayTicks == this.startDelayTicks && durationTicks == this.durationTicks) return
        start = current()
        target = value
        elapsedTicks = 0.0f
        this.startDelayTicks = startDelayTicks
        this.durationTicks = durationTicks.coerceAtLeast(0)
    }

    fun advance(delta: Duration): DisplayTransformation {
        elapsedTicks += delta.inWholeNanoseconds / 1_000_000_000.0f * TickUtil.TICKS_PER_SECOND
        return current()
    }

    fun current(): DisplayTransformation {
        if (durationTicks <= 0) return target
        val progress = ((elapsedTicks - startDelayTicks) / durationTicks).coerceIn(0.0f, 1.0f)
        return start.interpolate(target, progress)
    }
}

data class DisplayShadowState(
    val radius: Float = 0.0f,
    val strength: Float = 1.0f,
) {
    fun interpolate(target: DisplayShadowState, delta: Float): DisplayShadowState {
        val t = delta.finiteProgress()
        return DisplayShadowState(
            radius = radius + (target.radius - radius) * t,
            strength = strength + (target.strength - strength) * t,
        )
    }
}

class DisplayShadowInterpolator(initial: DisplayShadowState = DisplayShadowState()) {
    private val state = DisplayTimedInterpolator(initial, DisplayShadowState::interpolate)

    fun target(value: DisplayShadowState, startDelayTicks: Int, durationTicks: Int) =
        state.target(value, startDelayTicks, durationTicks)

    fun advance(delta: Duration) = state.advance(delta)
    fun current() = state.current()
}

data class DisplayTextStyle(
    val opacity: Int = -1,
    val background: Int = 0x40000000,
) {
    fun interpolate(target: DisplayTextStyle, delta: Float): DisplayTextStyle {
        val t = delta.finiteProgress()
        return DisplayTextStyle(
            opacity = lerpInt(opacity, target.opacity, t),
            background = lerpArgb(background, target.background, t),
        )
    }

    private fun lerpArgb(from: Int, to: Int, delta: Float): Int {
        val alpha = lerpInt(from ushr 24 and 0xFF, to ushr 24 and 0xFF, delta)
        val red = lerpInt(from ushr 16 and 0xFF, to ushr 16 and 0xFF, delta)
        val green = lerpInt(from ushr 8 and 0xFF, to ushr 8 and 0xFF, delta)
        val blue = lerpInt(from and 0xFF, to and 0xFF, delta)
        return alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }

    private fun lerpInt(from: Int, to: Int, delta: Float) = (from + delta * (to - from)).toInt()
}

class DisplayTextStyleInterpolator(initial: DisplayTextStyle = DisplayTextStyle()) {
    private val state = DisplayTimedInterpolator(initial, DisplayTextStyle::interpolate)

    fun target(value: DisplayTextStyle, startDelayTicks: Int, durationTicks: Int) =
        state.target(value, startDelayTicks, durationTicks)

    fun advance(delta: Duration) = state.advance(delta)
    fun current() = state.current()
}

data class DisplayPose(
    val position: Vec3d,
    val rotation: EntityRotation,
) {
    fun interpolate(target: DisplayPose, delta: Float): DisplayPose {
        val t = delta.finiteProgress()
        return DisplayPose(
            position = Vec3d(
                position.x + (target.position.x - position.x) * t,
                position.y + (target.position.y - position.y) * t,
                position.z + (target.position.z - position.z) * t,
            ),
            rotation = EntityRotation(
                interpolateYaw(t, rotation.yaw, target.rotation.yaw),
                rotation.pitch + (target.rotation.pitch - rotation.pitch) * t,
            ),
        )
    }
}

class DisplayPoseInterpolator(initial: DisplayPose) {
    private val state = DisplayTimedInterpolator(initial, DisplayPose::interpolate)

    fun target(value: DisplayPose, durationTicks: Int) = state.target(value, 0, durationTicks.coerceIn(0, MAX_TELEPORT_DURATION))
    fun advance(delta: Duration) = state.advance(delta)
    fun current() = state.current()

    private companion object {
        const val MAX_TELEPORT_DURATION = 59
    }
}

private class DisplayTimedInterpolator<T>(
    initial: T,
    private val interpolate: (T, T, Float) -> T,
) {
    private var start = initial
    private var target = initial
    private var elapsedTicks = 0.0f
    private var startDelayTicks = 0
    private var durationTicks = 0

    fun target(value: T, startDelayTicks: Int, durationTicks: Int) {
        if (value == target && startDelayTicks == this.startDelayTicks && durationTicks == this.durationTicks) return
        start = current()
        target = value
        elapsedTicks = 0.0f
        this.startDelayTicks = startDelayTicks
        this.durationTicks = durationTicks.coerceAtLeast(0)
    }

    fun advance(delta: Duration): T {
        elapsedTicks += delta.inWholeNanoseconds / 1_000_000_000.0f * TickUtil.TICKS_PER_SECOND
        return current()
    }

    fun current(): T {
        if (durationTicks <= 0) return target
        val progress = ((elapsedTicks - startDelayTicks) / durationTicks).finiteProgress()
        return interpolate(start, target, progress)
    }
}

private fun Float.finiteProgress(): Float = if (isFinite()) coerceIn(0.0f, 1.0f) else 0.0f
