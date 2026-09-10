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

package de.bixilon.minosoft.debug.content

import de.bixilon.minosoft.data.registries.blocks.types.Block
import de.bixilon.minosoft.data.registries.blocks.types.properties.size.DoubleSizeBlock
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["block", "debug"])
class BlockStateSculptureCatalogIT {

    fun `catalog pages cover every legal state exactly once`() {
        val session = createSession()
        for (name in listOf("oak_fence", "oak_stairs", "oak_door", "cobblestone_wall", "redstone_wire")) {
            val block = block(session, name)
            val first = BlockStateSculptureCatalog.page(block, 0, PAGE_SIZE)
            val keys = (0 until first.totalPages).flatMap { page ->
                BlockStateSculptureCatalog.page(block, page, PAGE_SIZE).entries.map { it.key }
            }
            val registryKeys = block.states.map(BlockStateSculptureCatalog::canonicalKey)
            assertEquals(keys.toSet(), registryKeys.toSet(), "Sculpture catalog did not exactly cover minecraft:$name")
            assertEquals(keys.toSet().size, registryKeys.size, "Sculpture catalog repeated minecraft:$name states")
        }
    }

    fun `known complex families retain their complete state counts`() {
        val session = createSession()
        val fence = BlockStateSculptureCatalog.page(block(session, "oak_fence"), 0)
        assertEquals(fence.totalStates, 32)
        assertTrue(fence.entries.any { "north=true" in it.key })
        assertTrue(fence.entries.any { "north=false" in it.key })
        assertTrue(fence.entries.none { "north=side" in it.key || "north=none" in it.key })
        val stairs = BlockStateSculptureCatalog.page(block(session, "oak_stairs"), 0)
        assertEquals(stairs.totalStates, 80)
        assertTrue(stairs.entries.any { "half=bottom" in it.key })
        assertTrue(stairs.entries.any { "half=top" in it.key })
        assertTrue(stairs.entries.none { "half=lower" in it.key || "half=upper" in it.key })
        val door = BlockStateSculptureCatalog.page(block(session, "oak_door"), 0)
        assertEquals(door.totalStates, 64)
        assertTrue(door.entries.any { "half=lower" in it.key })
        assertTrue(door.entries.any { "half=upper" in it.key })
        assertTrue(door.entries.none { "half=bottom" in it.key || "half=top" in it.key })
        assertEquals(door.spacing, BlockStateSculptureCatalog.DOUBLE_SIZE_SPACING)
        assertTrue(door.entries.all { it.context.size == 1 })
        for (entry in door.entries) {
            val context = entry.context.single()
            assertEquals(context.position.x, entry.position.x)
            assertEquals(context.position.z, entry.position.z)
            assertEquals(kotlin.math.abs(context.position.y - entry.position.y), 1)
            assertEquals(context.state[DoubleSizeBlock.HALF] == entry.state[DoubleSizeBlock.HALF], false)
            assertEquals(
                context.state.withProperties(DoubleSizeBlock.HALF to entry.state[DoubleSizeBlock.HALF]),
                entry.state,
            )
        }
        assertEquals(BlockStateSculptureCatalog.page(block(session, "cobblestone_wall"), 0).totalStates, 324)
        assertEquals(BlockStateSculptureCatalog.page(block(session, "redstone_wire"), 0).totalStates, 1296)
    }

    fun `page layout and fingerprint are stable and bounded`() {
        val session = createSession()
        val block = block(session, "oak_stairs")
        val first = BlockStateSculptureCatalog.page(block, 0, PAGE_SIZE)
        val repeated = BlockStateSculptureCatalog.page(block, 0, PAGE_SIZE)
        val second = BlockStateSculptureCatalog.page(block, 1, PAGE_SIZE)

        assertEquals(first.entries.map { it.key }, repeated.entries.map { it.key })
        assertEquals(first.entries.map { it.position }, repeated.entries.map { it.position })
        assertEquals(first.fingerprint, repeated.fingerprint)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.slots.toSet().size, first.slots.size)
        assertTrue(first.entries.size <= BlockStateSculptureCatalog.MAX_PAGE_SIZE)
        assertEquals(first.entries.last().catalogIndex + 1, second.entries.first().catalogIndex)
    }

    fun `partial pages and changing door halves clear the same complete footprint`() {
        val session = createSession()
        for (name in listOf("oak_stairs", "oak_door")) {
            val block = block(session, name)
            val first = BlockStateSculptureCatalog.page(block, 0, PAGE_SIZE)
            for (index in 0 until first.totalPages) {
                val page = BlockStateSculptureCatalog.page(block, index, PAGE_SIZE)
                assertEquals(page.slots, first.slots, "Page footprint changed for $name page $index")
                for (entry in page.entries) {
                    assertTrue(entry.position in page.slots)
                    assertTrue(entry.context.all { it.position in page.slots })
                }
            }
        }
    }

    fun `exported Java property values round trip to the exact registry states`() {
        val session = createSession()
        for (name in listOf("oak_fence", "oak_stairs", "oak_door", "cobblestone_wall", "redstone_wire")) {
            val block = block(session, name)
            for (state in block.states) for ((property, value) in state.properties) {
                val exported = BlockStateSculptureCatalog.canonicalValue(state, property, value)
                assertEquals(property.parse(exported), value, "Could not round-trip minecraft:$name $property=$exported")
            }
        }
    }

    private fun block(session: PlaySession, name: String): Block =
        requireNotNull(session.registries.block[ResourceLocation.of("minecraft:$name")])

    companion object {
        private const val PAGE_SIZE = 37
    }
}
