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

package de.bixilon.minosoft.gui.rendering.terrain.near

import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.chunk.heightmap.Heightmap
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTracker
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey

/** Conservative surface-readiness policy for near-to-distant masking. */
object NearSurfaceCoverage {
    fun requiredSections(heightmap: Heightmap): Set<Int> {
        val required = linkedSetOf<Int>()
        for (index in 0 until ChunkSize.SECTION_WIDTH_X * ChunkSize.SECTION_WIDTH_Z) {
            val height = heightmap[index]
            if (height == Int.MIN_VALUE) continue
            if (height == Int.MAX_VALUE) return emptySet()
            required += Math.floorDiv(height - 1, ChunkSize.SECTION_HEIGHT_Y)
        }
        return required
    }

    fun isReady(requiredSections: Set<Int>, uploadedSections: Set<Int>): Boolean =
        requiredSections.isNotEmpty() && uploadedSections.containsAll(requiredSections)
}

data class NearSurfaceCoverageSnapshot(
    val revision: Long,
    private val chunkValues: Set<ChunkPosition>,
    val lifecycle: TerrainCoverageSnapshot,
) {
    val chunks: Set<ChunkPosition> = java.util.Set.copyOf(chunkValues)
}

data class NearSurfaceCoverageEnvironment(
    val worldEpoch: Long,
    val providerGeneration: Long,
    val frame: Long,
)

/**
 * Application adapter for the canonical coverage lifecycle. Revision changes
 * only when the spatially ready chunk set or its world/provider owner changes.
 */
