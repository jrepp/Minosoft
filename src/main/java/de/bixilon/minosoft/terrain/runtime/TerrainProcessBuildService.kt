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

package de.bixilon.minosoft.terrain.runtime

import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSharedBuildService

/** One bounded CPU service shared by every near and distant terrain tenant. */
object TerrainProcessBuildService {
    private val workerCount = maxOf(minOf(Runtime.getRuntime().availableProcessors() - 1, 12), 1)

    val shared = TerrainSharedBuildService(
        workerCount = workerCount,
        queueCapacity = Math.multiplyExact(workerCount, 8),
        threadNamePrefix = "Terrain CPU",
    )
}
