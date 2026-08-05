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

package de.bixilon.minosoft.data.world.entities

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldEntitiesMemoryTest {
    @Test
    fun `entity indexes compact only after a substantial sparse transition`() {
        assertFalse(shouldCompactEntityIndexes(100, 2_000, 255, force = false))
        assertFalse(shouldCompactEntityIndexes(600, 2_000, 300, force = false))
        assertTrue(shouldCompactEntityIndexes(500, 2_000, 256, force = false))
        assertTrue(shouldCompactEntityIndexes(1, 1, 0, force = true))
    }
}