class NearSurfaceCoverageIndex(
    private val environment: () -> NearSurfaceCoverageEnvironment = {
        NearSurfaceCoverageEnvironment(0L, 0L, 0L)
    },
) {
    private var tracker: TerrainCoverageTracker? = null
    private var trackerEnvironment: NearSurfaceCoverageEnvironment? = null
    var revision: Long = 0L
        private set

    fun requested(position: ChunkPosition): Boolean =
        advance(position, TerrainCoverageState.REQUESTED)

    fun building(position: ChunkPosition): Boolean =
        advance(position, TerrainCoverageState.BUILDING)

    fun uploadPending(position: ChunkPosition): Boolean =
        advance(position, TerrainCoverageState.UPLOAD_PENDING)

    fun update(
        position: ChunkPosition,
        requiredSections: Set<Int>,
        uploadedSections: Set<Int>,
    ): Boolean {
        val ready = NearSurfaceCoverage.isReady(requiredSections, uploadedSections)
        val before = isCovered(position)
        if (ready) {
            advance(position, TerrainCoverageState.READY)
        } else {
            // The active uploaded set is incomplete. Unlike a replacement in
            // flight, this is a real loss of renderable surface ownership.
            absent(position)
            if (uploadedSections.isNotEmpty()) advance(position, TerrainCoverageState.UPLOAD_PENDING)
        }
        return before != isCovered(position)
    }

    fun remove(position: ChunkPosition): Boolean {
        val before = isCovered(position)
        absent(position)
        return before
    }

    fun clear(): Boolean {
        val environment = currentEnvironment()
        val active = ensureTracker(environment)
        val snapshot = active.snapshot(environment.frame)
        val changed = snapshot.coveredPages.isNotEmpty()
        snapshot.cells.forEach { remove(active, it.page, environment.frame) }
        return changed
    }

    /** Drops lifecycle state without consulting an owner that is already closing. */
    fun discard(): Boolean {
        val active = tracker ?: return false
        val environment = checkNotNull(trackerEnvironment)
        val changed = active.snapshot(environment.frame).coveredPages.isNotEmpty()
        tracker = null
        trackerEnvironment = null
        if (changed) revision = Math.addExact(revision, 1L)
        return changed
    }

    fun snapshot(): NearSurfaceCoverageSnapshot {
        val environment = currentEnvironment()
        val lifecycle = ensureTracker(environment).snapshot(environment.frame)
        val chunks = lifecycle.coveredPages.mapTo(linkedSetOf()) {
            ChunkPosition(Math.toIntExact(it.x), Math.toIntExact(it.z))
        }
        return NearSurfaceCoverageSnapshot(revision, chunks, lifecycle)
    }

    private fun advance(position: ChunkPosition, target: TerrainCoverageState): Boolean {
        val environment = currentEnvironment()
        val tracker = ensureTracker(environment)
        val page = position.page(environment.worldEpoch)
        val before = tracker.contributesCoverage(page)
        when (target) {
            TerrainCoverageState.REQUESTED -> toRequested(tracker, page, environment.frame)
            TerrainCoverageState.BUILDING -> {
                toRequested(tracker, page, environment.frame)
                transition(tracker, page, TerrainCoverageState.BUILDING, environment.frame)
            }
            TerrainCoverageState.UPLOAD_PENDING -> {
                toRequested(tracker, page, environment.frame)
                transition(tracker, page, TerrainCoverageState.BUILDING, environment.frame)
                transition(tracker, page, TerrainCoverageState.UPLOAD_PENDING, environment.frame)
            }
            TerrainCoverageState.READY -> {
                toRequested(tracker, page, environment.frame)
                transition(tracker, page, TerrainCoverageState.BUILDING, environment.frame)
                transition(tracker, page, TerrainCoverageState.UPLOAD_PENDING, environment.frame)
                transition(tracker, page, TerrainCoverageState.READY, environment.frame)
            }
            TerrainCoverageState.ABSENT -> absent(position)
            TerrainCoverageState.RETIRING -> {
                if (tracker.state(page) != TerrainCoverageState.READY) return false
                transition(tracker, page, TerrainCoverageState.RETIRING, environment.frame)
            }
        }
        return before != tracker.contributesCoverage(page)
    }

    private fun toRequested(tracker: TerrainCoverageTracker, page: TerrainPageKey, frame: Long) {
        when (tracker.state(page)) {
            TerrainCoverageState.ABSENT,
            TerrainCoverageState.READY,
            TerrainCoverageState.BUILDING,
            TerrainCoverageState.UPLOAD_PENDING,
            -> transition(tracker, page, TerrainCoverageState.REQUESTED, frame)
            TerrainCoverageState.RETIRING -> {
                transition(tracker, page, TerrainCoverageState.READY, frame)
                transition(tracker, page, TerrainCoverageState.REQUESTED, frame)
            }
            TerrainCoverageState.REQUESTED -> Unit
        }
    }

    private fun absent(position: ChunkPosition) {
        val environment = currentEnvironment()
        val tracker = ensureTracker(environment)
        val page = position.page(environment.worldEpoch)
        remove(tracker, page, environment.frame)
    }

    private fun remove(tracker: TerrainCoverageTracker, page: TerrainPageKey, frame: Long) {
        when (tracker.state(page)) {
            TerrainCoverageState.ABSENT -> return
            TerrainCoverageState.READY -> {
                transition(tracker, page, TerrainCoverageState.RETIRING, frame)
                transition(tracker, page, TerrainCoverageState.ABSENT, frame)
            }
            TerrainCoverageState.RETIRING,
            TerrainCoverageState.REQUESTED,
            TerrainCoverageState.BUILDING,
            TerrainCoverageState.UPLOAD_PENDING,
            -> transition(tracker, page, TerrainCoverageState.ABSENT, frame)
        }
    }

    private fun isCovered(position: ChunkPosition): Boolean {
        val environment = currentEnvironment()
        val tracker = ensureTracker(environment)
        return tracker.contributesCoverage(position.page(environment.worldEpoch))
    }

    private fun transition(
        tracker: TerrainCoverageTracker,
        page: TerrainPageKey,
        state: TerrainCoverageState,
        frame: Long,
    ) {
        val before = tracker.contributesCoverage(page)
        tracker.transition(page, state, surfaceRelevant = true, frame = frame)
        val after = tracker.contributesCoverage(page)
        if (before != after) revision = Math.addExact(revision, 1L)
    }

    private fun currentEnvironment(): NearSurfaceCoverageEnvironment = environment().also {
        require(it.worldEpoch >= 0L && it.providerGeneration >= 0L && it.frame >= 0L)
    }

    private fun ensureTracker(environment: NearSurfaceCoverageEnvironment = currentEnvironment()): TerrainCoverageTracker {
        val current = trackerEnvironment
        if (current?.worldEpoch == environment.worldEpoch &&
            current.providerGeneration == environment.providerGeneration
        ) {
            return checkNotNull(tracker)
        }
        if (current != null) revision = Math.addExact(revision, 1L)
        trackerEnvironment = environment
        return TerrainCoverageTracker(environment.worldEpoch, environment.providerGeneration).also { tracker = it }
    }

    private fun ChunkPosition.page(worldEpoch: Long) = TerrainPageKey(
        TerrainDomain.NEAR,
        0,
        x.toLong(),
        0L,
        z.toLong(),
        worldEpoch,
    )
}
