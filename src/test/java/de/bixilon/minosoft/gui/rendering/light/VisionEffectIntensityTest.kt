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
import de.bixilon.minosoft.data.registries.effects.vision.VisionEffect
import de.bixilon.minosoft.protocol.network.session.play.tick.Ticks.Companion.ticks
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class VisionEffectIntensityTest {
    @Test
    fun `blindness uses Iris duration fade and infinite sentinel`() {
        val fading = StatusEffectInstance(VisionEffect.Blindness, 0, 10.ticks)
        val infinite = StatusEffectInstance(VisionEffect.Blindness, 0, (-1).ticks)

        assertEquals(0.5f, VisionEffectIntensity.blindness(fading))
        assertEquals(1.0f, VisionEffectIntensity.blindness(infinite))
        assertEquals(0.0f, VisionEffectIntensity.blindness(null))
    }

    @Test
    fun `night vision matches vanilla tick phase`() {
        val fading = StatusEffectInstance(VisionEffect.NightVision, 0, 100.ticks)
        val expected = 0.7f + sin((100.0f - 0.25f) * PI.toFloat() * 0.2f) * 0.3f

        assertEquals(expected, VisionEffectIntensity.nightVision(fading, 0.25f), 1.0e-6f)
        assertEquals(
            1.0f,
            VisionEffectIntensity.nightVision(
                StatusEffectInstance(VisionEffect.NightVision, 0, 201.ticks),
                0.25f,
            ),
        )
    }

    @Test
    fun `darkness light pulse matches vanilla lightmap gamma`() {
        val expected = maxOf(0.0f, cos((40.0f - 0.5f) * PI.toFloat() * 0.025f) * 0.45f * 0.8f)

        assertEquals(expected, VisionEffectIntensity.darknessLightFactor(40, 0.8f, 0.5f), 1.0e-6f)
    }
}
