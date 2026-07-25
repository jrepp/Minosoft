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

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Persistent inputs used by EMF's 1.20.4 walking-animation variables.
 *
 * The update constants and interpolation order mirror Mojang's `LimbAnimator`
 * as consumed by EMF 3.0.17.
 */
class CemLimbState {
    private var lastAge = Int.MIN_VALUE
    private var lastPosition: Vec3d? = null
    private var previousSpeed = 0.0
    private var speed = 0.0
    private var position = 0.0

    fun sample(
        age: Int,
        currentPosition: Vec3d,
        partialTick: Float,
        active: Boolean,
        baby: Boolean,
    ): CemLimbSample {
        if (age != lastAge) {
            val previousPosition = lastPosition
            if (previousPosition != null) {
                val x = currentPosition.x - previousPosition.x
                val z = currentPosition.z - previousPosition.z
                val target = min(sqrt(x * x + z * z) * 4.0, 1.0)
                previousSpeed = speed
                speed += (target - speed) * 0.4
                position += speed
            }
            lastPosition = currentPosition
            lastAge = age
        }
        if (!active) return CemLimbSample.ZERO

        val tick = partialTick.coerceIn(0.0f, 1.0f).toDouble()
        val interpolatedSpeed = previousSpeed + (speed - previousSpeed) * tick
        var swing = position - speed * (1.0 - tick)
        if (baby) swing *= 3.0
        return CemLimbSample(swing, interpolatedSpeed)
    }
}

data class CemLimbSample(
    val swing: Double,
    val speed: Double,
) {
    companion object {
        val ZERO = CemLimbSample(0.0, 0.0)
    }
}

internal object CemEntityExpressionMath {
    const val EMF_TICK_WRAP = 27_720L
    const val EMF_DAY_TIME_WRAP = 31_415L

    fun tickValue(value: Long, partialTick: Float, modulus: Long): Double {
        return value.mod(modulus).toDouble() + partialTick.coerceIn(0.0f, 1.0f)
    }

    fun frameCounter(frame: Long): Double = frame.mod(EMF_TICK_WRAP).toDouble()

    fun entityId(id: Int): Double = abs(id.toLong()).mod(EMF_TICK_WRAP).toDouble()

    fun relativeHeadYaw(headYaw: Float, bodyYaw: Float): Double {
        var value = (headYaw - bodyYaw).toDouble().mod(360.0)
        if (value >= 180.0) value -= 360.0
        if (value < -180.0) value += 360.0
        return value
    }

    fun dimension(name: String?): Double = when (name) {
        "minecraft:the_nether" -> -1.0
        "minecraft:the_end" -> 1.0
        else -> 0.0
    }

    fun movement(velocityX: Double, velocityZ: Double, yawDegrees: Float): CemMovementSample {
        val speed = sqrt(velocityX * velocityX + velocityZ * velocityZ)
        if (speed == 0.0) return CemMovementSample.ZERO

        val angle = (90.0 - yawDegrees) * PI / 180.0
        val cosine = cos(angle)
        val sine = sin(angle)
        val forward = -(velocityX * cosine - velocityZ * sine) / speed
        val strafing = -(velocityX * sine + velocityZ * cosine) / speed
        return CemMovementSample(forward, strafing)
    }

    fun wet(
        inWater: Boolean,
        raining: Boolean,
        dimensionWeather: Boolean,
        rainBiome: Boolean,
        skyHeight: Int?,
        feetY: Int,
        eyeY: Int,
    ): Boolean {
        if (inWater) return true
        if (!raining || !dimensionWeather || !rainBiome) return false
        val skyHeight = skyHeight ?: return false
        return feetY >= skyHeight || eyeY >= skyHeight
    }

    fun inGround(
        arrow: Boolean,
        noClip: Boolean,
        velocitySquared: Double,
        collisionBlock: Boolean,
    ): Boolean {
        return arrow && !noClip && collisionBlock && velocitySquared <= MOVEMENT_EPSILON_SQUARED
    }

    private const val MOVEMENT_EPSILON_SQUARED = 1.0E-7
}

data class CemMovementSample(
    val forward: Double,
    val strafing: Double,
) {
    companion object {
        val ZERO = CemMovementSample(0.0, 0.0)
    }
}
