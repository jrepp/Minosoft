/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DistantSourceAuthorityTest {
    @Test
    fun `another world epoch rejects the complete update atomically`() {
        val active = page("a" to record(digest = "active"))
        val outcome = DistantSourceMerger.merge(
            active,
            mapOf(
                "a" to record(authority = DistantSourceAuthority.OBSERVED, digest = "replacement"),
                "b" to record(worldEpoch = 8L, digest = "wrong-world"),
            ),
        )

        val rejected = assertIs<DistantPageMergeOutcome.RejectedWorldEpoch<String>>(outcome)
        assertSame(active, rejected.active)
        assertEquals(setOf(8L), rejected.rejectedEpochs)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (rejected.rejectedEpochs as MutableSet<Long>).add(9L)
        }
        assertEquals(setOf("a"), active.records.keys)
    }

    @Test
    fun `complete higher authority replaces while lower authority does not`() {
        val cache = record(authority = DistantSourceAuthority.CACHE, digest = "cache")
        val remote = record(authority = DistantSourceAuthority.REMOTE_AUTHORITY, digest = "remote")
        val observed = record(authority = DistantSourceAuthority.OBSERVED, digest = "observed")

        val promoted = assertIs<DistantPageMergeOutcome.Published<String>>(
            DistantSourceMerger.merge(page("column" to cache), mapOf("column" to remote)),
        )
        assertEquals(remote, promoted.published.records.getValue("column"))

        val retained = assertIs<DistantPageMergeOutcome.Unchanged<String>>(
            DistantSourceMerger.merge(page("column" to observed), mapOf("column" to remote)),
        )
        assertEquals(observed, retained.active.records.getValue("column"))
    }

    @Test
    fun `lower authority fills a genuinely missing value`() {
        val active = page("present" to record(authority = DistantSourceAuthority.OBSERVED))
        val cached = record(authority = DistantSourceAuthority.CACHE, digest = "cached")

        val outcome = assertIs<DistantPageMergeOutcome.Published<String>>(
            DistantSourceMerger.merge(active, mapOf("missing" to cached)),
        )

        assertEquals(cached, outcome.published.records.getValue("missing"))
        assertEquals(active.records.getValue("present"), outcome.published.records.getValue("present"))
    }

    @Test
    fun `newer equal authority source revision replaces the active value`() {
        val activeRecord = record(
            authority = DistantSourceAuthority.LOCAL_AUTHORITY,
            sourceRevision = 4L,
            digest = "old",
        )
        val newer = record(
            authority = DistantSourceAuthority.LOCAL_AUTHORITY,
            sourceRevision = 5L,
            digest = "new",
        )

        val outcome = assertIs<DistantPageMergeOutcome.Published<String>>(
            DistantSourceMerger.merge(page("run" to activeRecord), mapOf("run" to newer)),
        )

        assertEquals(newer, outcome.published.records.getValue("run"))
    }

    @Test
    fun `equal revision content conflict reports and retains active value`() {
        val activeRecord = record(
            authority = DistantSourceAuthority.REMOTE_AUTHORITY,
            sourceRevision = 9L,
            sourceSequence = 3L,
            digest = "active",
        )
        val conflicting = activeRecord.copy(semanticContentDigest = digest("conflicting"))
        val active = page("run" to activeRecord)

        val outcome = assertIs<DistantPageMergeOutcome.Unchanged<String>>(
            DistantSourceMerger.merge(active, mapOf("run" to conflicting)),
        )

        assertSame(active, outcome.active)
        assertEquals(activeRecord, outcome.active.records.getValue("run"))
        assertEquals(
            DistantSourceConflict("run", activeRecord, conflicting),
            outcome.conflicts.single(),
        )
    }

    @Test
    fun `no change retains identity while publication creates one immutable revision`() {
        val active = page("a" to record(digest = "a"))
        val unchanged = assertIs<DistantPageMergeOutcome.Unchanged<String>>(
            DistantSourceMerger.merge(
                active,
                mapOf("a" to record(sourceRevision = 0L, digest = "stale")),
            ),
        )
        assertSame(active, unchanged.active)

        val mutableUpdates = linkedMapOf("b" to record(digest = "b"))
        val changed = assertIs<DistantPageMergeOutcome.Published<String>>(
            DistantSourceMerger.merge(active, mutableUpdates),
        )
        mutableUpdates.clear()

        assertSame(active, changed.active)
        assertNotSame(active, changed.published)
        assertEquals(active.revision + 1L, changed.published.revision)
        assertEquals(setOf("a"), active.records.keys)
        assertEquals(setOf("a", "b"), changed.published.records.keys)
        assertTrue(changed.conflicts.isEmpty())
    }

    private fun page(vararg records: Pair<String, DistantSourceRecord>) =
        DistantPageRevision(WORLD_EPOCH, 11L, mapOf(*records))

    private fun record(
        worldEpoch: Long = WORLD_EPOCH,
        authority: DistantSourceAuthority = DistantSourceAuthority.CACHE,
        sourceRevision: Long = 1L,
        sourceSequence: Long? = null,
        digest: String = "content",
    ) = DistantSourceRecord(
        worldEpoch = worldEpoch,
        authority = authority,
        originalProvenance = DistantSourceProvenance(DistantSourceKind.LOCAL_AUTHORITY, "fixture"),
        sourceDataRevision = sourceRevision,
        sourceSequence = sourceSequence,
        completeness = DistantSourceCompleteness.COMPLETE,
        generationStatus = DistantSourceGenerationStatus.GENERATED,
        capturedAtMillis = 1_000L,
        dataSchemaVersion = 1,
        detailLevel = 0,
        semanticContentDigest = digest(digest),
    )

    private fun digest(value: String) = DistantSemanticDigest(
        schemaVersion = 1,
        algorithm = "fixture",
        encodedValue = value,
    )

    private companion object {
        const val WORLD_EPOCH = 7L
    }
}
