/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.lighting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LightingControlsTest {

    @Test
    fun `brightness increases in stable ten percent steps`() {
        assertEquals(0.6f, LightingControls.adjustGamma(0.5f, 1))
        assertEquals(60, LightingControls.gammaPercent(LightingControls.adjustGamma(0.5f, 1)))
    }

    @Test
    fun `brightness is clamped to supported profile range`() {
        assertEquals(0.0f, LightingControls.adjustGamma(0.0f, -1))
        assertEquals(1.0f, LightingControls.adjustGamma(1.0f, 1))
    }

    @Test
    fun `brightness rounds imported values onto menu steps`() {
        assertEquals(0.4f, LightingControls.adjustGamma(0.34f, 1))
    }

    @Test
    fun `brightness step conversion is reversible`() {
        assertEquals(3, LightingControls.gammaStep(0.34f))
        assertEquals(0.3f, LightingControls.gammaForStep(3))
        assertEquals(0.0f, LightingControls.gammaForStep(-1))
        assertEquals(1.0f, LightingControls.gammaForStep(11))
    }

    @Test
    fun `player light uses bounded five percent steps`() {
        assertEquals(0.2f, LightingControls.adjustPlayerLight(0.15f, 1))
        assertEquals(20, LightingControls.playerLightPercent(0.2f))
        assertEquals(0.0f, LightingControls.adjustPlayerLight(0.0f, -1))
        assertEquals(0.3f, LightingControls.adjustPlayerLight(0.3f, 1))
    }

    @Test
    fun `player light step conversion is reversible`() {
        assertEquals(6, LightingControls.PLAYER_LIGHT_MAXIMUM_STEP)
        assertEquals(3, LightingControls.playerLightStep(0.15f))
        assertEquals(0.15f, LightingControls.playerLightForStep(3))
        assertEquals(0.3f, LightingControls.playerLightForStep(7))
    }

    @Test
    fun `non-finite profile lighting falls back to off`() {
        assertEquals(0, LightingControls.gammaStep(Float.NaN))
        assertEquals(0, LightingControls.gammaPercent(Float.POSITIVE_INFINITY))
        assertEquals(0, LightingControls.playerLightStep(Float.NEGATIVE_INFINITY))
        assertEquals(0, LightingControls.playerLightPercent(Float.NaN))
    }
}
