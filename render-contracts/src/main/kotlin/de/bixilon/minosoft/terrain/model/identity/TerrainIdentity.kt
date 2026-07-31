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

package de.bixilon.minosoft.terrain.model.identity

data class TerrainWorldIdentity(
    val sessionGeneration: Long,
    val normalizedWorldKey: String,
    val worldEpoch: Long,
    val minimumHeight: Int,
    val maximumHeightExclusive: Int,
    val contentGeneration: Long,
    val persistenceIdentity: String? = null,
) {
    init {
        require(sessionGeneration >= 0L) { "Terrain session generation must not be negative" }
        require(normalizedWorldKey.isNotBlank()) { "Terrain world key must not be blank" }
        require(worldEpoch >= 0L) { "Terrain world epoch must not be negative" }
        require(minimumHeight < maximumHeightExclusive) {
            "Terrain world height range must not be empty"
        }
        require(contentGeneration >= 0L) { "Terrain content generation must not be negative" }
        require(persistenceIdentity == null || persistenceIdentity.isNotBlank()) {
            "Terrain persistence identity must not be blank"
        }
    }
}

enum class TerrainDomain {
    NEAR,
    DISTANT,
}

data class TerrainPageKey(
    val domain: TerrainDomain,
    val detailLevel: Int,
    val x: Long,
    val y: Long,
    val z: Long,
    val worldEpoch: Long,
) {
    init {
        require(detailLevel >= 0) { "Terrain page detail level must not be negative" }
        require(worldEpoch >= 0L) { "Terrain page world epoch must not be negative" }
    }

    fun offset(deltaX: Long, deltaY: Long, deltaZ: Long): TerrainPageKey = copy(
        x = Math.addExact(x, deltaX),
        y = Math.addExact(y, deltaY),
        z = Math.addExact(z, deltaZ),
    )
}

data class TerrainBuildIdentity(
    val page: TerrainPageKey,
    val requestRevision: Long,
    val capturedModelRevision: Long,
    val providerGeneration: Long,
    val layoutGeneration: Long,
    val materialGeneration: Long,
    val coverageGeneration: Long,
    val prioritySequence: Long,
    val sourceDataRevision: Long? = null,
) {
    init {
        require(requestRevision >= 0L) { "Terrain request revision must not be negative" }
        require(capturedModelRevision >= 0L) { "Terrain model revision must not be negative" }
        require(providerGeneration >= 0L) { "Terrain provider generation must not be negative" }
        require(layoutGeneration >= 0L) { "Terrain layout generation must not be negative" }
        require(materialGeneration >= 0L) { "Terrain material generation must not be negative" }
        require(coverageGeneration >= 0L) { "Terrain coverage generation must not be negative" }
        require(prioritySequence >= 0L) { "Terrain priority sequence must not be negative" }
        require(sourceDataRevision == null || sourceDataRevision >= 0L) {
            "Terrain source-data revision must not be negative"
        }
    }

    /**
     * Compares every publication-relevant identity field in a stable order.
     * Priority is deliberately excluded: it affects scheduling, not whether a
     * completed artifact is still valid.
     */
    fun mismatch(expected: TerrainBuildIdentity): TerrainBuildIdentityMismatch? {
        if (page.worldEpoch != expected.page.worldEpoch) return TerrainBuildIdentityMismatch.WORLD_EPOCH
        if (page != expected.page) return TerrainBuildIdentityMismatch.PAGE
        if (requestRevision != expected.requestRevision) return TerrainBuildIdentityMismatch.REQUEST_REVISION
        if (capturedModelRevision != expected.capturedModelRevision) {
            return TerrainBuildIdentityMismatch.MODEL_REVISION
        }
        if (providerGeneration != expected.providerGeneration) {
            return TerrainBuildIdentityMismatch.PROVIDER_GENERATION
        }
        if (layoutGeneration != expected.layoutGeneration) return TerrainBuildIdentityMismatch.LAYOUT_GENERATION
        if (materialGeneration != expected.materialGeneration) {
            return TerrainBuildIdentityMismatch.MATERIAL_GENERATION
        }
        if (coverageGeneration != expected.coverageGeneration) {
            return TerrainBuildIdentityMismatch.COVERAGE_GENERATION
        }
        if (sourceDataRevision != expected.sourceDataRevision) {
            return TerrainBuildIdentityMismatch.SOURCE_DATA_REVISION
        }
        return null
    }
}

enum class TerrainBuildIdentityMismatch {
    WORLD_EPOCH,
    PAGE,
    REQUEST_REVISION,
    MODEL_REVISION,
    PROVIDER_GENERATION,
    LAYOUT_GENERATION,
    MATERIAL_GENERATION,
    COVERAGE_GENERATION,
    SOURCE_DATA_REVISION,
}
