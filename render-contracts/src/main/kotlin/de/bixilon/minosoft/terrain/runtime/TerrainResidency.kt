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

enum class TerrainResidencyScope {
    WORLD,
    DEVICE,
}

enum class TerrainResidencyCategory(val scope: TerrainResidencyScope) {
    DETACHED_BUILD_SNAPSHOT(TerrainResidencyScope.WORLD),
    COMPLETED_CPU_MESH_ARTIFACT(TerrainResidencyScope.WORLD),
    DECODED_DISTANT_PAGE(TerrainResidencyScope.WORLD),
    PERSISTENCE_WRITE_SNAPSHOT(TerrainResidencyScope.WORLD),
    STAGING_BUFFER(TerrainResidencyScope.DEVICE),
    RESIDENT_NEAR_GPU_RANGE(TerrainResidencyScope.DEVICE),
    RESIDENT_DISTANT_GPU_RANGE(TerrainResidencyScope.DEVICE),
    RETIRED_GPU_RANGE(TerrainResidencyScope.DEVICE),
    BATCH_COMMAND_STORAGE(TerrainResidencyScope.DEVICE),
}

data class TerrainResidencyCategoryAccounting(
    val category: TerrainResidencyCategory,
    val residentBytes: Long,
    val retiredBytes: Long = 0L,
    val pinnedBytes: Long = 0L,
) {
    val accountedBytes: Long = Math.addExact(residentBytes, retiredBytes)

    init {
        require(residentBytes >= 0L) { "Terrain resident bytes must not be negative" }
        require(retiredBytes >= 0L) { "Terrain retired bytes must not be negative" }
        require(pinnedBytes >= 0L) { "Terrain pinned bytes must not be negative" }
        require(pinnedBytes <= accountedBytes) {
            "Terrain pinned bytes must not exceed accounted bytes"
        }
        require(category == TerrainResidencyCategory.RETIRED_GPU_RANGE || retiredBytes == 0L) {
            "Only retired GPU ranges may account retired bytes"
        }
        require(category != TerrainResidencyCategory.RETIRED_GPU_RANGE || residentBytes == 0L) {
            "Retired GPU ranges may not account resident bytes"
        }
    }

    fun plus(other: TerrainResidencyCategoryAccounting): TerrainResidencyCategoryAccounting {
        require(category == other.category) {
            "Cannot combine different terrain residency categories: $category and ${other.category}"
        }
        return TerrainResidencyCategoryAccounting(
            category = category,
            residentBytes = Math.addExact(residentBytes, other.residentBytes),
            retiredBytes = Math.addExact(retiredBytes, other.retiredBytes),
            pinnedBytes = Math.addExact(pinnedBytes, other.pinnedBytes),
        )
    }
}

class TerrainResidencyCapSnapshot(
    val scope: TerrainResidencyScope,
    val capBytes: Long,
    accounting: Collection<TerrainResidencyCategoryAccounting>,
    val highWaterMarkBytes: Long,
    val evictionCount: Long = 0L,
    val evictedBytes: Long = 0L,
    val deferralCount: Long = 0L,
    val hardFailureCount: Long = 0L,
) {
    val accounting: List<TerrainResidencyCategoryAccounting> =
        java.util.List.copyOf(accounting.sortedBy { it.category.ordinal })
    val residentBytes: Long = checkedSum(this.accounting.map { it.residentBytes })
    val retiredBytes: Long = checkedSum(this.accounting.map { it.retiredBytes })
    val pinnedBytes: Long = checkedSum(this.accounting.map { it.pinnedBytes })
    val accountedBytes: Long = Math.addExact(residentBytes, retiredBytes)
    val availableBytes: Long
        get() = capBytes - accountedBytes

    init {
        require(capBytes >= 0L) { "Terrain residency cap must not be negative" }
        require(highWaterMarkBytes >= 0L) { "Terrain residency high-water mark must not be negative" }
        require(evictionCount >= 0L) { "Terrain residency eviction count must not be negative" }
        require(evictedBytes >= 0L) { "Terrain evicted bytes must not be negative" }
        require(deferralCount >= 0L) { "Terrain residency deferral count must not be negative" }
        require(hardFailureCount >= 0L) { "Terrain residency hard-failure count must not be negative" }
        require(this.accounting.all { it.category.scope == scope }) {
            "Terrain residency accounting categories must belong to the $scope scope"
        }
        require(this.accounting.map { it.category }.toSet().size == this.accounting.size) {
            "Terrain residency snapshot contains duplicate accounting categories"
        }
        require(accountedBytes <= capBytes) {
            "Terrain accounted bytes must not exceed the configured cap"
        }
        require(highWaterMarkBytes in accountedBytes..capBytes) {
            "Terrain residency high-water mark must include current accounting and remain within the cap"
        }
    }

    fun canReserve(bytes: Long): Boolean {
        require(bytes >= 0L) { "Terrain reservation bytes must not be negative" }
        return bytes <= availableBytes
    }

    fun accounting(category: TerrainResidencyCategory): TerrainResidencyCategoryAccounting? {
        require(category.scope == scope) {
            "Terrain residency category $category does not belong to the $scope scope"
        }
        return accounting.firstOrNull { it.category == category }
    }

    private companion object {
        fun checkedSum(values: Iterable<Long>): Long {
            var sum = 0L
            for (value in values) {
                sum = Math.addExact(sum, value)
            }
            return sum
        }
    }
}
