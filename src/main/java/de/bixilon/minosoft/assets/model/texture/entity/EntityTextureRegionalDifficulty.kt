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

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.minosoft.data.world.difficulty.Difficulties

/**
 * Minecraft 1.20.4 LocalDifficulty#getLocalDifficulty expressed without
 * Mojang runtime classes. Remote clients do not receive chunk inhabited time,
 * so Minosoft passes the same client-visible default of zero.
 */
object EntityTextureRegionalDifficulty {
    fun calculate(
        difficulty: Difficulties,
        worldAge: Long,
        inhabitedTime: Long,
        moonSize: Float,
    ): Double {
        require(moonSize.isFinite()) { "Regional-difficulty moon size must be finite." }
        if (difficulty == Difficulties.PEACEFUL) return 0.0

        val hard = difficulty == Difficulties.HARD
        val worldFactor = ((worldAge.toDouble() - 72_000.0) / 1_440_000.0)
            .coerceIn(0.0, 1.0) * 0.25
        var localFactor = (inhabitedTime.toDouble() / 3_600_000.0)
            .coerceIn(0.0, 1.0) * if (hard) 1.0 else 0.75
        localFactor += (moonSize * 0.25).coerceIn(0.0, worldFactor)
        if (difficulty == Difficulties.EASY) localFactor *= 0.5

        val difficultyLevel = when (difficulty) {
            Difficulties.PEACEFUL -> 0
            Difficulties.EASY -> 1
            Difficulties.NORMAL -> 2
            Difficulties.HARD -> 3
        }
        return difficultyLevel * (0.75 + worldFactor + localFactor)
    }
}
