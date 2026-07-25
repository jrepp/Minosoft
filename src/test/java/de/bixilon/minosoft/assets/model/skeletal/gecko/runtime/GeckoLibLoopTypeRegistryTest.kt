/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GeckoLibLoopTypeRegistryTest {
    @Test
    fun `registration is owner scoped duplicate safe and removable`() {
        val context = GeckoLibLoopContext(
            controller = "main",
            animation = "idle",
            state = GeckoLibAnimationState(1.0f),
            completedCycles = 2,
        )
        val registration = GeckoLibLoopTypeRegistry.register("test-owner", "test:conditional") {
            if (it.completedCycles == 2) GeckoLibLoopDecision.HOLD else GeckoLibLoopDecision.ADVANCE
        }
        try {
            assertEquals(mapOf("test:conditional" to "test-owner"), GeckoLibLoopTypeRegistry.owners())
            assertEquals(GeckoLibLoopDecision.HOLD, GeckoLibLoopTypeRegistry.decide("test:conditional", context))
            assertFailsWith<IllegalArgumentException> {
                GeckoLibLoopTypeRegistry.register("other", "test:conditional") { GeckoLibLoopDecision.REPEAT }
            }
        } finally {
            registration.close()
        }

        assertEquals(emptyMap(), GeckoLibLoopTypeRegistry.owners())
        assertNull(GeckoLibLoopTypeRegistry.decide("test:conditional", context))
    }

    @Test
    fun `built in loop types cannot be replaced`() {
        for (name in listOf("default", "false", "play_once", "hold_on_last_frame", "true", "loop")) {
            assertFailsWith<IllegalArgumentException> {
                GeckoLibLoopTypeRegistry.register("test-owner", name) { GeckoLibLoopDecision.ADVANCE }
            }
        }
    }
}
