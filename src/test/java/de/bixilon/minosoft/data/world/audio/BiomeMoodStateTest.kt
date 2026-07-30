/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.data.world.audio

import de.bixilon.minosoft.data.registries.biomes.BiomeMoodSettings
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiomeMoodStateTest {
    private val settings = BiomeMoodSettings(
        sound = minecraft("ambient.cave"),
        tickDelay = 4,
        blockSearchExtent = 8,
        soundPositionOffset = 2.0,
    )

    @Test
    fun `dark samples advance vanilla and constant mood together`() {
        val tracker = BiomeMoodTracker()

        repeat(3) {
            assertFalse(tracker.advance(settings, skyLight = 0, blockLight = 0))
        }
        assertEquals(0.75f, tracker.playerMood)
        assertEquals(0.75f, tracker.constantMood)

        assertTrue(tracker.advance(settings, skyLight = 0, blockLight = 0))
        assertEquals(0.0f, tracker.playerMood)
        assertEquals(1.0f, tracker.constantMood)
    }

    @Test
    fun `sky and block light recover with pinned vanilla rates`() {
        val tracker = BiomeMoodTracker()
        repeat(3) { tracker.advance(settings, skyLight = 0, blockLight = 0) }

        assertFalse(tracker.advance(settings, skyLight = 15, blockLight = 0))
        assertEquals(0.749f, tracker.playerMood)
        assertEquals(0.749f, tracker.constantMood)

        assertFalse(tracker.advance(settings, skyLight = 0, blockLight = 2))
        assertEquals(0.499f, tracker.playerMood)
        assertEquals(0.499f, tracker.constantMood)
    }

    @Test
    fun `reset clears both retained accumulators`() {
        val tracker = BiomeMoodTracker()
        tracker.advance(settings, skyLight = 0, blockLight = 0)

        tracker.reset()

        assertEquals(0.0f, tracker.playerMood)
        assertEquals(0.0f, tracker.constantMood)
    }
}
