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

package de.bixilon.minosoft.gui.rendering.terrain.distant

import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageHierarchy
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import kotlin.math.hypot

/** Stable build admission around the visible near/distant handoff. */
internal data class DistantTerrainBuildPriority(
    val cameraChunkX: Int,
    val cameraChunkZ: Int,
    val seamDistanceChunks: Float,
) {
    init {
        require(seamDistanceChunks.isFinite() && seamDistanceChunks >= 0.0f) {
            "Distant build seam distance must be finite and non-negative"
        }
    }

    val comparator: Comparator<TerrainPageKey> =
        compareByDescending<TerrainPageKey>(TerrainPageKey::detailLevel)
            .thenBy(::distanceFromSeam)
            .thenBy { if (isOutsideSeam(it)) 0 else 1 }
            .then(DistantPageHierarchy.order)

    fun isSeamCritical(page: TerrainPageKey, bandChunks: Double): Boolean {
        require(bandChunks.isFinite() && bandChunks >= 0.0) {
            "Distant build seam urgency band must be finite and non-negative"
        }
        return distanceFromSeam(page) <= bandChunks
    }

    private fun distanceFromSeam(page: TerrainPageKey): Double =
        kotlin.math.abs(distanceFromCamera(page) - seamDistanceChunks.toDouble())

    private fun isOutsideSeam(page: TerrainPageKey): Boolean =
        distanceFromCamera(page) >= seamDistanceChunks.toDouble()

    private fun distanceFromCamera(page: TerrainPageKey): Double {
        val span = 1L shl page.detailLevel
        val centerX = page.x.toDouble() * span + span * 0.5
        val centerZ = page.z.toDouble() * span + span * 0.5
        return hypot(centerX - cameraChunkX, centerZ - cameraChunkZ)
    }
}
