/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.distant.DistantSemanticDigest
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageCell
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoveragePageMask
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTransitionPolicy
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistantPageSelectorTest {
    @Test
    fun `partial near ownership refines only the overlapping hierarchy path`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val root = key(0, 0, detail = 2)
        val coverage = coverage(
            revision = 15L,
            covered = (0L until 4L).flatMap { z ->
                (0L until 4L).map { x -> x to z }
            }.dropLast(1),
        )
        val semanticRevisions = revisionSignature(index.snapshot())
        val selection = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(),
            emptyMap(),
            request(listOf(root), cameraX = 10_000.0),
            nearCoverage = coverage,
        )
        val mask = TerrainCoveragePageMask(coverage, TerrainCoverageTransitionPolicy(true, 1))
        val drawable = selection.pages.filter(mask::drawDistant)

        assertEquals(7, selection.pages.size)
        assertEquals(listOf(key(3, 3)), drawable)
        assertEquals(6, selection.pages.size - drawable.size)
        assertTrue(selection.unresolvedPartialCoveragePages.isEmpty())
        assertEquals(semanticRevisions, revisionSignature(index.snapshot()))
        assertTrue(selection.maximumAdjacentDetailDelta <= 1)
        assertTrue((0L until 4L).all { z ->
            (0L until 4L).all { x -> selection.pages.any { it.coversBasePage(x, z) } }
        })
        assertTrue(coverage.cells.all { near ->
            drawable.none { it.coversBasePage(near.page.x, near.page.z) }
        })
    }

    @Test
    fun `checkerboard ownership refines to base pages while full and empty spans stay coarse`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val root = key(0, 0, detail = 2)
        val empty = coverage(revision = 0L, covered = emptyList())
        val full = coverage(
            revision = 16L,
            covered = (0L until 4L).flatMap { z -> (0L until 4L).map { x -> x to z } },
        )
        val checkerboard = coverage(
            revision = 8L,
            covered = (0L until 4L).flatMap { z ->
                (0L until 4L).mapNotNull { x -> (x to z).takeIf { (x + z) % 2L == 0L } }
            },
        )

        assertEquals(
            listOf(root),
            DistantPageSelector(WORLD_EPOCH).select(
                index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0), nearCoverage = empty,
            ).pages,
        )
        val fullSelection = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0), nearCoverage = full,
        )
        assertEquals(listOf(root), fullSelection.pages)
        assertTrue(
            fullSelection.pages.none(
                TerrainCoveragePageMask(full, TerrainCoverageTransitionPolicy(true, 1))::drawDistant,
            ),
        )
        val checkerboardSelection = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0), nearCoverage = checkerboard,
        )
        assertEquals(16, checkerboardSelection.pages.size)
        assertTrue(checkerboardSelection.pages.all { it.detailLevel == 0 })
    }

    @Test
    fun `unavailable child retains and reports a conservative partial page`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val snapshot = index.snapshot()
        val root = key(0, 0, detail = 2)
        val unavailableChild = key(1, 1, detail = 1)
        val available = snapshot.pages.keys - unavailableChild
        val partial = coverage(revision = 1L, covered = listOf(0L to 0L))
        val selection = DistantPageSelector(WORLD_EPOCH).select(
            snapshot,
            emptyMap(),
            request(listOf(root), cameraX = 10_000.0, availablePages = available),
            nearCoverage = partial,
        )

        assertEquals(listOf(root), selection.pages)
        assertEquals(listOf(root), selection.unresolvedPartialCoveragePages)
    }

    @Test
    fun `same pinned coverage is deterministic for main and shadow and changed coverage refines`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val root = key(0, 0, detail = 2)
        val request = request(listOf(root), cameraX = 10_000.0)
        val empty = coverage(revision = 0L, covered = emptyList())
        val partial = coverage(revision = 1L, covered = listOf(0L to 0L))
        val main = DistantPageSelector(WORLD_EPOCH)
        val shadow = DistantPageSelector(WORLD_EPOCH)

        val before = main.select(index.snapshot(), emptyMap(), request, nearCoverage = empty)
        val after = main.select(index.snapshot(), emptyMap(), request, nearCoverage = partial)
        val repeated = main.select(index.snapshot(), emptyMap(), request, nearCoverage = partial)
        val shadowAfter = shadow.select(index.snapshot(), emptyMap(), request, nearCoverage = partial)

        assertEquals(listOf(root), before.pages)
        assertTrue(after.generation > before.generation)
        assertEquals(after.pages, repeated.pages)
        assertEquals(after.generation, repeated.generation)
        assertEquals(after.pages, shadowAfter.pages)
    }

    @Test
    fun `camera movement changes selection without changing page revisions`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val root = key(0, 0, detail = 2)
        val selector = DistantPageSelector(WORLD_EPOCH)
        val before = revisionSignature(index.snapshot())

        val near = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 1.0))
        assertEquals(16, near.pages.size)
        assertTrue(near.pages.all { it.detailLevel == 0 })

        val far = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0))
        assertEquals(listOf(root), far.pages)
        assertTrue(far.generation > near.generation)
        assertEquals(before, revisionSignature(index.snapshot()))
    }

    @Test
    fun `separate refine and coarsen thresholds prevent oscillation`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val root = key(0, 0, detail = 2)
        val selector = DistantPageSelector(WORLD_EPOCH)

        val coarse = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0))
        assertEquals(listOf(root), coarse.pages)
        val middleWhileCoarse = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 1_400.0))
        assertEquals(listOf(root), middleWhileCoarse.pages)

        val refined = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 1.0))
        assertTrue(refined.pages.size > 1)
        val middleWhileRefined = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 1_400.0))
        assertTrue(middleWhileRefined.pages.size > 1)
        val repeatedMiddle = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 1_400.0))
        assertEquals(middleWhileRefined.pages, repeatedMiddle.pages)

        val coarsened = selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0))
        assertEquals(listOf(root), coarsened.pages)
    }

    @Test
    fun `visit and change budgets retain a conservative accepted subtree`() {
        val index = completeIndex(width = 4, height = 4, maximumDetail = 2)
        val root = key(0, 0, detail = 2)
        val selector = DistantPageSelector(WORLD_EPOCH)
        selector.select(index.snapshot(), emptyMap(), request(listOf(root), cameraX = 10_000.0))

        val visitBounded = selector.select(
            index.snapshot(),
            emptyMap(),
            request(listOf(root), cameraX = 1.0, maximumVisits = 1),
        )
        assertTrue(visitBounded.visitBudgetExhausted)
        assertEquals(listOf(root), visitBounded.pages)

        val changeBounded = selector.select(
            index.snapshot(),
            emptyMap(),
            request(listOf(root), cameraX = 1.0, maximumChanges = 4),
        )
        assertTrue(changeBounded.changeBudgetExhausted)
        assertEquals(listOf(root), changeBounded.pages)
        assertEquals(0, changeBounded.selectionChanges)
    }

    @Test
    fun `adjacent selected pages differ by at most one detail level`() {
        val index = completeIndex(width = 16, height = 8, maximumDetail = 3)
        val leftRoot = key(0, 0, detail = 3)
        val rightRoot = key(1, 0, detail = 3)
        val metadata = index.snapshot().pages.keys.associateWith { page ->
            val baseMinimumX = page.x shl page.detailLevel
            DistantPageSelectionMetadata(
                geometricErrorBlocks = if (baseMinimumX < 8L) 1_000_000.0 else 0.0,
                minimumY = 0,
                maximumYExclusive = 256,
            )
        }

        val selection = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(),
            metadata,
            request(listOf(leftRoot, rightRoot), cameraX = 10_000.0, maximumChanges = 1_000),
        )

        assertEquals(1, selection.maximumAdjacentDetailDelta)
        assertTrue(selection.pages.any { it.detailLevel == 0 })
        assertTrue(selection.pages.any { it.detailLevel > 0 })
        assertFalse(selection.visitBudgetExhausted)
    }

    @Test
    fun `mixed detail roots are balanced before budgeted refinement`() {
        val index = completeIndex(width = 16, height = 8, maximumDetail = 3)
        val coarse = key(0, 0, detail = 3)
        val fine = key(8, 0, detail = 0)

        val selection = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(),
            emptyMap(),
            request(listOf(coarse, fine), cameraX = 10_000.0),
            maximumPages = 64,
        )

        assertTrue(selection.pages.size in 3..64)
        assertTrue(selection.maximumAdjacentDetailDelta <= 1)
        assertTrue((0L until 8L).all { z ->
            (0L until 8L).all { x -> selection.pages.any { it.coversBasePage(x, z) } }
        })
        assertTrue(selection.pages.any { it.coversBasePage(8L, 0L) })
    }

    @Test
    fun `budgeted selection rejects a root set whose mandatory balance exceeds the budget`() {
        val index = completeIndex(width = 16, height = 8, maximumDetail = 3)
        val coarse = key(0, 0, detail = 3)
        val fine = key(8, 0, detail = 0)

        assertFailsWith<IllegalArgumentException> {
            DistantPageSelector(WORLD_EPOCH).select(
                index.snapshot(),
                emptyMap(),
                request(listOf(coarse, fine), cameraX = 10_000.0),
                maximumPages = 2,
            )
        }
    }

    @Test
    fun `page budget coarsens a previously refined selection without losing coverage`() {
        val index = completeIndex(width = 16, height = 16, maximumDetail = 4)
        val root = key(0, 0, detail = 4)
        val selector = DistantPageSelector(WORLD_EPOCH)
        val request = request(
            roots = listOf(root),
            cameraX = 1.0,
            maximumVisits = 10_000,
            maximumChanges = 10_000,
        )

        val refined = selector.select(index.snapshot(), emptyMap(), request)
        assertEquals(256, refined.pages.size)

        val bounded = selector.select(index.snapshot(), emptyMap(), request, maximumPages = 16)

        assertTrue(bounded.pages.size in 12..16)
        assertTrue((0L until 16L).all { z ->
            (0L until 16L).all { x -> bounded.pages.any { it.coversBasePage(x, z) } }
        })
        assertTrue(bounded.maximumAdjacentDetailDelta <= 1)
        assertTrue(bounded.generation > refined.generation)
    }

    @Test
    fun `page budget remains a hard limit when coarsening exceeds the change budget`() {
        val index = completeIndex(width = 16, height = 16, maximumDetail = 4)
        val root = key(0, 0, detail = 4)
        val selector = DistantPageSelector(WORLD_EPOCH)
        val refined = selector.select(
            index.snapshot(),
            emptyMap(),
            request(roots = listOf(root), cameraX = 1.0, maximumVisits = 10_000, maximumChanges = 10_000),
        )
        assertEquals(256, refined.pages.size)

        val bounded = selector.select(
            index.snapshot(),
            emptyMap(),
            request(roots = listOf(root), cameraX = 1.0, maximumVisits = 10_000, maximumChanges = 1),
            maximumPages = 16,
        )

        assertTrue(bounded.changeBudgetExhausted)
        assertTrue(bounded.pages.size <= 16)
        assertTrue((0L until 16L).all { z ->
            (0L until 16L).all { x -> bounded.pages.any { it.coversBasePage(x, z) } }
        })
    }

    @Test
    fun `large quality search balances violations in bounded passes`() {
        val index = completeIndex(width = 64, height = 32, maximumDetail = 5)
        val roots = listOf(key(0, 0, detail = 5), key(1, 0, detail = 5))
        val metadata = index.snapshot().pages.keys.associateWith { page ->
            val baseMinimumX = page.x shl page.detailLevel
            DistantPageSelectionMetadata(
                geometricErrorBlocks = if (baseMinimumX < 32L) 1_000_000.0 else 0.0,
                minimumY = 0,
                maximumYExclusive = 256,
            )
        }

        val selection = DistantPageSelector(WORLD_EPOCH).select(
            index.snapshot(),
            metadata,
            request(
                roots = roots,
                cameraX = 10_000.0,
                maximumVisits = 50_000,
                maximumChanges = 50_000,
            ),
            maximumPages = 1_024,
        )

        assertTrue(selection.pages.size <= 1_024)
        assertTrue(selection.maximumAdjacentDetailDelta <= 1)
        assertFalse(selection.visitBudgetExhausted)
    }

    private fun completeIndex(width: Int, height: Int, maximumDetail: Int): DistantPageHierarchyIndex {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetail, maximumPages = width * height * 2)
        var revision = 1L
        for (z in 0 until height) {
            for (x in 0 until width) {
                index.publishSource(
                    key(x.toLong(), z.toLong()),
                    sourceRevision = revision,
                    completeness = DistantSourceCompleteness.COMPLETE,
                    semanticDigest = DistantSemanticDigest(1, "test", "page-${revision++}"),
                )
            }
        }
        return index
    }

    private fun request(
        roots: List<TerrainPageKey>,
        cameraX: Double,
        maximumVisits: Int = 1_000,
        maximumChanges: Int = 1_000,
        availablePages: Collection<TerrainPageKey>? = null,
    ) = DistantPageSelectionRequest(
        camera = DistantSelectionCamera(cameraX, 100.0, 1.0, verticalFieldOfViewDegrees = 90.0),
        viewportHeightPixels = 1_000,
        basePageSizeBlocks = 16,
        roots = roots,
        refineErrorPixels = 8.0,
        coarsenErrorPixels = 4.0,
        maximumNodeVisits = maximumVisits,
        maximumSelectionChanges = maximumChanges,
        availablePages = availablePages,
    )

    private fun coverage(
        revision: Long,
        covered: Collection<Pair<Long, Long>>,
    ) = TerrainCoverageSnapshot(
        worldEpoch = WORLD_EPOCH,
        revision = revision,
        providerGeneration = 1L,
        cells = covered.map { (x, z) ->
            TerrainCoverageCell(
                page = TerrainPageKey(TerrainDomain.NEAR, 0, x, 0L, z, WORLD_EPOCH),
                state = TerrainCoverageState.READY,
                surfaceRelevant = true,
                transitionAgeFrames = 100,
                contributesCoverage = true,
                coverageAgeFrames = 100,
            )
        },
    )

    private fun revisionSignature(snapshot: DistantPageIndexSnapshot) = snapshot.pages.mapValues { (_, page) ->
        listOf(page.sourceRevision, page.dirtyRevision, page.renderRevision)
    }

    private fun key(x: Long, z: Long, detail: Int = 0) =
        TerrainPageKey(TerrainDomain.DISTANT, detail, x, 0L, z, WORLD_EPOCH)

    private fun TerrainPageKey.coversBasePage(baseX: Long, baseZ: Long): Boolean {
        val span = 1L shl detailLevel
        return baseX in x * span until (x + 1L) * span &&
            baseZ in z * span until (z + 1L) * span
    }

    private companion object {
        const val WORLD_EPOCH = 19L
    }
}
