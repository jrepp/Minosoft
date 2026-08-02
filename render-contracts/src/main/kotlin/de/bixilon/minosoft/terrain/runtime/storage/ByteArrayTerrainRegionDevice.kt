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

import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId

/** Deterministic ordinary-update device used by headless pipeline tests. */
class ByteArrayTerrainRegionDevice(
    override val deviceRuntime: TerrainDeviceRuntimeId,
    override val layoutGeneration: Long,
    override val vertexCapacityBytes: Int,
    override val indexCapacityBytes: Int,
) : TerrainRegionUploadDevice {
    private val vertices = ByteArray(requirePositiveCapacity(vertexCapacityBytes, "vertex"))
    private val indices = ByteArray(requirePositiveCapacity(indexCapacityBytes, "index"))

    init {
        require(layoutGeneration >= 0L) { "Terrain layout generation must not be negative" }
        require(vertexCapacityBytes > 0) { "Terrain vertex capacity must be positive" }
        require(indexCapacityBytes > 0) { "Terrain index capacity must be positive" }
    }

    @Synchronized
    override fun upload(plan: TerrainUploadPlan) {
        require(plan.layoutGeneration == layoutGeneration) { "Terrain upload uses another layout" }
        // Validate the complete plan before mutating either arena.
        plan.operations.forEach { operation ->
            val capacity = when (operation.arena) {
                TerrainBufferArena.VERTEX -> vertexCapacityBytes
                TerrainBufferArena.INDEX -> indexCapacityBytes
            }
            require(operation.range.offset + operation.range.length <= capacity) {
                "Terrain upload exceeds the ${operation.arena} arena"
            }
        }
        plan.operations.forEach { operation ->
            val destination = when (operation.arena) {
                TerrainBufferArena.VERTEX -> vertices
                TerrainBufferArena.INDEX -> indices
            }
            operation.copyInto(destination, operation.range.offset)
        }
    }

    @Synchronized
    fun read(arena: TerrainBufferArena, range: TerrainBufferRange): ByteArray {
        val source = when (arena) {
            TerrainBufferArena.VERTEX -> vertices
            TerrainBufferArena.INDEX -> indices
        }
        require(range.offset + range.length <= source.size) { "Terrain read exceeds the arena" }
        return source.copyOfRange(range.offset, range.offset + range.length)
    }

    private companion object {
        fun requirePositiveCapacity(capacity: Int, arena: String): Int {
            require(capacity > 0) { "Terrain $arena capacity must be positive" }
            return capacity
        }
    }
}
