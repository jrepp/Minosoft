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

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.BlockPosition
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.random.Random

/**
 * Render-context-owned state for legacy predicates whose vanilla providers
 * smooth values between game ticks. No GPU objects or content generations are
 * retained here.
 */
class ItemPredicateRuntime(
    randomSeed: Long = DEFAULT_RANDOM_SEED,
) {
    private val random = Random(randomSeed)
    private val clock = WrappedInterpolator(damping = 0.9)
    private val compasses = mutableMapOf<ResourceLocation, CompassState>()

    @Synchronized
    fun clock(worldAge: Long, dayTime: Int, natural: Boolean): Float {
        val target = if (natural) skyAngle(dayTime) else random.nextDouble()
        return clock.update(worldAge, target).toFloat()
    }

    @Synchronized
    fun compass(
        provider: ResourceLocation,
        entity: Entity,
        target: BlockPosition?,
        targetDimension: ResourceLocation?,
        worldAge: Long,
        seed: Int,
    ): Float {
        val state = compasses.getOrPut(provider) { CompassState() }
        val worldDimension = entity.session.world.name
        if (
            target == null ||
            targetDimension == null ||
            targetDimension != worldDimension ||
            target.squaredDistanceTo(entity.physics.position) < MIN_TARGET_DISTANCE_SQUARED
        ) {
            val value = state.aimless.update(worldAge, random.nextDouble())
            return floorMod(value + scatter(seed).toDouble() / Int.MAX_VALUE, 1.0).toFloat()
        }

        val targetAngle = angleTo(entity.physics.position, target)
        val bodyYaw = floorMod(entity.physics.rotation.yaw / 360.0, 1.0)
        val result = if (entity === entity.session.player) {
            targetAngle + state.aimed.update(worldAge, 0.5 - (bodyYaw - 0.25))
        } else {
            0.5 - (bodyYaw - 0.25) - targetAngle
        }
        return floorMod(result, 1.0).toFloat()
    }

    private fun skyAngle(dayTime: Int): Double {
        val fraction = floorMod(dayTime / 24_000.0 - 0.25, 1.0)
        val eased = 0.5 - cos(fraction * PI) / 2.0
        return (fraction * 2.0 + eased) / 3.0
    }

    private fun angleTo(position: Vec3d, target: BlockPosition): Double =
        atan2(target.z + 0.5 - position.z, target.x + 0.5 - position.x) / (2.0 * PI)

    private fun BlockPosition.squaredDistanceTo(position: Vec3d): Double {
        val dx = x + 0.5 - position.x
        val dy = y + 0.5 - position.y
        val dz = z + 0.5 - position.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun scatter(seed: Int): Int = seed * COMPASS_SCATTER_MULTIPLIER

    private fun floorMod(value: Double, modulus: Double): Double {
        val remainder = value % modulus
        return if (remainder < 0.0) remainder + modulus else remainder
    }

    private class CompassState {
        val aimed = WrappedInterpolator(damping = 0.8)
        val aimless = WrappedInterpolator(damping = 0.8)
    }

    private class WrappedInterpolator(
        private val damping: Double,
    ) {
        private var value = 0.0
        private var speed = 0.0
        private var lastTick = Long.MIN_VALUE

        fun update(tick: Long, target: Double): Double {
            if (tick == lastTick) return value
            lastTick = tick
            var delta = floorMod(target - value + 0.5, 1.0) - 0.5
            speed += delta * 0.1
            speed *= damping
            value = floorMod(value + speed, 1.0)
            return value
        }

        private fun floorMod(value: Double, modulus: Double): Double {
            val remainder = value % modulus
            return if (remainder < 0.0) remainder + modulus else remainder
        }
    }

    private companion object {
        const val DEFAULT_RANDOM_SEED = 0x4D494E4F534F4654L
        const val COMPASS_SCATTER_MULTIPLIER = 1_327_217_883
        const val MIN_TARGET_DISTANCE_SQUARED = 0.00001
    }
}
