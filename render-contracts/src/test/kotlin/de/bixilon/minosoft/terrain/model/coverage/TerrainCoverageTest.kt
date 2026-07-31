/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.model.coverage

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainCoverageTest {
    @Test
    fun `coverage lifecycle admits retries and last known good retirement`() {
        assertTrue(TerrainCoverageState.ABSENT.canTransitionTo(TerrainCoverageState.REQUESTED))
        assertTrue(TerrainCoverageState.BUILDING.canTransitionTo(TerrainCoverageState.REQUESTED))
        assertTrue(TerrainCoverageState.READY.canTransitionTo(TerrainCoverageState.RETIRING))
        assertTrue(TerrainCoverageState.RETIRING.canTransitionTo(TerrainCoverageState.READY))
        assertFalse(TerrainCoverageState.ABSENT.canTransitionTo(TerrainCoverageState.READY))
        assertThrows<IllegalArgumentException> {
            TerrainCoverageState.ABSENT.requireTransitionTo(TerrainCoverageState.READY)
        }
    }

    @Test
    fun `snapshot is immutable epoch checked and deterministically ordered`() {
        val mutable = mutableListOf(
            cell(x = 2L, z = 1L),
            cell(x = 1L, z = 0L),
            cell(x = 0L, z = 1L),
        )
        val snapshot = TerrainCoverageSnapshot(7L, 2L, 3L, mutable)
        mutable.clear()

        assertEquals(listOf(1L, 0L, 2L), snapshot.cells.map { it.page.x })
        assertThrows<IllegalArgumentException> {
            TerrainCoverageSnapshot(8L, 2L, 3L, snapshot.cells)
        }
        assertThrows<IllegalArgumentException> {
            TerrainCoverageSnapshot(7L, 2L, 3L, listOf(cell(), cell()))
        }
    }

    @Test
    fun `replacement lifecycle retains last known good spatial coverage`() {
        val tracker = TerrainCoverageTracker(worldEpoch = 7L, providerGeneration = 3L)
        val page = cell().page
        tracker.transition(page, TerrainCoverageState.REQUESTED, true, frame = 1L)
        tracker.transition(page, TerrainCoverageState.BUILDING, true, frame = 2L)
        tracker.transition(page, TerrainCoverageState.UPLOAD_PENDING, true, frame = 3L)
        tracker.transition(page, TerrainCoverageState.READY, true, frame = 4L)
        val readyRevision = tracker.revision

        tracker.transition(page, TerrainCoverageState.REQUESTED, true, frame = 5L)
        tracker.transition(page, TerrainCoverageState.BUILDING, true, frame = 6L)
        tracker.transition(page, TerrainCoverageState.REQUESTED, true, frame = 7L)

        val retry = tracker.snapshot(frame = 10L)
        assertEquals(readyRevision, retry.revision)
        assertEquals(setOf(page), retry.coveredPages)
        assertEquals(TerrainCoverageState.REQUESTED, retry.cells.single().state)
        assertTrue(retry.cells.single().contributesCoverage)
        assertEquals(3, retry.cells.single().transitionAgeFrames)

        tracker.transition(page, TerrainCoverageState.ABSENT, true, frame = 11L)
        assertEquals(readyRevision + 1L, tracker.revision)
        assertTrue(tracker.snapshot(11L).coveredPages.isEmpty())
    }

    @Test
    fun `non-surface lifecycle changes diagnostics without changing spatial revision`() {
        val tracker = TerrainCoverageTracker(worldEpoch = 7L, providerGeneration = 3L)
        val page = cell().page

        assertTrue(tracker.transition(page, TerrainCoverageState.REQUESTED, false, 1L))
        assertTrue(tracker.transition(page, TerrainCoverageState.BUILDING, false, 2L))
        assertEquals(0L, tracker.revision)
        assertEquals(2L, tracker.lifecycleRevision)
        assertEquals(2L, tracker.snapshot(2L).lifecycleRevision)
        assertFalse(tracker.transition(page, TerrainCoverageState.BUILDING, false, 3L))
        assertThrows<IllegalArgumentException> { tracker.snapshot(1L) }
        assertThrows<IllegalArgumentException> {
            tracker.transition(page.copy(worldEpoch = 8L), TerrainCoverageState.REQUESTED, true, 3L)
        }
    }

    private fun cell(
        x: Long = 0L,
        z: Long = 0L,
    ) = TerrainCoverageCell(
        page = TerrainPageKey(TerrainDomain.NEAR, 0, x, 0L, z, 7L),
        state = TerrainCoverageState.READY,
        surfaceRelevant = true,
        transitionAgeFrames = 0,
    )
}
