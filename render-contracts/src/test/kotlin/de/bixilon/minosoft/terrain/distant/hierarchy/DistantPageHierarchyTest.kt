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
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DistantPageHierarchyTest {
    @Test
    fun `meshing neighbours prefer same detail coverage`() {
        val subject = key(0, 0, detail = 2)
        val sameDetail = key(1, 0, detail = 2)
        val finer = listOf(key(2, 0, detail = 1), key(2, 1, detail = 1))

        val resolved = DistantPageHierarchy.resolveMeshingNeighbours(
            subject,
            (finer + sameDetail).toSet(),
        )

        assertEquals(listOf(sameDetail), resolved)
    }

    @Test
    fun `meshing neighbours use finer pages along a missing coarse boundary`() {
        val subject = key(0, 0, detail = 2)
        val finer = listOf(key(2, 0, detail = 1), key(2, 1, detail = 1))

        assertEquals(
            finer,
            DistantPageHierarchy.resolveMeshingNeighbours(subject, finer.toSet()),
        )
        assertTrue(subject in DistantPageHierarchy.meshingDependents(finer.first(), (finer + subject).toSet()))
    }

    @Test
    fun `meshing neighbours fall back to an adjacent parent without overlap`() {
        val subject = key(1, 0, detail = 1)
        val coarser = key(1, 0, detail = 2)

        assertEquals(
            listOf(coarser),
            DistantPageHierarchy.resolveMeshingNeighbours(subject, setOf(coarser)),
        )
        assertEquals(
            listOf(subject),
            DistantPageHierarchy.meshingDependents(coarser, setOf(subject, coarser)),
        )
    }

    @Test
    fun `negative-coordinate mixed detail boundary uses floor-aligned children`() {
        val subject = key(-1, -1, detail = 2)
        val finer = listOf(key(0, -2, detail = 1), key(0, -1, detail = 1))

        assertEquals(
            finer,
            DistantPageHierarchy.resolveMeshingNeighbours(subject, finer.toSet()),
        )
    }

    @Test
    fun `lineage rejects an unrepresentable detail level before allocating`() {
        assertThrows<IllegalArgumentException> {
            DistantPageHierarchy.lineage(key(0, 0), DistantPageHierarchy.MAXIMUM_DETAIL_LEVEL + 1)
        }
    }

    @Test
    fun `negative coordinates use floor division and checked inverse children`() {
        val child = key(-1, -1)
        val parent = DistantPageHierarchy.parent(child)

        assertEquals(key(-1, -1, detail = 1), parent)
        assertEquals(
            listOf(key(-2, -2), key(-1, -2), key(-2, -1), key(-1, -1)),
            DistantPageHierarchy.children(parent),
        )
        assertTrue(child in DistantPageHierarchy.children(parent))
    }

    @Test
    fun `parent completeness and digest derive from all four children`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 1, maximumPages = 16)
        val parent = key(0, 0, detail = 1)

        publish(index, key(0, 0), 1, "a")
        val partial = index[parent]!!
        assertEquals(DistantSourceCompleteness.PARTIAL, partial.completeness)
        assertEquals(0b0001, partial.presentChildMask)
        assertEquals(0b0001, partial.completeChildMask)
        val partialDigest = partial.semanticDigest

        publish(index, key(1, 0), 1, "b")
        publish(index, key(0, 1), 1, "c")
        publish(index, key(1, 1), 1, "d")

        val complete = index[parent]!!
        assertEquals(DistantSourceCompleteness.COMPLETE, complete.completeness)
        assertEquals(DistantHierarchyPage.CHILD_MASK, complete.presentChildMask)
        assertEquals(DistantHierarchyPage.CHILD_MASK, complete.completeChildMask)
        assertNotEquals(partialDigest, complete.semanticDigest)
    }

    @Test
    fun `one base change dirties only lineage and present stitch neighbours`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 2, maximumPages = 64)
        val target = key(0, 0)
        val neighbourKeys = listOf(key(-1, 0), key(1, 0), key(0, -1), key(0, 1))
        publish(index, target, 1, "target-1")
        neighbourKeys.forEachIndexed { offset, page -> publish(index, page, 1, "near-$offset") }
        val far = key(16, 16)
        publish(index, far, 1, "far")
        makeEveryPageCurrent(index)

        val outcome = publish(index, target, 2, "target-2")
        val changed = outcome.dirtiedPages.toSet()
        val lineage = DistantPageHierarchy.lineage(target, 2)

        assertTrue(lineage.all(changed::contains))
        assertTrue(neighbourKeys.all(changed::contains))
        assertFalse(far in changed)
        assertFalse(DistantPageHierarchy.parent(far) in changed)
        assertFalse(DistantPageHierarchy.parent(DistantPageHierarchy.parent(far)) in changed)
        assertTrue(index.snapshot().pages.getValue(far).dirty.not())
        assertTrue(changed.size < index.snapshot().pages.size)
    }

    @Test
    fun `stale render cannot replace current source and replacement retains active artifact`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 0, maximumPages = 4)
        val page = key(3, 5)
        val first = publish(index, page, 5, "first").snapshot.pages.getValue(page)
        publish(index, page, 6, "second")

        assertIs<DistantRenderPublication.Stale>(
            index.publishRender(page, first.sourceRevision, first.dirtyRevision),
        )
        val current = index[page]!!
        val accepted = assertIs<DistantRenderPublication.Published>(
            index.publishRender(page, current.sourceRevision, current.dirtyRevision),
        ).page
        assertEquals(1L, accepted.renderRevision)
        assertFalse(accepted.dirty)

        publish(index, page, 7, "third")
        val replacement = index[page]!!
        assertTrue(replacement.dirty)
        assertTrue(replacement.hasActiveArtifact)
        assertEquals(accepted.renderRevision, replacement.renderRevision)
        assertEquals(accepted.renderedDirtyRevision, replacement.renderedDirtyRevision)
    }

    @Test
    fun `capacity and equal revision conflict are atomic`() {
        val tooSmall = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 2, maximumPages = 2)
        assertIs<DistantSourcePublication.CapacityRejected>(
            tooSmall.publishSource(key(0, 0), 1, DistantSourceCompleteness.COMPLETE, digest("one")),
        )
        assertTrue(tooSmall.snapshot().pages.isEmpty())
        assertEquals(0L, tooSmall.snapshot().revision)

        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 0, maximumPages = 1)
        val published = publish(index, key(0, 0), 4, "one")
        val before = published.snapshot
        assertIs<DistantSourcePublication.Conflict>(
            index.publishSource(key(0, 0), 4, DistantSourceCompleteness.COMPLETE, digest("two")),
        )
        assertEquals(before.revision, index.snapshot().revision)
        assertEquals(digest("one"), index[key(0, 0)]!!.semanticDigest)
    }

    @Test
    fun `snapshot-free source mutation preserves the same indexed state`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 1, maximumPages = 8)
        val page = key(2, 3)

        val mutation = index.publishSourceMutation(
            page,
            sourceRevision = 4,
            completeness = DistantSourceCompleteness.COMPLETE,
            semanticDigest = digest("source"),
        )

        assertEquals(DistantSourceMutationStatus.PUBLISHED, mutation.status)
        assertEquals(DistantPageHierarchy.lineage(page, 1), mutation.sourceChangedPages)
        assertTrue(page in mutation.dirtiedPages)
        assertEquals(2, index.snapshot().pages.size)
    }

    @Test
    fun `in-place render state publication does not mutate prior snapshots`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 0, maximumPages = 1)
        val page = key(2, 3)
        publish(index, page, 1, "source")
        val before = index.snapshot()
        val active = index[page]!!

        assertIs<DistantRenderPublication.Published>(
            index.publishRender(page, active.sourceRevision, active.dirtyRevision),
        )

        assertTrue(before.pages.getValue(page).dirty)
        assertFalse(index.snapshot().pages.getValue(page).dirty)
    }

    @Test
    fun `in-place source publication does not mutate prior snapshots`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 1, maximumPages = 8)
        val page = key(2, 3)
        publish(index, page, 1, "first")
        val before = index.snapshot()

        val mutation = index.publishSourceMutation(
            page,
            sourceRevision = 2,
            completeness = DistantSourceCompleteness.PARTIAL,
            semanticDigest = digest("second"),
        )

        assertEquals(DistantSourceMutationStatus.PUBLISHED, mutation.status)
        assertEquals(1L, before.pages.getValue(page).sourceRevision)
        assertEquals(digest("first"), before.pages.getValue(page).semanticDigest)
        assertEquals(2L, index[page]!!.sourceRevision)
        assertEquals(digest("second"), index[page]!!.semanticDigest)
    }

    @Test
    fun `batch source publication derives the same complete hierarchy atomically`() {
        val sequential = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 2, maximumPages = 32)
        val batched = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 2, maximumPages = 32)
        val children = listOf(key(0, 0), key(1, 0), key(0, 1), key(1, 1))
        children.forEachIndexed { offset, child -> publish(sequential, child, offset + 1L, "child-$offset") }

        val publication = assertIs<DistantSourceBatchMutation.Published>(
            batched.publishSourceBatchMutation(children.mapIndexed { offset, child ->
                DistantSourcePageUpdate(
                    child,
                    offset + 1L,
                    DistantSourceCompleteness.COMPLETE,
                    digest("child-$offset"),
                )
            }),
        )

        assertEquals(children, publication.publishedPages)
        assertEquals(sequential.snapshot().sourcePublicationRevision, batched.snapshot().sourcePublicationRevision)
        assertEquals(sequential.snapshot().pages.keys, batched.snapshot().pages.keys)
        for (page in sequential.snapshot().pages.keys) {
            assertEquals(sequential[page]!!.semanticDigest, batched[page]!!.semanticDigest)
            assertEquals(sequential[page]!!.completeness, batched[page]!!.completeness)
            assertEquals(sequential[page]!!.presentChildMask, batched[page]!!.presentChildMask)
            assertEquals(sequential[page]!!.completeChildMask, batched[page]!!.completeChildMask)
        }
    }

    @Test
    fun `batch source rejection leaves the complete index unchanged`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 1, maximumPages = 8)
        val active = key(0, 0)
        publish(index, active, 2, "active")
        val before = index.snapshot()

        val rejection = assertIs<DistantSourceBatchMutation.Rejected>(
            index.publishSourceBatchMutation(
                listOf(
                    DistantSourcePageUpdate(key(2, 0), 1, DistantSourceCompleteness.COMPLETE, digest("new")),
                    DistantSourcePageUpdate(active, 1, DistantSourceCompleteness.COMPLETE, digest("stale")),
                ),
            ),
        )

        assertEquals(DistantSourceMutationStatus.STALE, rejection.status)
        assertEquals(before.revision, index.snapshot().revision)
        assertEquals(before.pages, index.snapshot().pages)
    }

    @Test
    fun `build lifecycle is identity checked and failure returns to dirty`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 0, maximumPages = 1)
        val current = publish(index, key(0, 0), 1, "one").snapshot.pages.getValue(key(0, 0))

        assertFalse(index.markRequested(current.key, current.dirtyRevision + 1))
        assertTrue(index.markRequested(current.key, current.dirtyRevision))
        assertTrue(index.markBuilding(current.key, current.dirtyRevision))
        assertTrue(index.markBuildFailed(current.key, current.dirtyRevision))
        assertEquals(DistantPageBuildState.DIRTY, index[current.key]!!.buildState)
    }

    @Test
    fun `source removal updates ancestors and releases bounded capacity atomically`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 1, maximumPages = 5)
        val children = listOf(key(0, 0), key(1, 0), key(0, 1), key(1, 1))
        children.forEachIndexed { offset, child -> publish(index, child, 1, "child-$offset") }
        val parent = key(0, 0, detail = 1)
        assertEquals(DistantSourceCompleteness.COMPLETE, index[parent]!!.completeness)

        val firstRemoval = assertIs<DistantSourceRemoval.Removed>(index.removeSource(children.first()))
        assertTrue(children.first() in firstRemoval.removedPages)
        assertEquals(null, index[children.first()])
        assertEquals(DistantSourceCompleteness.PARTIAL, index[parent]!!.completeness)
        assertTrue(index[parent]!!.derived)

        children.drop(1).forEach(index::removeSource)
        assertTrue(index.snapshot().pages.isEmpty())

        assertIs<DistantSourcePublication.Published>(
            index.publishSource(key(8, 8), 1, DistantSourceCompleteness.COMPLETE, digest("replacement")),
        )
        assertEquals(2, index.snapshot().pages.size)
    }

    @Test
    fun `render dependency invalidation is exact and preserves source revisions`() {
        val index = DistantPageHierarchyIndex(WORLD_EPOCH, maximumDetailLevel = 0, maximumPages = 3)
        val pages = listOf(key(0, 0), key(1, 0), key(8, 8))
        pages.forEachIndexed { offset, page -> publish(index, page, 1, "page-$offset") }
        makeEveryPageCurrent(index)
        val before = index.snapshot().pages.mapValues { it.value.sourceRevision }

        val invalidation = index.invalidateRender(listOf(pages[1], key(99, 99)))

        assertEquals(listOf(pages[1]), invalidation.dirtiedPages)
        assertFalse(index[pages[0]]!!.dirty)
        assertTrue(index[pages[1]]!!.dirty)
        assertFalse(index[pages[2]]!!.dirty)
        assertEquals(before, index.snapshot().pages.mapValues { it.value.sourceRevision })
    }

    private fun makeEveryPageCurrent(index: DistantPageHierarchyIndex) {
        for (page in index.snapshot().dirtyPages) {
            val active = index[page]!!
            assertIs<DistantRenderPublication.Published>(
                index.publishRender(page, active.sourceRevision, active.dirtyRevision),
            )
        }
        assertTrue(index.snapshot().dirtyPages.isEmpty())
    }

    private fun publish(
        index: DistantPageHierarchyIndex,
        page: TerrainPageKey,
        revision: Long,
        value: String,
    ): DistantSourcePublication.Published = assertIs(
        index.publishSource(page, revision, DistantSourceCompleteness.COMPLETE, digest(value)),
    )

    private fun digest(value: String) = DistantSemanticDigest(1, "test", value)

    private fun key(x: Long, z: Long, detail: Int = 0) =
        TerrainPageKey(TerrainDomain.DISTANT, detail, x, 0L, z, WORLD_EPOCH)

    private companion object {
        const val WORLD_EPOCH = 7L
    }
}
