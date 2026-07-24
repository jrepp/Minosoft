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

package de.bixilon.minosoft.data.entities.entities.player

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArmSwingStateTest {

    @Test
    fun swingProgressIsSharedAndBounded() {
        var now = 1_000_000_000L
        val state = ArmSwingState { now }

        assertNull(state.progress(Arms.RIGHT))
        state.swing(Arms.RIGHT)
        assertEquals(state.progress(Arms.RIGHT), 0.0f)

        now += ArmSwingState.DURATION_NANOS / 2
        assertEquals(state.progress(Arms.RIGHT), 0.5f)
        assertNull(state.progress(Arms.LEFT))

        now += ArmSwingState.DURATION_NANOS / 2
        assertNull(state.progress(Arms.RIGHT))
    }

    @Test
    fun repeatedSwingDoesNotRestartAnActiveAnimation() {
        var now = 1_000_000_000L
        val state = ArmSwingState { now }
        state.swing(Arms.RIGHT)

        now += ArmSwingState.DURATION_NANOS / 2
        state.swing(Arms.RIGHT)
        assertEquals(state.progress(Arms.RIGHT), 0.5f)
    }
}
