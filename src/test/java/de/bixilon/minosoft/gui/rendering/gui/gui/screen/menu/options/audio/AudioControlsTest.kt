/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioControlsTest {

    @Test
    fun `volume changes in stable ten percent steps`() {
        assertEquals(0.6f, AudioControls.adjustVolume(0.5f, 1))
        assertEquals(60, AudioControls.volumePercent(AudioControls.adjustVolume(0.5f, 1)))
    }

    @Test
    fun `volume clamps to profile range`() {
        assertEquals(0.0f, AudioControls.adjustVolume(0.0f, -1))
        assertEquals(1.0f, AudioControls.adjustVolume(1.0f, 1))
    }

    @Test
    fun `volume rounds imported values onto menu steps`() {
        assertEquals(0.4f, AudioControls.adjustVolume(0.34f, 1))
    }

    @Test
    fun `volume step conversion is reversible`() {
        assertEquals(3, AudioControls.volumeStep(0.34f))
        assertEquals(0.3f, AudioControls.volumeForStep(3))
        assertEquals(0.0f, AudioControls.volumeForStep(-1))
        assertEquals(1.0f, AudioControls.volumeForStep(11))
    }

    @Test
    fun `non-finite profile volume falls back to muted`() {
        assertEquals(0, AudioControls.volumeStep(Float.NaN))
        assertEquals(0, AudioControls.volumePercent(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `engine status distinguishes ready starting and restart states`() {
        assertEquals(AudioEngineStatus.READY, AudioControls.engineStatus(skipLoading = true, initialized = true))
        assertEquals(AudioEngineStatus.STARTING, AudioControls.engineStatus(skipLoading = false, initialized = false))
        assertEquals(AudioEngineStatus.RESTART_REQUIRED, AudioControls.engineStatus(skipLoading = true, initialized = false))
    }
}
