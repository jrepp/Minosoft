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

    @Test
    fun `snapshot is bounded sorted and generationed by state changes`() {
        val publication = TerrainSelectionPublication()
        val shadow = TerrainViewKey("minosoft:shadow")
        val main = TerrainViewKey("minosoft:main")
        val first = target(1, version = 2L)
        val second = target(2, version = 3L)

        publication.desire(shadow, listOf(second))
        publication.desire(main, listOf(first, second))
        publication.desire(main, listOf(first, second))
        publication.promote(shadow, mapOf(second.page to 3L))

        val snapshot = publication.snapshot(mapOf(first.page to 1L, second.page to 3L))

        assertEquals(3L, snapshot.generation)
        assertEquals(2, snapshot.retainedPageCount)
        assertEquals(listOf(main, shadow), snapshot.views.map { it.view })
        assertEquals(2, snapshot.views[0].desiredPageCount)
        assertEquals(0, snapshot.views[0].activePageCount)
        assertEquals(1, snapshot.views[0].missingPageCount)
        assertEquals(1, snapshot.views[1].activePageCount)
    }

    @Test
    fun `progressive desire never removes an active page without an available target`() {
        val publication = TerrainSelectionPublication()
        val view = TerrainViewKey("minosoft:main")
        val first = target(0, version = 1L)
        val second = target(1, version = 1L)
        val third = target(2, version = 1L)

        publication.desire(view, listOf(first, second))
        assertTrue(publication.promote(view, mapOf(first.page to 1L, second.page to 1L)))

        assertFalse(
            publication.desireAvailable(
                view,
                requestedPages = listOf(first.page, second.page, third.page),
                availableTargets = listOf(first, third),
            ),
        )
        assertEquals(listOf(first, second), publication.desired(view))
        assertEquals(listOf(first.page, second.page), publication.active(view))

        assertTrue(
            publication.desireAvailable(
                view,
                requestedPages = listOf(first.page, second.page, third.page),
                availableTargets = listOf(first, second, third),
            ),
        )
        assertEquals(listOf(first, second, third), publication.desired(view))
    }

    @Test
    fun `empty view can publish progressively and replacement removal waits for all targets`() {
        val publication = TerrainSelectionPublication()
        val view = TerrainViewKey("minosoft:main")
        val first = target(0, version = 1L)
        val second = target(1, version = 1L)

        assertTrue(
            publication.desireAvailable(
                view,
                requestedPages = listOf(first.page, second.page),
                availableTargets = listOf(first),
            ),
        )
        assertEquals(listOf(first), publication.desired(view))
        assertTrue(publication.promote(view, mapOf(first.page to 1L)))

        assertFalse(
            publication.desireAvailable(
                view,
                requestedPages = listOf(second.page),
                availableTargets = emptyList(),
            ),
        )
        assertEquals(listOf(first.page), publication.active(view))
        assertEquals(listOf(first), publication.desired(view))

        assertTrue(
            publication.desireAvailable(
                view,
                requestedPages = listOf(second.page),
                availableTargets = listOf(second),
            ),
        )
        assertEquals(listOf(second), publication.desired(view))
    }

    private fun target(x: Long, version: Long) = TerrainResidencyTarget(
        TerrainPageKey(TerrainDomain.DISTANT, detailLevel = 0, x = x, y = 0L, z = 0L, worldEpoch = 1L),
        version,
    )
}
