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

package de.bixilon.minosoft.gui.rendering.terrain.storage

import de.bixilon.minosoft.terrain.model.mesh.TerrainPrimitiveTopology
import de.bixilon.minosoft.terrain.runtime.storage.TerrainDrawPacket
import de.bixilon.minosoft.terrain.runtime.storage.TerrainDrawPacketGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenGlTerrainDrawPacketCacheTest {
    @Test
    fun `native packets are reused bounded and closed on eviction and teardown`() {
        val cache = OpenGlTerrainDrawPacketCache(maximumEntries = 1)
        val firstPacket = packet(3, 6)
        val first = cache[firstPacket]

        assertSame(first, cache[firstPacket])
        assertEquals(1, cache.size)
        assertEquals(listOf(3, 6), List(first.groups.single().counts.capacity()) { first.groups.single().counts[it] })
        assertEquals(listOf(0L, 12L), List(first.groups.single().offsets.capacity()) { first.groups.single().offsets[it] })
        assertEquals(listOf(0, 4), List(first.groups.single().bases.capacity()) { first.groups.single().bases[it] })

        val second = cache[packet(9)]
        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(1, cache.size)

        cache.close()
        cache.close()
        assertTrue(second.closed)
        assertEquals(0, cache.size)
        assertFailsWith<IllegalStateException> { cache[firstPacket] }
    }

    private fun packet(vararg counts: Int): TerrainDrawPacket {
        val group = TerrainDrawPacketGroup(
            topology = TerrainPrimitiveTopology.TRIANGLES,
            counts = counts,
            indexByteOffsets = LongArray(counts.size) { it * 12L },
            baseVertices = IntArray(counts.size) { it * 4 },
        )
        return TerrainDrawPacket(listOf(group))
    }
}
