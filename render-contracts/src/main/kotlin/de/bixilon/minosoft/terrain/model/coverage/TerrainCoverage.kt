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

package de.bixilon.minosoft.terrain.model.coverage

import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey

enum class TerrainCoverageState {
    ABSENT,
    REQUESTED,
    BUILDING,
    UPLOAD_PENDING,
    READY,
    RETIRING,
    ;

    fun canTransitionTo(next: TerrainCoverageState): Boolean {
        if (this == next) return true
        return next in TRANSITIONS.getValue(this)
    }

    fun requireTransitionTo(next: TerrainCoverageState) {
        require(canTransitionTo(next)) { "Invalid terrain coverage transition: $this -> $next" }
    }

    private companion object {
        val TRANSITIONS = mapOf(
            ABSENT to setOf(REQUESTED),
            REQUESTED to setOf(ABSENT, BUILDING),
            BUILDING to setOf(ABSENT, REQUESTED, UPLOAD_PENDING),
            UPLOAD_PENDING to setOf(ABSENT, REQUESTED, READY),
            READY to setOf(REQUESTED, RETIRING),
            RETIRING to setOf(ABSENT, READY),
        )
    }
}

data class TerrainCoverageCell(
    val page: TerrainPageKey,
    val state: TerrainCoverageState,
    val surfaceRelevant: Boolean,
    val transitionAgeFrames: Int,
    val contributesCoverage: Boolean =
        surfaceRelevant && (state == TerrainCoverageState.READY || state == TerrainCoverageState.RETIRING),
    val coverageAgeFrames: Int = transitionAgeFrames,
) {
    init {
        require(transitionAgeFrames >= 0) { "Terrain coverage transition age must not be negative" }
        require(coverageAgeFrames >= 0) { "Terrain spatial coverage age must not be negative" }
        require(!contributesCoverage || surfaceRelevant) {
            "Only surface-relevant terrain may contribute spatial coverage"
        }
    }
}

class TerrainCoverageSnapshot(
    val worldEpoch: Long,
    val revision: Long,
    val providerGeneration: Long,
    cells: Collection<TerrainCoverageCell>,
    val lifecycleRevision: Long = revision,
) {
    val cells: List<TerrainCoverageCell> = java.util.List.copyOf(cells.sortedWith(CELL_ORDER))
    val coveredPages: Set<TerrainPageKey> = java.util.Set.copyOf(
        this.cells.filter(TerrainCoverageCell::contributesCoverage).map(TerrainCoverageCell::page),
    )

    init {
        require(worldEpoch >= 0L) { "Terrain coverage world epoch must not be negative" }
        require(revision >= 0L) { "Terrain coverage revision must not be negative" }
        require(providerGeneration >= 0L) { "Terrain coverage provider generation must not be negative" }
        require(lifecycleRevision >= revision) {
            "Terrain coverage lifecycle revision must include the spatial revision"
        }
        require(this.cells.all { it.page.worldEpoch == worldEpoch }) {
            "Terrain coverage cells must belong to snapshot world epoch $worldEpoch"
        }
        require(this.cells.map(TerrainCoverageCell::page).toSet().size == this.cells.size) {
            "Terrain coverage snapshot contains duplicate page keys"
        }
    }

    private companion object {
        val CELL_ORDER = compareBy<TerrainCoverageCell>(
            { it.page.domain.ordinal },
            { it.page.detailLevel },
            { it.page.z },
            { it.page.y },
            { it.page.x },
        )
    }
}

/**
 * Mutable world-owner state machine. [revision] changes only when the spatial
 * covered-page result changes; [lifecycleRevision] changes for every accepted
 * state or surface-relevance transition.
 *
 * A READY page retains last-known-good coverage while a replacement moves
 * through REQUESTED, BUILDING, and UPLOAD_PENDING. Failed replacements can
 * therefore retry without exposing a hole to the distant provider.
 */
