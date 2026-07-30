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
import de.bixilon.minosoft.data.registries.effects.vision.VisionEffect
import de.bixilon.minosoft.protocol.network.session.play.tick.Ticks.Companion.ticks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusEffectFactorCalculationDataTest {
    @Test
    fun `infinite effects remain active and tick their factor state`() {
        val effect = StatusEffectInstance(
            VisionEffect.Darkness,
            amplifier = 0,
            duration = StatusEffectInstance.INFINITE_DURATION.ticks,
        )

        repeat(23) { effect.tick() }

        assertTrue(effect.infinite)
        assertFalse(effect.expired)
        assertEquals(StatusEffectInstance.INFINITE_DURATION, effect.remaining.ticks)
        assertEquals(1.0f, effect.factorCalculationData!!.interpolate(1.0f))
    }

    @Test
    fun `darkness factor follows the serialized twenty two tick transition`() {
        val effect = StatusEffectInstance(VisionEffect.Darkness, amplifier = 0, duration = 100.ticks)

        effect.tick()
        effect.tick()

        assertEquals(1.0f / 44.0f, effect.factorCalculationData!!.interpolate(0.5f), 1.0e-6f)

        repeat(76) { effect.tick() }
        assertEquals(1.0f, effect.factorCalculationData!!.interpolate(1.0f), 1.0e-6f)

        effect.tick()
        assertEquals(1.0f - 1.0f / 44.0f, effect.factorCalculationData!!.interpolate(0.5f), 1.0e-6f)
    }

    @Test
    fun `serialized factor state resumes without resetting`() {
        val data: JsonObject = mapOf(
            "padding_duration" to 22,
            "factor_start" to 0.25f,
            "factor_target" to 1.0f,
            "factor_current" to 0.5f,
            "ticks_active" to 7,
            "factor_previous_frame" to 0.4f,
            "had_effect_last_tick" to true,
        )

        val factor = StatusEffectFactorCalculationData.fromJson(data)!!

        assertEquals(0.45f, factor.interpolate(0.5f), 1.0e-6f)
    }

    @Test
    fun `serialized factor state rejects invalid numeric values`() {
        assertFailsWith<IllegalArgumentException> {
            StatusEffectFactorCalculationData.fromJson(
                mapOf("padding_duration" to 22, "factor_current" to Float.NaN),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StatusEffectFactorCalculationData.fromJson(
                mapOf("padding_duration" to 22, "ticks_active" to -1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StatusEffectFactorCalculationData.fromJson(
                mapOf("padding_duration" to Double.NaN),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StatusEffectFactorCalculationData(22).interpolate(Float.POSITIVE_INFINITY)
        }
    }
}
