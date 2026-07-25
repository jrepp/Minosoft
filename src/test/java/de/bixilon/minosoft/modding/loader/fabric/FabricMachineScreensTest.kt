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

package de.bixilon.minosoft.modding.loader.fabric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricMachineScreensTest {
    @Test
    fun `state accepts only newer synchronized revisions`() {
        val state = SynchronizedMachineScreenState(MachineScreenSnapshot(4, energy = MachineGaugeState(20, 100)))
        assertFalse(state.update(MachineScreenSnapshot(4, energy = MachineGaugeState(30, 100))))
        assertFalse(state.update(MachineScreenSnapshot(3, energy = MachineGaugeState(30, 100))))
        assertTrue(state.update(MachineScreenSnapshot(5, energy = MachineGaugeState(30, 100))))
        assertEquals(30L, state.snapshot.energy?.value)
    }

    @Test
    fun `snapshot validates bounded tank values and unique ids`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MachineScreenSnapshot(
                1,
                tanks = listOf(
                    MachineTankState("water", null, 1, 100),
                ),
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MachineScreenSnapshot(
                1,
                tanks = listOf(
                    MachineTankState("water", null, 0, 100),
                    MachineTankState("water", null, 0, 100),
                ),
            )
        }
    }
}
