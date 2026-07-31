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

package de.bixilon.minosoft.terrain.distant

import java.util.Collections
import java.util.SortedMap
import java.util.TreeMap
import java.util.TreeSet

enum class DistantSourceAuthority(
    internal val precedence: Int,
) {
    CACHE(0),
    LOCAL_AUTHORITY(1),
    REMOTE_AUTHORITY(2),
    OBSERVED(3),
}

enum class DistantSourceKind {
    OBSERVATION,
    REMOTE_AUTHORITY,
    LOCAL_AUTHORITY,
    PERSISTENCE,
    IMPORT,
    DERIVED,
}

data class DistantSourceProvenance(
    val kind: DistantSourceKind,
    val sourceId: String? = null,
) {
    init {
        require(sourceId == null || sourceId.isNotBlank()) {
            "Distant source identifier must not be blank"
        }
    }
}

enum class DistantSourceCompleteness {
    PARTIAL,
    COMPLETE,
}

enum class DistantSourceGenerationStatus {
    OBSERVED,
    RECEIVED,
    GENERATED,
    RESTORED,
    IMPORTED,
    DERIVED,
}

data class DistantSemanticDigest(
    val schemaVersion: Int,
    val algorithm: String,
    val encodedValue: String,
) {
    init {
        require(schemaVersion > 0) { "Distant semantic-digest schema version must be positive" }
        require(algorithm.isNotBlank()) { "Distant semantic-digest algorithm must not be blank" }
        require(encodedValue.isNotBlank()) { "Distant semantic digest must not be blank" }
    }
}

/**
 * Authority metadata for one present column or vertical run.
 *
 * A genuinely missing value is represented by the absence of a record in its
 * [DistantPageRevision], not by a sentinel digest or nullable authority.
 */
data class DistantSourceRecord(
    val worldEpoch: Long,
    val authority: DistantSourceAuthority,
    val originalProvenance: DistantSourceProvenance,
    val sourceDataRevision: Long,
    val sourceSequence: Long?,
    val completeness: DistantSourceCompleteness,
    val generationStatus: DistantSourceGenerationStatus,
    val capturedAtMillis: Long?,
    val dataSchemaVersion: Int,
    val detailLevel: Int,
    val semanticContentDigest: DistantSemanticDigest,
) {
    init {
        require(worldEpoch >= 0L) { "Distant source world epoch must not be negative" }
        require(sourceDataRevision >= 0L) { "Distant source-data revision must not be negative" }
        require(sourceSequence == null || sourceSequence >= 0L) {
            "Distant source sequence must not be negative"
        }
        require(capturedAtMillis == null || capturedAtMillis >= 0L) {
            "Distant source capture timestamp must not be negative"
        }
        require(dataSchemaVersion > 0) { "Distant data schema version must be positive" }
        require(detailLevel >= 0) { "Distant source detail level must not be negative" }
    }
}

/**
 * One immutable publication of the source records belonging to a distant page.
 */
class DistantPageRevision<K : Comparable<K>>(
    val worldEpoch: Long,
    val revision: Long,
    records: Map<K, DistantSourceRecord>,
) {
    val records: SortedMap<K, DistantSourceRecord> =
        Collections.unmodifiableSortedMap(TreeMap(records))

    init {
        require(worldEpoch >= 0L) { "Distant page world epoch must not be negative" }
        require(revision >= 0L) { "Distant page revision must not be negative" }
        require(this.records.values.all { it.worldEpoch == worldEpoch }) {
            "Every distant source record must belong to page world epoch $worldEpoch"
        }
    }

    internal fun publish(records: Map<K, DistantSourceRecord>): DistantPageRevision<K> =
        DistantPageRevision(worldEpoch, Math.addExact(revision, 1L), records)
}

data class DistantSourceConflict<K>(
    val key: K,
    val active: DistantSourceRecord,
    val incoming: DistantSourceRecord,
)

