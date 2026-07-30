/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.framebuffer.world.overlay.overlays.simple

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaterOverlayTest {
    @Test
    fun `shader pack can suppress the underwater overlay`() {
        assertTrue(shouldRenderWaterOverlay(spectator = false, eyeInWater = true, shaderAllowsOverlay = true))
        assertFalse(shouldRenderWaterOverlay(spectator = false, eyeInWater = true, shaderAllowsOverlay = false))
    }

    @Test
    fun `spectators and players outside water do not render the underwater overlay`() {
        assertFalse(shouldRenderWaterOverlay(spectator = true, eyeInWater = true, shaderAllowsOverlay = true))
        assertFalse(shouldRenderWaterOverlay(spectator = false, eyeInWater = false, shaderAllowsOverlay = true))
    }
}
