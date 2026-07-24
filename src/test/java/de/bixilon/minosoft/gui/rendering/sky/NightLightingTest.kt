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

package de.bixilon.minosoft.gui.rendering.sky

import de.bixilon.minosoft.data.world.time.MoonPhases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightLightingTest {

    @Test
    fun `terrain skylight stays visible at darkest night point`() {
        val color = NightLighting.terrainLight(brightness = 1.0f, nightProgress = 0.6f)

        assertTrue(color.x > 0.0f)
        assertTrue(color.y > 0.0f)
        assertTrue(color.z > 0.0f)
    }

    @Test
    fun `new moon preserves baseline visibility`() {
        assertEquals(NightLighting.MINIMUM_MOON_VISIBILITY, NightLighting.moonVisibility(MoonPhases.NEW_MOON))
    }

    @Test
    fun `full moon keeps full visibility`() {
        assertEquals(1.0f, NightLighting.moonVisibility(MoonPhases.FULL_MOON))
    }

    @Test
    fun `moon visibility increases with moon light`() {
        val newMoon = NightLighting.moonVisibility(MoonPhases.NEW_MOON)
        val quarter = NightLighting.moonVisibility(MoonPhases.FIRST_QUARTER)
        val fullMoon = NightLighting.moonVisibility(MoonPhases.FULL_MOON)

        assertTrue(newMoon < quarter)
        assertTrue(quarter < fullMoon)
    }
}