class TerrainCoverageTracker(
    val worldEpoch: Long,
    val providerGeneration: Long,
) {
    private data class Entry(
        var state: TerrainCoverageState,
        var surfaceRelevant: Boolean,
        var contributesCoverage: Boolean,
        var transitionedFrame: Long,
        var coverageChangedFrame: Long,
    )

    private val entries = LinkedHashMap<TerrainPageKey, Entry>()
    var revision: Long = 0L
        private set
    var lifecycleRevision: Long = 0L
        private set

    init {
        require(worldEpoch >= 0L) { "Terrain coverage world epoch must not be negative" }
        require(providerGeneration >= 0L) { "Terrain coverage provider generation must not be negative" }
    }

    @Synchronized
    fun state(page: TerrainPageKey): TerrainCoverageState {
        requireEpoch(page)
        return entries[page]?.state ?: TerrainCoverageState.ABSENT
    }

    @Synchronized
    fun contributesCoverage(page: TerrainPageKey): Boolean {
        requireEpoch(page)
        return entries[page]?.contributesCoverage == true
    }

    @Synchronized
    fun hasCoverage(): Boolean = entries.values.any { it.contributesCoverage }

    @Synchronized
    fun transition(
        page: TerrainPageKey,
        next: TerrainCoverageState,
        surfaceRelevant: Boolean,
        frame: Long,
    ): Boolean {
        requireEpoch(page)
        require(frame >= 0L) { "Terrain coverage frame must not be negative" }
        val previous = entries[page]
        val previousState = previous?.state ?: TerrainCoverageState.ABSENT
        previousState.requireTransitionTo(next)
        if (previous == null && next == TerrainCoverageState.ABSENT) return false
        val previousCovered = previous?.contributesCoverage == true
        val nextCovered = when (next) {
            TerrainCoverageState.ABSENT -> false
            TerrainCoverageState.READY -> surfaceRelevant
            TerrainCoverageState.RETIRING -> previousCovered && surfaceRelevant
            TerrainCoverageState.REQUESTED,
            TerrainCoverageState.BUILDING,
            TerrainCoverageState.UPLOAD_PENDING,
            -> previousCovered && surfaceRelevant
        }
        if (
            previousState == next &&
            previous?.surfaceRelevant == surfaceRelevant &&
            previousCovered == nextCovered
        ) {
            return false
        }

        if (next == TerrainCoverageState.ABSENT) {
            entries.remove(page)
        } else {
            entries[page] = Entry(
                next,
                surfaceRelevant,
                nextCovered,
                frame,
                if (previousCovered != nextCovered) frame else previous?.coverageChangedFrame ?: frame,
            )
        }
        lifecycleRevision = Math.addExact(lifecycleRevision, 1L)
        if (previousCovered != nextCovered) revision = Math.addExact(revision, 1L)
        return true
    }

    @Synchronized
    fun snapshot(frame: Long): TerrainCoverageSnapshot {
        require(frame >= 0L) { "Terrain coverage frame must not be negative" }
        return TerrainCoverageSnapshot(
            worldEpoch = worldEpoch,
            revision = revision,
            providerGeneration = providerGeneration,
            cells = entries.map { (page, entry) ->
                TerrainCoverageCell(
                    page = page,
                    state = entry.state,
                    surfaceRelevant = entry.surfaceRelevant,
                    transitionAgeFrames = age(frame, entry.transitionedFrame),
                    contributesCoverage = entry.contributesCoverage,
                    coverageAgeFrames = age(frame, entry.coverageChangedFrame),
                )
            },
            lifecycleRevision = lifecycleRevision,
        )
    }

    private fun requireEpoch(page: TerrainPageKey) {
        require(page.worldEpoch == worldEpoch) {
            "Terrain coverage page belongs to world epoch ${page.worldEpoch}, expected $worldEpoch"
        }
    }

    private fun age(frame: Long, transitionedFrame: Long): Int {
        val elapsed = Math.subtractExact(frame, transitionedFrame)
        require(elapsed >= 0L) { "Terrain coverage snapshot predates a transition" }
        return minOf(elapsed, Int.MAX_VALUE.toLong()).toInt()
    }
}
