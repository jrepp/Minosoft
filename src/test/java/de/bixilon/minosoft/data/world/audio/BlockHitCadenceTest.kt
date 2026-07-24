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

package de.bixilon.minosoft.data.world.audio

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockHitCadenceTest {

    @Test
    fun `first hit is immediate and later hits are time bounded`() {
        var now = 1_000_000_000L
        val cadence = BlockHitCadence { now }

        assertTrue(cadence.take())
        repeat(10) { assertFalse(cadence.take()) }

        now += BlockHitCadence.INTERVAL_NANOS - 1
        assertFalse(cadence.take())
        now++
        assertTrue(cadence.take())
    }

    @Test
    fun `nano time wraparound preserves cadence`() {
        var now = Long.MAX_VALUE - BlockHitCadence.INTERVAL_NANOS / 2
        val cadence = BlockHitCadence { now }

        assertTrue(cadence.take())
        now += BlockHitCadence.INTERVAL_NANOS - 1
        assertFalse(cadence.take())
        now++
        assertTrue(cadence.take())
    }
}
