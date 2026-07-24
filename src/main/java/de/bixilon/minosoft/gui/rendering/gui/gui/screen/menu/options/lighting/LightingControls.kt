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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.lighting

import kotlin.math.roundToInt

object LightingControls {
    const val GAMMA_STEP_COUNT = 10
    const val PLAYER_LIGHT_STEP_COUNT = 20
    const val PLAYER_LIGHT_MAXIMUM = 0.3f
    val PLAYER_LIGHT_MAXIMUM_STEP = (PLAYER_LIGHT_MAXIMUM * PLAYER_LIGHT_STEP_COUNT).roundToInt()

    fun adjustGamma(gamma: Float, steps: Int): Float {
        return gammaForStep(gammaStep(gamma) + steps)
    }

    fun gammaStep(gamma: Float): Int {
        if (!gamma.isFinite()) return 0
        return (gamma * GAMMA_STEP_COUNT).roundToInt().coerceIn(0, GAMMA_STEP_COUNT)
    }

    fun gammaForStep(step: Int): Float {
        return step.coerceIn(0, GAMMA_STEP_COUNT) / GAMMA_STEP_COUNT.toFloat()
    }

    fun gammaPercent(gamma: Float): Int {
        if (!gamma.isFinite()) return 0
        return (gamma.coerceIn(0.0f, 1.0f) * 100.0f).roundToInt()
    }

    fun adjustPlayerLight(intensity: Float, steps: Int): Float {
        return playerLightForStep(playerLightStep(intensity) + steps)
    }

    fun playerLightStep(intensity: Float): Int {
        if (!intensity.isFinite()) return 0
        return (intensity * PLAYER_LIGHT_STEP_COUNT).roundToInt().coerceIn(0, PLAYER_LIGHT_MAXIMUM_STEP)
    }

    fun playerLightForStep(step: Int): Float {
        return step.coerceIn(0, PLAYER_LIGHT_MAXIMUM_STEP) / PLAYER_LIGHT_STEP_COUNT.toFloat()
    }

    fun playerLightPercent(intensity: Float): Int {
        if (!intensity.isFinite()) return 0
        return (intensity.coerceIn(0.0f, PLAYER_LIGHT_MAXIMUM) * 100.0f).roundToInt()
    }
}
