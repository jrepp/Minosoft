/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.light

import de.bixilon.minosoft.data.entities.StatusEffectInstance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vanilla 1.20.4 visual-effect intensity functions consumed by both the native
 * lightmap and Iris-compatible frame state.
 */
object VisionEffectIntensity {
    fun blindness(instance: StatusEffectInstance?): Float {
        instance ?: return 0.0f
        if (instance.infinite) return 1.0f
        return (instance.remaining.ticks / BLINDNESS_FADE_TICKS).coerceIn(0.0f, 1.0f)
    }

    fun nightVision(instance: StatusEffectInstance?, partialTick: Float): Float {
        instance ?: return 0.0f
        if (instance.infinite || instance.remaining.ticks > NIGHT_VISION_FADE_TICKS) return 1.0f
        val phase = (instance.remaining.ticks - partialTick.coerceIn(0.0f, 1.0f)) *
            PI.toFloat() * NIGHT_VISION_FLICKER_RATE
        return (NIGHT_VISION_BASE + sin(phase) * NIGHT_VISION_AMPLITUDE).coerceIn(0.0f, 1.0f)
    }

    fun darknessLightFactor(entityAge: Int, darknessFactor: Float, partialTick: Float): Float {
        val amplitude = DARKNESS_LIGHT_AMPLITUDE * darknessFactor.coerceIn(0.0f, 1.0f)
        val phase = (entityAge - partialTick.coerceIn(0.0f, 1.0f)) * PI.toFloat() * DARKNESS_LIGHT_RATE
        return maxOf(0.0f, cos(phase) * amplitude)
    }

    private const val BLINDNESS_FADE_TICKS = 20.0f
    private const val NIGHT_VISION_FADE_TICKS = 200
    private const val NIGHT_VISION_BASE = 0.7f
    private const val NIGHT_VISION_AMPLITUDE = 0.3f
    private const val NIGHT_VISION_FLICKER_RATE = 0.2f
    private const val DARKNESS_LIGHT_AMPLITUDE = 0.45f
    private const val DARKNESS_LIGHT_RATE = 0.025f
}
