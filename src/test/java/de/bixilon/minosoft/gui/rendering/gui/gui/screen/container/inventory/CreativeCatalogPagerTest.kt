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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreativeCatalogPagerTest {

    @Test
    fun `pages expose every item exactly once`() {
        val pager = CreativeCatalogPager((0 until 130).toList(), 63)

        assertEquals(pager.pageCount, 3)
        assertEquals(pager.visible, (0 until 63).toList())
        assertFalse(pager.scroll(-1.0f))
        assertTrue(pager.scroll(-1.0f))
        assertEquals(pager.visible, (63 until 126).toList())
        assertFalse(pager.scroll(-1.0f))
        assertTrue(pager.scroll(-1.0f))
        assertEquals(pager.visible, (126 until 130).toList())
    }

    @Test
    fun `scrolling clamps at both ends`() {
        val pager = CreativeCatalogPager((0 until 64).toList(), 63)

        assertFalse(pager.scroll(1.0f))
        assertEquals(pager.page, 0)
        assertFalse(pager.scroll(-1.0f))
        assertTrue(pager.scroll(-1.0f))
        assertEquals(pager.page, 1)
        assertFalse(pager.scroll(-1.0f))
        assertEquals(pager.page, 1)
        assertFalse(pager.scroll(1.0f))
        assertTrue(pager.scroll(1.0f))
        assertEquals(pager.page, 0)
    }

    @Test
    fun `fractional scrolling accumulates and direction changes discard momentum`() {
        val pager = CreativeCatalogPager((0 until 64).toList(), 63)

        assertFalse(pager.scroll(-0.75f))
        assertFalse(pager.scroll(-0.75f))
        assertFalse(pager.scroll(0.75f))
        assertFalse(pager.scroll(-1.0f))
        assertTrue(pager.scroll(-1.0f))
        assertEquals(1, pager.page)
    }

    @Test
    fun `empty catalogs still have a stable page`() {
        val pager = CreativeCatalogPager(emptyList<Int>(), 63)

        assertEquals(pager.pageCount, 1)
        assertEquals(pager.visible, emptyList<Int>())
        assertFalse(pager.scroll(-1.0f))
    }

    @Test
    fun `search filters case insensitively and resets paging`() {
        val items = listOf(
            "minecraft:oak_planks Oak Planks",
            "minecraft:spruce_planks Spruce Planks",
            "minecraft:stone Stone",
        )
        val pager = CreativeCatalogPager(items, 1)

        assertFalse(pager.scroll(-1.0f))
        assertTrue(pager.scroll(-1.0f))
        assertEquals(1, pager.page)
        assertTrue(pager.search("OAK plank"))
        assertEquals(0, pager.page)
        assertEquals(1, pager.resultCount)
        assertEquals(listOf(items.first()), pager.visible)
    }

    @Test
    fun `search can match namespaced identifiers and clear back to all items`() {
        val items = listOf("minecraft:stone Stone", "example:copper_wire Copper Wire")
        val pager = CreativeCatalogPager(items, 63)

        assertTrue(pager.search("example: copper"))
        assertEquals(listOf(items.last()), pager.visible)
        assertTrue(pager.search(""))
        assertEquals(items, pager.visible)
    }

    @Test
    fun `search with no matches keeps a stable empty page`() {
        val pager = CreativeCatalogPager(listOf("minecraft:stone Stone"), 63)

        assertTrue(pager.search("diamond"))
        assertEquals(0, pager.resultCount)
        assertEquals(1, pager.pageCount)
        assertEquals(emptyList<String>(), pager.visible)
        assertFalse(pager.scroll(-1.0f))
    }

    @Test
    fun `a changed query resets paging even when every item still matches`() {
        val pager = CreativeCatalogPager(listOf("stone", "dirt"), 1)

        assertFalse(pager.scroll(-1.0f))
        assertTrue(pager.scroll(-1.0f))
        assertTrue(pager.search("t"))
        assertEquals(0, pager.page)
        assertEquals(2, pager.resultCount)
        assertFalse(pager.search(" T "))
    }
}
