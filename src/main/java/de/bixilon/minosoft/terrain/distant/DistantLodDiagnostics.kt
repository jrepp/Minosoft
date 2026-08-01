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

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageBuildState
import de.bixilon.minosoft.terrain.runtime.storage.TerrainSelectionPublicationSnapshot

internal data class DistantLodRenderCellDiagnostic(
    val chunk: ChunkPosition,
    val x: Int,
    val z: Int,
    val size: Int,
    val y: Int,
    val minimumY: Int,
    val maximumY: Int,
    val material: String,
    val surface: String,
    val source: DistantLodTileSource,
    val skirtSegments: Int,
    val maximumSkirtDrop: Int,
)

internal data class DistantLodRenderDiagnostics(
    val revision: Long,
    val nativeOwnershipRevision: Long,
    val tileCount: Int,
    val renderReadyNativeChunks: Int,
    val excludedTiles: Int,
    val cellCount: Int,
    val terrainCells: Int,
    val waterCells: Int,
    val waterBedCells: Int,
    val adaptiveCells: Int,
    val skirtSegments: Int,
    val maximumSkirtDrop: Int,
    val cellSizes: Map<Int, Int>,
    val sources: Map<DistantLodTileSource, Int>,
    val cells: List<DistantLodRenderCellDiagnostic>,
    val hierarchy: DistantHierarchyRenderDiagnostics? = null,
) {
    companion object {
        fun empty(
            revision: Long,
            nativeOwnershipRevision: Long,
            tileCount: Int,
            renderReadyNativeChunks: Int,
            excludedTiles: Int,
        ) = DistantLodRenderDiagnostics(
            revision,
            nativeOwnershipRevision,
            tileCount,
            renderReadyNativeChunks,
            excludedTiles,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            emptyMap(),
            emptyMap(),
            emptyList(),
        )
    }
}

internal data class DistantHierarchyPageDiagnostic(
    val detailLevel: Int,
    val x: Long,
    val z: Long,
    val sourceRevision: Long,
    val dirtyRevision: Long,
    val renderRevision: Long,
    val dirty: Boolean,
    val derived: Boolean,
    val buildState: DistantPageBuildState,
    val mergedFaces: Int,
    val fallbackFaces: Int,
)

internal data class DistantHierarchyRegionDiagnostic(
    val detailLevel: Int,
    val x: Long,
    val z: Long,
    val shard: Int,
    val activePages: Int,
    val retiredPages: Int,
    val residentBytes: Long,
    val vertexAllocatedBytes: Int,
    val indexAllocatedBytes: Int,
    val vertexHighWaterBytes: Int,
    val indexHighWaterBytes: Int,
    val vertexFragmentation: Double,
    val indexFragmentation: Double,
    val allocationFailures: Long,
    val uploadFailures: Long,
)

internal data class DistantHierarchyRenderDiagnostics(
    val deviceCapacityBytes: Long,
    val storageHighWaterBytes: Long,
    val stagingCapacityBytes: Int,
    val storagePublicationGeneration: Long,
    val selectionPublication: TerrainSelectionPublicationSnapshot,
    val indexRevision: Long,
    val sourcePublicationRevision: Long,
    val dirtyRevision: Long,
    val indexedPages: Int,
    val dirtyPages: Int,
    val cpuPages: Int,
    val gpuPages: Int,
    val pendingPages: Int,
    val queuedPages: Int,
    val queuedBuildPages: Int,
    val pendingUploadPages: Int,
    val mainSelectedPages: Int,
    val shadowSelectedPages: Int,
    val mainMaskedPages: Int,
    val shadowMaskedPages: Int,
    val coverageRevision: Long,
    val coverageLifecycleRevision: Long,
    val coverageTransitionFrames: Int,
    val regions: Int,
    val residentBytes: Long,
    val retiredBytes: Long,
    val allocationFailures: Long,
    val uploadFailures: Long,
    val cpuLeaseCount: Int,
    val pendingSubmissionCount: Int,
    val failedSubmissionCount: Int,
    val invalidatedSubmissionCount: Int,
    val retiredCpuLeasedPages: Int,
    val retiredPendingSubmissionPages: Int,
    val retiredFailedSubmissionPages: Int,
    val retiredDeviceInvalidatedPages: Int,
    val deviceInvalidations: Long,
    val drawBatches: Int,
    val drawCommands: Int,
    val drawVertices: Long,
    val pendingSubmissionFences: Int,
    val detailCounts: Map<Int, Int>,
    val regionStates: List<DistantHierarchyRegionDiagnostic>,
    val pages: List<DistantHierarchyPageDiagnostic>,
)
