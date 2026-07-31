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

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.data.world.positions.ChunkPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DistantLodChangeJournalTest {
    @Test
    fun `journal coalesces positions without scanning retained domain`() {
        val journal = DistantLodChangeJournal(4)
        val first = ChunkPosition(1, 2)
        val second = ChunkPosition(3, 4)
        journal.record(first, 1L)
        journal.record(second, 2L)
        journal.record(first, 3L)

        val changes = journal.changesSince(1L, 3L)

        assertFalse(changes.reset)
        assertEquals(listOf(second, first), changes.positions)
        assertTrue(journal.changesSince(3L, 4L).positions.isEmpty())
    }

    @Test
    fun `journal requests full reset once a required change falls outside its bound`() {
        val journal = DistantLodChangeJournal(2)
        journal.record(ChunkPosition(0, 0), 1L)
        journal.record(ChunkPosition(1, 0), 2L)
        journal.record(ChunkPosition(2, 0), 3L)

        assertTrue(journal.changesSince(0L, 3L).reset)
        assertFalse(journal.changesSince(1L, 3L).reset)
        assertEquals(listOf(ChunkPosition(1, 0), ChunkPosition(2, 0)), journal.changesSince(1L, 3L).positions)
    }
}
