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

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import kotlin.math.sqrt
import de.bixilon.minosoft.protocol.network.session.play.tick.TickUtil
import kotlin.time.Duration

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
        this.startDelayTicks = startDelayTicks.coerceAtLeast(0)
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
