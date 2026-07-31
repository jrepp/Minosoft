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

package de.bixilon.minosoft.gui.rendering.terrain.runtime

import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainRuntimeMode
import java.util.Locale

/**
 * One process-pinned terrain rollout decision. A world may not independently
 * select the artifact, storage, and distant-hierarchy halves of the runtime.
 */
data class TerrainRuntimeSelection(
    val mode: TerrainRuntimeMode,
) {
    val semanticArtifacts: Boolean get() = mode != TerrainRuntimeMode.LEGACY
    val regionStorage: Boolean get() = mode == TerrainRuntimeMode.UNIFIED
    val distantHierarchy: Boolean get() = mode == TerrainRuntimeMode.UNIFIED

    companion object {
        const val PROPERTY = "minosoft.terrain.runtime"

        /** The production default is the consolidated runtime. */
        val process: TerrainRuntimeSelection = parse(System.getProperty(PROPERTY))

        fun parse(value: String?): TerrainRuntimeSelection {
            val mode = when (value?.trim()?.lowercase(Locale.ROOT)) {
                null, "", "unified" -> TerrainRuntimeMode.UNIFIED
                "unified-compare", "unified_compare" -> TerrainRuntimeMode.UNIFIED_COMPARE
                "legacy" -> TerrainRuntimeMode.LEGACY
                else -> throw IllegalArgumentException(
                    "Unsupported $PROPERTY value '$value'; expected unified, unified-compare, or legacy",
                )
            }
            return TerrainRuntimeSelection(mode)
        }
    }
}
