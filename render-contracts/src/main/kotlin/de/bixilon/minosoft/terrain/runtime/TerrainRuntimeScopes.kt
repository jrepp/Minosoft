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

@JvmInline
value class TerrainProcessScopeId(val generation: Long) {
    init {
        require(generation >= 0L) { "Terrain process generation must not be negative" }
    }
}

data class TerrainDeviceRuntimeId(
    val process: TerrainProcessScopeId,
    val deviceGeneration: Long,
) {
    init {
        require(deviceGeneration >= 0L) { "Terrain device generation must not be negative" }
    }
}

data class TerrainWorldRuntimeId(
    val process: TerrainProcessScopeId,
    val sessionGeneration: Long,
    val worldEpoch: Long,
) {
    init {
        require(sessionGeneration >= 0L) { "Terrain session generation must not be negative" }
        require(worldEpoch >= 0L) { "Terrain world epoch must not be negative" }
    }
}

/**
 * Process-owned CPU service boundary. Implementations may serve many device
 * and world runtimes but must not expose their mutable state through this
 * contract.
 */
interface TerrainCpuServiceScope : AutoCloseable {
    val scopeId: TerrainProcessScopeId
}

/**
 * Rendering-device boundary. Losing or replacing a context changes [scopeId]
 * and invalidates every resource associated with the former value.
 */
interface TerrainDeviceRuntimeScope : AutoCloseable {
    val scopeId: TerrainDeviceRuntimeId
}

/**
 * Session-world boundary. Late results are accepted only while their complete
 * [scopeId] still identifies the active world runtime.
 */
interface TerrainWorldRuntimeScope : AutoCloseable {
    val scopeId: TerrainWorldRuntimeId
}
