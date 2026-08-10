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

package de.bixilon.minosoft.assets.audit

import de.bixilon.minosoft.assets.audit.ContentAssetAudit.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentAssetAuditTest {
    @Test
    fun `snapshot is sorted deduplicated and independent of insertion order`() {
        val first = ContentAssetAudit().apply {
            missing(Kind.TEXTURE, "minecraft:textures/misc/vignette.png")
            missing(Kind.MODEL, "minecraft:models/item/stone.json", "minecraft:stone")
            missing(Kind.BLOCKSTATE, "minecraft:blockstates/stone.json", "minecraft:stone")
            missing(Kind.MODEL, "minecraft:models/item/stone.json", "minecraft:stone")
            missing(Kind.MODEL, "minecraft:models/item/stone.json", "minecraft:stone_button")
        }.snapshot()
        val second = ContentAssetAudit().apply {
            missing(Kind.MODEL, "minecraft:models/item/stone.json", "minecraft:stone_button")
            missing(Kind.BLOCKSTATE, "minecraft:blockstates/stone.json", "minecraft:stone")
            missing(Kind.MODEL, "minecraft:models/item/stone.json", "minecraft:stone")
            missing(Kind.TEXTURE, "minecraft:textures/misc/vignette.png")
        }.snapshot()

        assertEquals(first, second)
        assertEquals(first.entries.map { it.kind }, listOf(Kind.BLOCKSTATE, Kind.MODEL, Kind.TEXTURE))
        assertEquals(first.entries[1].consumers, listOf("minecraft:stone", "minecraft:stone_button"))
        assertEquals(first.entries[1].target, "assets/minecraft/models/item/stone.json")
        assertFalse(first.truncated)
    }

    @Test
    fun `consumer fanout is bounded deterministically and blank resources reject`() {
        val audit = ContentAssetAudit()
        for (index in 299 downTo 0) {
            audit.missing(Kind.MODEL, "minecraft:models/item/shared.json", "minecraft:item_%03d".format(index))
        }

        val entry = audit.snapshot().entries.single()
        assertEquals(16, entry.consumers.size)
        assertEquals("minecraft:item_000", entry.consumers.first())
        assertEquals("minecraft:item_015", entry.consumers.last())
        assertTrue(entry.consumersTruncated)
        assertFailsWith<IllegalArgumentException> { audit.missing(Kind.TEXTURE, "  ") }
        assertFailsWith<IllegalArgumentException> { audit.missing(Kind.TEXTURE, "minecraft:../outside.png") }
        assertFailsWith<IllegalArgumentException> {
            audit.missing(Kind.TEXTURE, "minecraft:textures/item/stone.png", "not a resource")
        }
    }

    @Test
    fun `resource inventory keeps the lexicographically smallest transport-safe set`() {
        val audit = ContentAssetAudit()
        for (index in 2_099 downTo 0) {
            audit.missing(Kind.TEXTURE, "minecraft:textures/item/item_%04d.png".format(index))
        }

        val snapshot = audit.snapshot()
        assertEquals(2_048, snapshot.entries.size)
        assertEquals("minecraft:textures/item/item_0000.png", snapshot.entries.first().resource)
        assertEquals("minecraft:textures/item/item_2047.png", snapshot.entries.last().resource)
        assertTrue(snapshot.truncated)
    }
}
