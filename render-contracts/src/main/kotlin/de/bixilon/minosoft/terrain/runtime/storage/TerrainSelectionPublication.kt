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

package de.bixilon.minosoft.terrain.runtime.storage

import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey

/** One immutable page version required by a frame-visible selection. */
data class TerrainResidencyTarget(
    val page: TerrainPageKey,
    val publicationVersion: Long,
) {
    init {
        require(publicationVersion > 0L) { "Terrain residency publication version must be positive" }
    }
}

/** Bounded per-view publication state suitable for atomic diagnostics. */
data class TerrainViewPublicationSnapshot(
    val view: TerrainViewKey,
    val desiredPageCount: Int,
    val activePageCount: Int,
    val missingPageCount: Int,
) {
    init {
        require(desiredPageCount >= 0) { "Desired terrain page count must not be negative" }
        require(activePageCount >= 0) { "Active terrain page count must not be negative" }
        require(missingPageCount in 0..desiredPageCount) {
            "Missing terrain page count must be within the desired selection"
        }
    }
}

/** Immutable summary of one transactional selection/publication owner. */
class TerrainSelectionPublicationSnapshot(
    val generation: Long,
    val retainedPageCount: Int,
    views: Collection<TerrainViewPublicationSnapshot>,
) {
    val views: List<TerrainViewPublicationSnapshot> = java.util.List.copyOf(views)

    init {
        require(generation >= 0L) { "Terrain selection generation must not be negative" }
        require(retainedPageCount >= 0) { "Retained terrain page count must not be negative" }
        require(this.views == this.views.sortedBy { it.view.value }) {
            "Terrain publication views must be sorted deterministically"
        }
        require(this.views.map { it.view }.distinct().size == this.views.size) {
            "Terrain publication snapshot contains duplicate views"
        }
    }
}

/**
 * Transactional view-selection publication independent of a graphics API.
 *
 * A desired selection does not replace the active selection until every exact
 * page version is resident. The union of active and desired targets therefore
 * identifies both the upload set and the last-known-good residency set.
 */
class TerrainSelectionPublication {
    private val desired = LinkedHashMap<TerrainViewKey, List<TerrainResidencyTarget>>()
    private val active = LinkedHashMap<TerrainViewKey, List<TerrainResidencyTarget>>()
    private var generation = 0L

    @Synchronized
    fun desire(view: TerrainViewKey, targets: Collection<TerrainResidencyTarget>) {
        val ordered = targets.toList()
        require(ordered.map(TerrainResidencyTarget::page).distinct().size == ordered.size) {
            "Terrain selection contains duplicate pages"
        }
        val immutable = java.util.List.copyOf(ordered)
        if (desired[view].orEmpty() == immutable) return
        if (immutable.isEmpty()) desired.remove(view) else desired[view] = immutable
        generation = Math.addExact(generation, 1L)
    }

    /**
     * Publishes the available subset of a requested selection only when doing
     * so cannot remove an active page before its replacement is available.
     *
     * An empty active view may grow progressively. A populated view may grow
     * progressively only while every active page remains represented by an
     * available target. A selection that removes active pages is held until
     * the complete requested target set is available.
     */
    @Synchronized
    fun desireAvailable(
        view: TerrainViewKey,
        requestedPages: Collection<TerrainPageKey>,
        availableTargets: Collection<TerrainResidencyTarget>,
    ): Boolean {
        val requested = requestedPages.toList()
        require(requested.distinct().size == requested.size) {
            "Requested terrain selection contains duplicate pages"
        }
        val targets = availableTargets.toList()
        val availablePages = targets.map(TerrainResidencyTarget::page).toSet()
        require(availablePages.size == targets.size) {
            "Available terrain selection contains duplicate pages"
        }
        require(requested.toSet().containsAll(availablePages)) {
            "Available terrain target is outside the requested selection"
        }

        val activePages = active[view].orEmpty().map(TerrainResidencyTarget::page)
        val complete = targets.size == requested.size
        val progressive = activePages.isEmpty() || activePages.all(availablePages::contains)
        if (!complete && !progressive) return false
        desire(view, targets)
        return true
    }

    /** Promotes one view atomically when every desired page version is resident. */
    @Synchronized
    fun promote(view: TerrainViewKey, residentVersions: Map<TerrainPageKey, Long>): Boolean {
        val candidate = desired[view].orEmpty()
        if (candidate.any { residentVersions[it.page] != it.publicationVersion }) return false
        val previous = active[view].orEmpty()
        if (previous == candidate) return false
        if (candidate.isEmpty()) active.remove(view) else active[view] = candidate
        generation = Math.addExact(generation, 1L)
        return true
    }

    @Synchronized
    fun active(view: TerrainViewKey): List<TerrainPageKey> =
        java.util.List.copyOf(active[view].orEmpty().map(TerrainResidencyTarget::page))

    @Synchronized
    fun desired(view: TerrainViewKey): List<TerrainResidencyTarget> =
        java.util.List.copyOf(desired[view].orEmpty())

    /** Exact desired versions that are not yet resident, deduplicated by page. */
    @Synchronized
    fun missing(residentVersions: Map<TerrainPageKey, Long>): List<TerrainResidencyTarget> {
        val missing = LinkedHashMap<TerrainPageKey, TerrainResidencyTarget>()
        desired.values.asSequence().flatten().forEach { target ->
            if (residentVersions[target.page] != target.publicationVersion) missing[target.page] = target
        }
        return java.util.List.copyOf(missing.values)
    }

    /** Pages that cannot be evicted without exposing an active or pending view. */
    @Synchronized
    fun retainedPages(): Set<TerrainPageKey> = java.util.Set.copyOf(
        buildSet {
            active.values.forEach { selection -> selection.forEach { add(it.page) } }
            desired.values.forEach { selection -> selection.forEach { add(it.page) } }
        },
    )

    /**
     * Captures only fixed-size counts per declared view. Exact page membership
     * remains available through the bounded page diagnostic query.
     */
    @Synchronized
    fun snapshot(residentVersions: Map<TerrainPageKey, Long>): TerrainSelectionPublicationSnapshot {
        val views = (desired.keys + active.keys).distinct().sortedBy(TerrainViewKey::value).map { view ->
            val desired = desired[view].orEmpty()
            TerrainViewPublicationSnapshot(
                view = view,
                desiredPageCount = desired.size,
                activePageCount = active[view].orEmpty().size,
                missingPageCount = desired.count {
                    residentVersions[it.page] != it.publicationVersion
                },
            )
        }
        return TerrainSelectionPublicationSnapshot(
            generation = generation,
            retainedPageCount = retainedPages().size,
            views = java.util.List.copyOf(views),
        )
    }

    @Synchronized
    fun clear() {
        if (desired.isEmpty() && active.isEmpty()) return
        desired.clear()
        active.clear()
        generation = Math.addExact(generation, 1L)
    }
}
