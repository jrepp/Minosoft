/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.sky.planet.scatter

import de.bixilon.minosoft.data.world.time.DayPhases
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SunScatterRendererTest {

    @Test
    fun `reference override does not replace authored sunrise gate`() {
        assertFalse(SunScatterRenderer.isEnabled(null, true, DayPhases.DAY, true))
        assertTrue(SunScatterRenderer.isEnabled(null, true, DayPhases.SUNRISE, true))
        assertTrue(SunScatterRenderer.isEnabled(true, false, DayPhases.NIGHT, false))
        assertFalse(SunScatterRenderer.isEnabled(false, true, DayPhases.SUNSET, true))
    }
}
