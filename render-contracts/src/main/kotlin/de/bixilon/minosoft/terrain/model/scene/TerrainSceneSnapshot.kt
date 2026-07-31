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

package de.bixilon.minosoft.terrain.model.scene

import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.page.TerrainCameraPosition
import de.bixilon.minosoft.terrain.model.page.TerrainRenderOrigin

data class TerrainSceneViewDescriptor(
    val id: String,
    val auxiliary: Boolean,
) {
    init {
        require(NORMALIZED_ID.matches(id)) { "Terrain scene view ID must be normalized" }
    }

    private companion object {
        val NORMALIZED_ID = Regex("[a-z0-9_.-]+(?::[a-z0-9/._-]+)?")
    }
}

data class TerrainSeamParameters(
    val overlapBlocks: Double,
    val transitionFrames: Int,
    val transitionEnabled: Boolean,
) {
    init {
        require(overlapBlocks.isFinite() && overlapBlocks >= 0.0) {
            "Terrain seam overlap must be finite and non-negative"
        }
        require(transitionFrames >= 0) { "Terrain seam transition frames must not be negative" }
        require(transitionEnabled || transitionFrames == 0) {
            "Disabled terrain seam transition must not retain a transition duration"
        }
    }

    companion object {
        val CONSERVATIVE_OVERLAP = TerrainSeamParameters(16.0, 0, false)
    }
}

/**
 * One immutable terrain decision boundary shared by every view in a frame.
 * Calling [forView] never recaptures coverage or distant selection.
 */
class TerrainSceneSnapshot(
    val frameGeneration: Long,
    val worldEpoch: Long,
    val camera: TerrainCameraPosition,
    val renderOrigin: TerrainRenderOrigin,
    val nearCoverage: TerrainCoverageSnapshot,
    selectedDistantPages: Collection<TerrainPageKey>,
    val seam: TerrainSeamParameters,
    val layoutGeneration: Long,
    val materialGeneration: Long,
    views: Collection<TerrainSceneViewDescriptor>,
) {
    val selectedDistantPages: List<TerrainPageKey> = java.util.List.copyOf(
        selectedDistantPages.sortedWith(PAGE_ORDER),
    )
    val views: List<TerrainSceneViewDescriptor> = java.util.List.copyOf(views.sortedBy { it.id })

    init {
        require(frameGeneration >= 0L) { "Terrain frame generation must not be negative" }
        require(worldEpoch >= 0L) { "Terrain scene world epoch must not be negative" }
        require(layoutGeneration >= 0L) { "Terrain layout generation must not be negative" }
        require(materialGeneration >= 0L) { "Terrain material generation must not be negative" }
        require(nearCoverage.worldEpoch == worldEpoch) { "Terrain scene and near coverage epochs differ" }
        require(this.selectedDistantPages.all {
            it.domain == TerrainDomain.DISTANT && it.worldEpoch == worldEpoch
        }) { "Terrain scene distant selection has the wrong domain or world epoch" }
        require(this.selectedDistantPages.toSet().size == this.selectedDistantPages.size) {
            "Terrain scene contains duplicate distant pages"
        }
        require(this.views.isNotEmpty()) { "Terrain scene must declare at least one view" }
        require(this.views.map(TerrainSceneViewDescriptor::id).toSet().size == this.views.size) {
            "Terrain scene contains duplicate view IDs"
        }
    }

    fun forView(id: String): TerrainSceneViewSnapshot {
        val view = views.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Terrain scene does not declare view $id")
        return TerrainSceneViewSnapshot(this, view)
    }

    private companion object {
        val PAGE_ORDER = compareBy<TerrainPageKey>({ it.detailLevel }, { it.z }, { it.x }, { it.y })
    }
}

data class TerrainSceneViewSnapshot(
    val scene: TerrainSceneSnapshot,
    val view: TerrainSceneViewDescriptor,
) {
    val nearCoverage: TerrainCoverageSnapshot get() = scene.nearCoverage
    val selectedDistantPages: List<TerrainPageKey> get() = scene.selectedDistantPages
}
