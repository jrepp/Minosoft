/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.audio

import kotlin.math.roundToInt

enum class AudioEngineStatus(val translationKey: String) {
    READY("menu.audio.engine.ready"),
    STARTING("menu.audio.engine.starting"),
    RESTART_REQUIRED("menu.audio.engine.restart_required"),
}

object AudioControls {
    const val VOLUME_STEP_COUNT = 10

    fun adjustVolume(volume: Float, steps: Int): Float {
        return volumeForStep(volumeStep(volume) + steps)
    }

    fun volumeStep(volume: Float): Int {
        if (!volume.isFinite()) return 0
        return (volume * VOLUME_STEP_COUNT).roundToInt().coerceIn(0, VOLUME_STEP_COUNT)
    }

    fun volumeForStep(step: Int): Float {
        return step.coerceIn(0, VOLUME_STEP_COUNT) / VOLUME_STEP_COUNT.toFloat()
    }

    fun volumePercent(volume: Float): Int {
        if (!volume.isFinite()) return 0
        return (volume.coerceIn(0.0f, 1.0f) * 100.0f).roundToInt()
    }

    fun engineStatus(skipLoading: Boolean, initialized: Boolean): AudioEngineStatus {
        if (initialized) return AudioEngineStatus.READY
        if (skipLoading) return AudioEngineStatus.RESTART_REQUIRED
        return AudioEngineStatus.STARTING
    }
}
