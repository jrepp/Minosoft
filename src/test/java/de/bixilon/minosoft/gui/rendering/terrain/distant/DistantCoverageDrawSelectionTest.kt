/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.terrain.distant

import de.bixilon.minosoft.terrain.distant.DistantSemanticDigest
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageHierarchyIndex
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelectionRequest
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelector
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantSelectionCamera
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageCell
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTransitionPolicy
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistantCoverageDrawSelectionTest {
    @Test
    fun `production draw boundary preserves the uncovered cell and masks settled near ownership`() {
        val root = key(0, 0, detail = 2)
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 2, maximumPages = 64)
        repeat(4) { z ->
            repeat(4) { x ->
                index.publishSource(
                    key(x.toLong(), z.toLong()),
                    sourceRevision = (z * 4L) + x + 1L,
                    completeness = DistantSourceCompleteness.COMPLETE,
                    semanticDigest = DistantSemanticDigest(1, "test", "page-$x-$z"),
                )
            }
        }
        val covered = (0L until 4L).flatMap { z ->
            (0L until 4L).map { x -> x to z }
        }.dropLast(1)
        val coverage = TerrainCoverageSnapshot(
            revision = 15L,
            providerGeneration = 1L,
            worldEpoch = WORLD_EPOCH,
            cells = covered.map { (x, z) ->
                TerrainCoverageCell(
                    page = TerrainPageKey(TerrainDomain.NEAR, 0, x, 0L, z, WORLD_EPOCH),
                    state = TerrainCoverageState.READY,
                    surfaceRelevant = true,
                    transitionAgeFrames = 2,
                    contributesCoverage = true,
                    coverageAgeFrames = 2,
                )
            },
        )
        val request = DistantPageSelectionRequest(
            camera = DistantSelectionCamera(10_000.0, 80.0, 10_000.0, 70.0),
            viewportHeightPixels = 1_080,
            basePageSizeBlocks = 16,
            roots = listOf(root),
            refineErrorPixels = 4.0,
            coarsenErrorPixels = 2.0,
            maximumNodeVisits = 128,
            maximumSelectionChanges = 128,
        )
        val selected = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(),
            metadata = emptyMap(),
            request = request,
            nearCoverage = coverage,
        ).pages

        val main = distantCoverageDrawSelection(selected, coverage, TRANSITION)
        val shadow = distantCoverageDrawSelection(selected, coverage, TRANSITION)

        assertEquals(main, shadow)
        assertEquals(selected.size - main.drawable.size, main.maskedPages)
        assertEquals(listOf(key(3, 3)), main.drawable)
        assertTrue(covered.all { (x, z) -> main.drawable.none { it.coversBasePage(x, z) } })
        assertTrue(main.drawable.any { it.coversBasePage(3, 3) })
    }

    @Test
    fun `exact base ownership masks owned pages while partial coarse coverage remains drawable`() {
        val ownedBase = key(0, 0)
        val uncoveredBase = key(1, 0)
        val partialCoarse = key(0, 0, detail = 1)
        val coverage = TerrainCoverageSnapshot(
            revision = 16L,
            providerGeneration = 1L,
            worldEpoch = WORLD_EPOCH,
            cells = listOf(
                TerrainCoverageCell(
                    page = TerrainPageKey(TerrainDomain.NEAR, 0, 0L, 0L, 0L, WORLD_EPOCH),
                    state = TerrainCoverageState.READY,
                    surfaceRelevant = true,
                    transitionAgeFrames = 8,
                    contributesCoverage = true,
                    coverageAgeFrames = 8,
                ),
            ),
        )

        val result = distantCoverageDrawSelection(
            listOf(ownedBase, uncoveredBase, partialCoarse),
            coverage,
            TerrainCoverageTransitionPolicy(true, 8),
        )

        assertFalse(ownedBase in result.drawable)
        assertTrue(uncoveredBase in result.drawable)
        assertTrue(partialCoarse in result.drawable)
    }

    private fun key(x: Long, z: Long, detail: Int = 0) = TerrainPageKey(
        TerrainDomain.DISTANT,
        detail,
        x,
        0,
        z,
        WORLD_EPOCH,
    )

    private fun TerrainPageKey.coversBasePage(x: Long, z: Long): Boolean {
        val span = 1L shl detailLevel
        return x in this.x * span until (this.x + 1L) * span &&
            z in this.z * span until (this.z + 1L) * span
    }

    private companion object {
        const val WORLD_EPOCH = 9L
        val TRANSITION = TerrainCoverageTransitionPolicy(true, 1)
    }
}
