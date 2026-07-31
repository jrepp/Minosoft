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

package de.bixilon.minosoft.terrain.runtime.storage

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerrainSelectionPublicationTest {
    @Test
    fun `replacement selection retains old pages until every exact version is resident`() {
        val publication = TerrainSelectionPublication()
        val view = TerrainViewKey("minosoft:main")
        val old = target(0, version = 1L)
        val first = target(1, version = 2L)
        val second = target(2, version = 3L)

        publication.desire(view, listOf(old))
        assertTrue(publication.promote(view, mapOf(old.page to old.publicationVersion)))
        publication.desire(view, listOf(first, second))

        assertFalse(publication.promote(view, mapOf(old.page to 1L, first.page to 2L)))
        assertEquals(listOf(old.page), publication.active(view))
        assertEquals(setOf(old.page, first.page, second.page), publication.retainedPages())
        assertEquals(listOf(second), publication.missing(mapOf(old.page to 1L, first.page to 2L)))

        assertTrue(publication.promote(view, mapOf(first.page to 2L, second.page to 3L)))
        assertEquals(listOf(first.page, second.page), publication.active(view))
        assertEquals(setOf(first.page, second.page), publication.retainedPages())
    }

    @Test
    fun `stale resident version cannot satisfy desired replacement`() {
        val publication = TerrainSelectionPublication()
        val view = TerrainViewKey("minosoft:shadow")
        val replacement = target(4, version = 9L)
        publication.desire(view, listOf(replacement))

        assertFalse(publication.promote(view, mapOf(replacement.page to 8L)))
        assertEquals(listOf(replacement), publication.missing(mapOf(replacement.page to 8L)))
        assertTrue(publication.promote(view, mapOf(replacement.page to 9L)))
    }

    @Test
    fun `empty desired selection retires active view immediately`() {
        val publication = TerrainSelectionPublication()
        val view = TerrainViewKey("minosoft:main")
        val page = target(0, version = 1L)
        publication.desire(view, listOf(page))
        publication.promote(view, mapOf(page.page to 1L))

        publication.desire(view, emptyList())

        assertTrue(publication.promote(view, emptyMap()))
        assertTrue(publication.active(view).isEmpty())
        assertTrue(publication.retainedPages().isEmpty())
    }

    private fun target(x: Long, version: Long) = TerrainResidencyTarget(
        TerrainPageKey(TerrainDomain.DISTANT, detailLevel = 0, x = x, y = 0L, z = 0L, worldEpoch = 1L),
        version,
    )
}