sealed interface DistantPageMergeOutcome<K : Comparable<K>> {
    val active: DistantPageRevision<K>

    class RejectedWorldEpoch<K : Comparable<K>>(
        override val active: DistantPageRevision<K>,
        rejectedEpochs: Set<Long>,
    ) : DistantPageMergeOutcome<K> {
        val rejectedEpochs: Set<Long> =
            Collections.unmodifiableSet(TreeSet(rejectedEpochs))

        init {
            require(this.rejectedEpochs.isNotEmpty()) { "Rejected epoch set must not be empty" }
            require(active.worldEpoch !in this.rejectedEpochs) {
                "Rejected epochs must differ from the active world epoch"
            }
        }
    }

    class Unchanged<K : Comparable<K>>(
        override val active: DistantPageRevision<K>,
        conflicts: List<DistantSourceConflict<K>>,
    ) : DistantPageMergeOutcome<K> {
        val conflicts: List<DistantSourceConflict<K>> =
            Collections.unmodifiableList(ArrayList(conflicts))
    }

    class Published<K : Comparable<K>>(
        override val active: DistantPageRevision<K>,
        val published: DistantPageRevision<K>,
        conflicts: List<DistantSourceConflict<K>>,
    ) : DistantPageMergeOutcome<K> {
        val conflicts: List<DistantSourceConflict<K>> =
            Collections.unmodifiableList(ArrayList(conflicts))
    }
}

object DistantSourceMerger {
    /**
     * Atomically applies a set of column/run records to one active page.
     *
     * An update set containing any other world epoch is rejected as a whole.
     * Accepted updates are evaluated in key order so conflicts and publication
     * are deterministic even when the caller supplies an unordered map.
     */
    fun <K : Comparable<K>> merge(
        active: DistantPageRevision<K>,
        updates: Map<K, DistantSourceRecord>,
    ): DistantPageMergeOutcome<K> {
        val rejectedEpochs = updates.values
            .asSequence()
            .map(DistantSourceRecord::worldEpoch)
            .filter { it != active.worldEpoch }
            .toSortedSet()
        if (rejectedEpochs.isNotEmpty()) {
            return DistantPageMergeOutcome.RejectedWorldEpoch(active, rejectedEpochs)
        }

        val merged = TreeMap(active.records)
        val conflicts = mutableListOf<DistantSourceConflict<K>>()
        var changed = false
        for ((key, incoming) in updates.toSortedMap()) {
            val current = merged[key]
            when {
                current == null -> {
                    merged[key] = incoming
                    changed = true
                }

                incoming.authority.precedence > current.authority.precedence &&
                    incoming.completeness == DistantSourceCompleteness.COMPLETE -> {
                    merged[key] = incoming
                    changed = true
                }

                incoming.authority.precedence < current.authority.precedence -> Unit

                incoming.authority == current.authority -> {
                    val revisionComparison = compareSourceVersion(incoming, current)
                    when {
                        revisionComparison > 0 -> {
                            merged[key] = incoming
                            changed = true
                        }

                        revisionComparison == 0 &&
                            incoming.semanticContentDigest != current.semanticContentDigest -> {
                            conflicts += DistantSourceConflict(key, current, incoming)
                        }
                    }
                }
            }
        }

        val immutableConflicts = conflicts.toList()
        if (!changed) return DistantPageMergeOutcome.Unchanged(active, immutableConflicts)
        return DistantPageMergeOutcome.Published(
            active = active,
            published = active.publish(merged),
            conflicts = immutableConflicts,
        )
    }

    private fun compareSourceVersion(
        incoming: DistantSourceRecord,
        active: DistantSourceRecord,
    ): Int {
        val revision = incoming.sourceDataRevision.compareTo(active.sourceDataRevision)
        if (revision != 0) return revision
        return compareValues(incoming.sourceSequence, active.sourceSequence)
    }
}
