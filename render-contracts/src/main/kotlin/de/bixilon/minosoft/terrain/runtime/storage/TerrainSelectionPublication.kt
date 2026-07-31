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

    @Synchronized
    fun desire(view: TerrainViewKey, targets: Collection<TerrainResidencyTarget>) {
        val ordered = targets.toList()
        require(ordered.map(TerrainResidencyTarget::page).distinct().size == ordered.size) {
            "Terrain selection contains duplicate pages"
        }
        val immutable = java.util.List.copyOf(ordered)
        if (immutable.isEmpty()) desired.remove(view) else desired[view] = immutable
    }

    /** Promotes one view atomically when every desired page version is resident. */
    @Synchronized
    fun promote(view: TerrainViewKey, residentVersions: Map<TerrainPageKey, Long>): Boolean {
        val candidate = desired[view].orEmpty()
        if (candidate.any { residentVersions[it.page] != it.publicationVersion }) return false
        val previous = active[view].orEmpty()
        if (candidate.isEmpty()) active.remove(view) else active[view] = candidate
        return previous != candidate
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

    @Synchronized
    fun clear() {
        desired.clear()
        active.clear()
    }
}
