/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.diagnostic

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TerrainDiagnosticQueryTest {
    @Test
    fun `selectors and result counts are strict and bounded`() {
        assertThrows<IllegalArgumentException> {
            TerrainDiagnosticFixtures.query(maximumCount = 0)
        }
        assertThrows<IllegalArgumentException> {
            TerrainDiagnosticFixtures.query(
                maximumCount = TerrainDiagnosticSchema.MAXIMUM_PAGE_COUNT + 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainPageAreaSelector(
                TerrainDomain.NEAR,
                minimumDetailLevel = 1,
                maximumDetailLevel = 0,
                minimumX = 0L,
                maximumX = 0L,
                minimumY = 0L,
                maximumY = 0L,
                minimumZ = 0L,
                maximumZ = 0L,
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainPagePrefixSelector(TerrainDomain.NEAR, detailLevel = 0, y = 1L)
        }
        assertThrows<IllegalArgumentException> {
            TerrainPageDiagnosticWindow(
                query = TerrainDiagnosticFixtures.query(maximumCount = 1),
                pages = listOf(
                    TerrainDiagnosticFixtures.pageSnapshot(-1L, -1L),
                    TerrainDiagnosticFixtures.pageSnapshot(1L, 0L),
                ),
                nextCursor = null,
            )
        }
    }

    @Test
    fun `page windows use canonical order and reject pages outside selector`() {
        val query = TerrainDiagnosticFixtures.query()
        val later = TerrainDiagnosticFixtures.pageSnapshot(1L, 0L)
        val earlier = TerrainDiagnosticFixtures.pageSnapshot(-1L, -1L)
        val window = TerrainPageDiagnosticWindow(query, listOf(later, earlier), null)

        assertEquals(listOf(earlier, later), window.pages)
        assertThrows<IllegalArgumentException> {
            TerrainPageDiagnosticWindow(
                query,
                listOf(TerrainDiagnosticFixtures.pageSnapshot(2L, 0L)),
                null,
            )
        }
    }

    @Test
    fun `opaque cursor is tied to diagnostic generation selector and world epoch`() {
        val query = TerrainDiagnosticFixtures.query()
        val lastPage = TerrainDiagnosticFixtures.page(-1L, -1L)
        val cursor = TerrainPageCursorCodec.issue(
            TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
            query,
            lastPage,
        )

        assertEquals(
            TerrainPageCursorResolution.Accepted(lastPage),
            TerrainPageCursorCodec.resolve(
                cursor,
                TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
                query,
            ),
        )

        val stale = assertIs<TerrainPageCursorResolution.Rejected>(
            TerrainPageCursorCodec.resolve(
                cursor,
                TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION + 1L,
                query,
            ),
        )
        assertEquals(TerrainDiagnosticRejectionCode.STALE_CURSOR, stale.rejection.code)

        val changedSelector = query.copy(
            selector = TerrainPagePrefixSelector(TerrainDomain.NEAR, detailLevel = 0),
        )
        val mismatch = assertIs<TerrainPageCursorResolution.Rejected>(
            TerrainPageCursorCodec.resolve(
                cursor,
                TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
                changedSelector,
            ),
        )
        assertEquals(
            TerrainDiagnosticRejectionCode.CURSOR_SELECTOR_MISMATCH,
            mismatch.rejection.code,
        )

        val malformed = assertIs<TerrainPageCursorResolution.Rejected>(
            TerrainPageCursorCodec.resolve(
                TerrainPageCursor("abcd"),
                TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
                query,
            ),
        )
        assertEquals(TerrainDiagnosticRejectionCode.MALFORMED_CURSOR, malformed.rejection.code)
    }

    @Test
    fun `bounded paging uses one-record lookahead and issues the canonical continuation`() {
        val query = TerrainDiagnosticFixtures.query(maximumCount = 2)
        val first = TerrainDiagnosticFixtures.pageSnapshot(-1L, -1L)
        val second = TerrainDiagnosticFixtures.pageSnapshot(0L, 0L)
        val lookahead = TerrainDiagnosticFixtures.pageSnapshot(1L, 0L)
        val window = TerrainPageDiagnosticPaging.window(
            TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
            query,
            listOf(lookahead, second, first),
        )

        assertEquals(listOf(first, second), window.pages)
        val cursor = assertNotNull(window.nextCursor)
        assertEquals(
            TerrainPageCursorResolution.Accepted(second.page),
            TerrainPageCursorCodec.resolve(
                cursor,
                TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
                query,
            ),
        )
        assertThrows<IllegalArgumentException> {
            TerrainPageDiagnosticPaging.window(
                TerrainDiagnosticFixtures.DIAGNOSTIC_GENERATION,
                query,
                listOf(first, second, lookahead, TerrainDiagnosticFixtures.pageSnapshot(1L, 1L)),
            )
        }
    }
}
