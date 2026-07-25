/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeckoLibEasingRegistryTest {
    @Test
    fun `custom easing is owner scoped and receives its first argument`() {
        val registration = GeckoLibEasingRegistry.register("test-owner", "test:amplify") { value, argument ->
            value * (argument ?: 1.0)
        }
        try {
            assertEquals(mapOf("test:amplify" to "test-owner"), GeckoLibEasingRegistry.owners())
            assertEquals(
                0.75f,
                SkeletalAnimationEvaluator.ease(
                    "test:amplify",
                    0.25f,
                    listOf(3.0f),
                    GeckoLibEasingRegistry,
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                GeckoLibEasingRegistry.register("other", "test:amplify") { value, _ -> value }
            }
        } finally {
            registration.close()
        }

        assertEquals(emptyMap(), GeckoLibEasingRegistry.owners())
        assertEquals(
            0.25f,
            SkeletalAnimationEvaluator.ease(
                "test:amplify",
                0.25f,
                easingResolver = GeckoLibEasingRegistry,
            ),
        )
    }

    @Test
    fun `built ins cannot be replaced and non finite custom output is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GeckoLibEasingRegistry.register("test-owner", "ease_in_quad") { value, _ -> value }
        }
        val registration = GeckoLibEasingRegistry.register("test-owner", "test:invalid") { _, _ -> Double.NaN }
        try {
            assertFailsWith<IllegalArgumentException> {
                SkeletalAnimationEvaluator.ease(
                    "test:invalid",
                    0.5f,
                    easingResolver = GeckoLibEasingRegistry,
                )
            }
        } finally {
            registration.close()
        }

        val outOfRange = GeckoLibEasingRegistry.register("test-owner", "test:out_of_range") { _, _ ->
            Double.MAX_VALUE
        }
        try {
            assertFailsWith<IllegalArgumentException> {
                GeckoLibEasingRegistry.transform("test:out_of_range", 0.5f, emptyList())
            }
        } finally {
            outOfRange.close()
        }
    }
}
