/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.data.world.audio

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.registries.biomes.BiomeMoodSettings
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Vanilla 1.20.4's sampled biome mood accumulator plus Iris 1.7.2's constant
 * accumulator. Both consume the same random block probe once per client tick.
 */
class BiomeMoodState(
    private val world: World,
) {
    private val tracker = BiomeMoodTracker()

    @Volatile
    var playerMood: Float = 0.0f
        private set

    @Volatile
    var constantMood: Float = 0.0f
        private set

    fun tick() {
        val player = world.session.player
        val settings = player.physics.positionInfo.biome?.moodSettings
        if (settings == null) {
            reset()
            return
        }
        val extent = settings.blockSearchExtent
        val diameter = extent * 2 + 1
        val position = player.physics.position
        val sampled = BlockPosition(
            floor(position.x + world.random.nextInt(diameter) - extent).toInt()
                .coerceIn(-BlockPosition.MAX_X, BlockPosition.MAX_X),
            floor(player.physics.eyeY + world.random.nextInt(diameter) - extent).toInt()
                .coerceIn(BlockPosition.MIN_Y, BlockPosition.MAX_Y),
            floor(position.z + world.random.nextInt(diameter) - extent).toInt()
                .coerceIn(-BlockPosition.MAX_Z, BlockPosition.MAX_Z),
        )
        val light = world.getLight(sampled)
        val play = tracker.advance(settings, light.sky, light.block)
        playerMood = tracker.playerMood
        constantMood = tracker.constantMood
        if (play) play(settings, sampled, position, player.physics.eyeY)
    }

    fun reset() {
        tracker.reset()
        playerMood = 0.0f
        constantMood = 0.0f
    }

    private fun play(
        settings: BiomeMoodSettings,
        sampled: BlockPosition,
        playerPosition: Vec3d,
        playerEyeY: Double,
    ) {
        val x = sampled.x + 0.5
        val y = sampled.y + 0.5
        val z = sampled.z + 0.5
        val dx = x - playerPosition.x
        val dy = y - playerEyeY
        val dz = z - playerPosition.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance <= 0.0 || !distance.isFinite()) return
        val soundDistance = distance + settings.soundPositionOffset
        world.audio?.play(
            settings.sound,
            Vec3d(
                playerPosition.x + dx / distance * soundDistance,
                playerEyeY + dy / distance * soundDistance,
                playerPosition.z + dz / distance * soundDistance,
            ),
        )
    }
}

internal class BiomeMoodTracker {
    var playerMood: Float = 0.0f
        private set
    var constantMood: Float = 0.0f
        private set

    fun advance(
        settings: BiomeMoodSettings,
        skyLight: Int,
        blockLight: Int,
    ): Boolean {
        require(skyLight in LightLevel.MIN_LEVEL..LightLevel.MAX_LEVEL)
        require(blockLight in LightLevel.MIN_LEVEL..LightLevel.MAX_LEVEL)
        if (skyLight > 0) {
            val recovery = skyLight.toFloat() / LightLevel.MAX_LEVEL * SKY_MOOD_RECOVERY_RATE
            playerMood -= recovery
            constantMood -= recovery
        } else {
            val increase = (1 - blockLight).toFloat() / settings.tickDelay
            playerMood += increase
            constantMood += increase
        }
        constantMood = constantMood.coerceIn(0.0f, 1.0f)
        if (playerMood >= 1.0f) {
            playerMood = 0.0f
            return true
        }
        playerMood = playerMood.coerceAtLeast(0.0f)
        return false
    }

    fun reset() {
        playerMood = 0.0f
        constantMood = 0.0f
    }

    private companion object {
        const val SKY_MOOD_RECOVERY_RATE = 0.001f
    }
}
