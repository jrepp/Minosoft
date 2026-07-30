/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.data.entities

import de.bixilon.kutil.json.JsonObject

/**
 * Client-side transition state carried by modern status-effect packets.
 *
 * Mojang uses this for Darkness's 22-tick fade in/out. Keeping it on the
 * effect instance lets the lightmap, fog, and shader-pack paths observe one
 * tick-pinned value.
 */
class StatusEffectFactorCalculationData(
    val paddingDuration: Int,
    private var factorStart: Float = 0.0f,
    private var factorTarget: Float = 1.0f,
    private var factorCurrent: Float = 0.0f,
    private var ticksActive: Int = 0,
    private var factorPreviousFrame: Float = 0.0f,
    private var hadEffectLastTick: Boolean = false,
) {
    init {
        require(paddingDuration >= 0) { "Status-effect factor padding must be non-negative" }
        require(ticksActive >= 0) { "Status-effect factor ticks must be non-negative" }
        require(factorStart.isUnitFactor()) { "Status-effect start factor must be finite and between zero and one" }
        require(factorTarget.isUnitFactor()) { "Status-effect target factor must be finite and between zero and one" }
        require(factorCurrent.isUnitFactor()) { "Status-effect current factor must be finite and between zero and one" }
        require(factorPreviousFrame.isUnitFactor()) {
            "Status-effect previous factor must be finite and between zero and one"
        }
    }

    fun update(instance: StatusEffectInstance) {
        factorPreviousFrame = factorCurrent
        val hasEffect = instance.infinite || instance.remaining.ticks > paddingDuration
        if (hadEffectLastTick != hasEffect) {
            hadEffectLastTick = hasEffect
            ticksActive = 0
            factorStart = factorCurrent
            factorTarget = if (hasEffect) 1.0f else 0.0f
        } else if (ticksActive < paddingDuration) {
            ticksActive++
        }
        val progress = if (paddingDuration == 0) 1.0f else {
            (ticksActive.toFloat() / paddingDuration).coerceIn(0.0f, 1.0f)
        }
        factorCurrent = factorStart + (factorTarget - factorStart) * progress
    }

    fun interpolate(partialTick: Float): Float {
        require(partialTick.isFinite()) { "Status-effect partial tick must be finite" }
        val delta = partialTick.coerceIn(0.0f, 1.0f)
        return factorPreviousFrame + (factorCurrent - factorPreviousFrame) * delta
    }

    companion object {
        fun fromJson(data: JsonObject): StatusEffectFactorCalculationData? {
            val paddingDuration = (data["padding_duration"] as? Number)
                ?.toNonNegativeInt("padding_duration")
                ?: return null
            return StatusEffectFactorCalculationData(
                paddingDuration = paddingDuration,
                factorStart = (data["factor_start"] as? Number)?.toFloat() ?: 0.0f,
                factorTarget = (data["factor_target"] as? Number)?.toFloat() ?: 1.0f,
                factorCurrent = (data["factor_current"] as? Number)?.toFloat() ?: 0.0f,
                ticksActive = (data["ticks_active"] as? Number)
                    ?.toNonNegativeInt("ticks_active")
                    ?: 0,
                factorPreviousFrame = (data["factor_previous_frame"] as? Number)?.toFloat() ?: 0.0f,
                hadEffectLastTick = data["had_effect_last_tick"] as? Boolean ?: false,
            )
        }

        private fun Float.isUnitFactor(): Boolean = isFinite() && this in 0.0f..1.0f

        private fun Number.toNonNegativeInt(field: String): Int {
            val value = toDouble()
            require(value.isFinite() && value % 1.0 == 0.0 && value in 0.0..Int.MAX_VALUE.toDouble()) {
                "Status-effect $field must be a non-negative integer"
            }
            return value.toInt()
        }
    }
}
