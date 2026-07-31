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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainCoverageMaskingTest {
    private val policy = TerrainCoverageTransitionPolicy(true, 8)

    @Test
    fun `new ready coverage overlaps then deterministically excludes distant`() {
        val start = TerrainCoverageMasking.decide(cell(TerrainCoverageState.READY, coverageAge = 0), policy)
        val middle = TerrainCoverageMasking.decide(cell(TerrainCoverageState.READY, coverageAge = 4), policy)
        val complete = TerrainCoverageMasking.decide(cell(TerrainCoverageState.READY, coverageAge = 8), policy)

        assertEquals(0.0, start.nearWeight)
        assertEquals(0.5, middle.nearWeight)
        assertTrue(middle.drawNear && middle.drawDistant)
        assertEquals(1.0, complete.nearWeight)
        assertFalse(complete.drawDistant)
    }

    @Test
    fun `retirement reverses overlap and absent coverage keeps distant`() {
        val middle = TerrainCoverageMasking.decide(
            cell(TerrainCoverageState.RETIRING, transitionAge = 4, coverageAge = 20),
            policy,
        )
        assertEquals(0.5, middle.nearWeight)
        assertEquals(0.5, middle.distantWeight)
        assertEquals(
            TerrainCoverageMaskDecision(false, true, 0.0, 1.0),
            TerrainCoverageMasking.decide(null, policy),
        )
    }

    @Test
    fun `disabled transition is conservative overlap`() {
        val decision = TerrainCoverageMasking.decide(
            cell(TerrainCoverageState.READY, coverageAge = 100),
            TerrainCoverageTransitionPolicy.CONSERVATIVE_OVERLAP,
        )
        assertTrue(decision.drawNear && decision.drawDistant)
        assertEquals(1.0, decision.nearWeight)
        assertEquals(1.0, decision.distantWeight)
    }

    @Test
    fun `page mask requires complete base coverage and fails open across worlds`() {
        val distant = page(TerrainDomain.DISTANT, detail = 1, x = -1L, z = 0L)
        val complete = listOf(
            cell(x = -2L, z = 0L, coverageAge = 100),
            cell(x = -1L, z = 0L, coverageAge = 100),
            cell(x = -2L, z = 1L, coverageAge = 100),
            cell(x = -1L, z = 1L, coverageAge = 100),
        )

        assertFalse(TerrainCoveragePageMask(snapshot(complete), policy).drawDistant(distant))
        assertTrue(TerrainCoveragePageMask(snapshot(complete.dropLast(1)), policy).drawDistant(distant))
        assertTrue(
            TerrainCoveragePageMask(snapshot(complete), policy).drawDistant(distant.copy(worldEpoch = 2L)),
        )
    }

    @Test
    fun `page mask transition is deterministic and retains failed replacement coverage`() {
        val distant = page(TerrainDomain.DISTANT, x = 4L, z = -3L)
        val initial = TerrainCoveragePageMask(
            snapshot(listOf(cell(x = 4L, z = -3L, coverageAge = 0))),
            policy,
        )
        val settledCell = cell(x = 4L, z = -3L, coverageAge = policy.durationFrames)
        val settled = TerrainCoveragePageMask(snapshot(listOf(settledCell)), policy)
        val replacement = TerrainCoveragePageMask(
            snapshot(
                listOf(
                    settledCell.copy(
                        state = TerrainCoverageState.BUILDING,
                        transitionAgeFrames = 1,
                        contributesCoverage = true,
                    ),
                ),
            ),
            policy,
        )

        assertTrue(initial.drawDistant(distant))
        assertFalse(settled.drawDistant(distant))
        assertFalse(replacement.drawDistant(distant))
        assertEquals(settled.drawDistant(distant), settled.drawDistant(distant))
    }

    private fun cell(
        state: TerrainCoverageState,
        transitionAge: Int = 0,
        coverageAge: Int,
        x: Long = 0L,
        z: Long = 0L,
    ) = TerrainCoverageCell(
        page(TerrainDomain.NEAR, x = x, z = z),
        state,
        true,
        transitionAge,
        true,
        coverageAge,
    )

    private fun cell(
        x: Long,
        z: Long,
        coverageAge: Int,
    ) = cell(TerrainCoverageState.READY, coverageAge = coverageAge, x = x, z = z)

    private fun snapshot(cells: List<TerrainCoverageCell>) = TerrainCoverageSnapshot(
        worldEpoch = 1L,
        revision = 1L,
        providerGeneration = 1L,
        cells = cells,
    )

    private fun page(
        domain: TerrainDomain,
        detail: Int = 0,
        x: Long = 0L,
        z: Long = 0L,
    ) = TerrainPageKey(domain, detail, x, 0L, z, 1L)
}
