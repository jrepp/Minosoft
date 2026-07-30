/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreaturePreviewRotationStateTest {

    @Test
    fun `idle orbit completes one rotation in twenty seconds`() {
        val rotation = CreaturePreviewRotationState()

        repeat(20 * 20) { rotation.tick() }

        assertTrue(rotation.yaw < 0.0001f || abs(rotation.yaw - CreaturePreviewRotationState.FULL_ROTATION) < 0.0001f)
    }

    @Test
    fun `horizontal dragging adjusts yaw and remains normalized`() {
        val rotation = CreaturePreviewRotationState()

        rotation.drag(50.0f)
        assertTrue(abs(rotation.yaw - 1.0f) < 0.0001f)
        rotation.drag(-100.0f)
        assertTrue(rotation.yaw >= 0.0f && rotation.yaw < CreaturePreviewRotationState.FULL_ROTATION)
    }

    @Test
    fun `invalid drag input cannot poison preview rotation`() {
        val rotation = CreaturePreviewRotationState()

        rotation.drag(Float.NaN)
        rotation.drag(Float.POSITIVE_INFINITY)

        assertTrue(rotation.yaw.isFinite())
    }

    @Test
    fun `default rotation remains fixed until preview drag resumes orbit`() {
        val rotation = CreaturePreviewRotationState(1.5f)

        rotation.showDefault()
        assertTrue(rotation.showingDefault)
        assertEquals(0.0f, rotation.yaw, 0.00001f)
        repeat(40) { rotation.tick() }
        assertEquals(0.0f, rotation.yaw, 0.00001f)

        rotation.beginDrag()
        assertFalse(rotation.showingDefault)
        rotation.drag(10.0f)
        val dragged = rotation.yaw
        rotation.tick()
        assertTrue(rotation.yaw > dragged)
    }
}
