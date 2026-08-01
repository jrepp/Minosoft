/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.diagnostic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerrainAcceptanceFaultTest {
    private val scope = TerrainAcceptanceFaultScope(1, 2, 3, 4)

    @Test
    fun `fault is one shot and restoration token disarms it`() {
        val controller = TerrainAcceptanceFaultController()
        val armed = controller.arm(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD, scope)
        assertTrue(armed.active)
        assertTrue(controller.isArmed(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD))
        val token = checkNotNull(armed.restorationToken)

        assertFailsWith<IllegalArgumentException> {
            controller.restore("$token-invalid", scope)
        }
        assertTrue(controller.snapshot().active)
        assertFalse(controller.restore(token, scope).active)
        assertFalse(controller.isArmed(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD))
    }

    @Test
    fun `fault consumption cannot repeat`() {
        val controller = TerrainAcceptanceFaultController()
        controller.arm(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD, scope)

        assertTrue(controller.consume(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD, scope))
        assertFalse(controller.consume(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD, scope))
        assertEquals(1L, controller.snapshot().consumedCount)
    }

    @Test
    fun `generation replacement invalidates an armed fault`() {
        val controller = TerrainAcceptanceFaultController()
        controller.arm(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD, scope)

        val replacement = scope.copy(worldEpoch = 2)
        assertFalse(controller.consume(TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD, replacement))
        assertFalse(controller.snapshot().active)
        assertEquals(1L, controller.snapshot().invalidatedCount)
    }
}
