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

package de.bixilon.minosoft.gui.rendering.terrain.near

import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshBuilder
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshesBuilder
import de.bixilon.minosoft.gui.rendering.chunk.mesh.types.ChunkMeshTypes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainDirectionalVisibility
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainRuntimeSelection
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactBounds
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactCoordinate
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactStream
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.model.mesh.TerrainPrimitiveTopology
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NearTerrainArtifactCapture {
    val regionStorageEnabled: Boolean get() = TerrainRuntimeSelection.process.regionStorage
    val enabled: Boolean get() = TerrainRuntimeSelection.process.semanticArtifacts
}

/** Exports the candidate CPU builders without retaining a live section. */
fun ChunkMeshesBuilder.buildSemanticArtifact(
    identity: TerrainBuildIdentity,
    connectivity: TerrainDirectionalVisibility,
): TerrainMeshArtifact {
    val streams = buildList {
        addStream(ChunkMeshTypes.OPAQUE, opaque)
        addStream(ChunkMeshTypes.CUTOUT, cutout)
        addStream(ChunkMeshTypes.TRANSLUCENT, translucent)
        addStream(ChunkMeshTypes.TEXT, text)
    }
    val hasContent = streams.isNotEmpty() || entityPositions.isNotEmpty()
    return TerrainMeshArtifact(
        identity = identity,
        streams = streams,
        bounds = if (hasContent) {
            TerrainArtifactBounds(minPosition.artifactCoordinate(), maxPosition.artifactCoordinate())
        } else {
            null
        },
        connectivityBits = connectivity.encodedBits,
        coverageContribution = if (streams.isEmpty()) emptyList() else listOf(identity.page),
        entityPositions = entityPositions.map { it.artifactCoordinate() },
    )
}

private fun MutableList<TerrainArtifactStream>.addStream(
    type: ChunkMeshTypes,
    builder: ChunkMeshBuilder,
) {
    val vertices = builder._data ?: return
    if (vertices.isEmpty) return
    val vertexBytes = ByteBuffer.allocate(Math.multiplyExact(vertices.size, Float.SIZE_BYTES))
        .order(ByteOrder.LITTLE_ENDIAN)
    for (index in 0 until vertices.size) vertexBytes.putInt(vertices[index].toRawBits())
    val indices = builder._index
    val indexBytes = ByteBuffer.allocate(Math.multiplyExact(indices?.size ?: 0, Int.SIZE_BYTES))
        .order(ByteOrder.LITTLE_ENDIAN)
    if (indices != null) {
        for (index in 0 until indices.size) indexBytes.putInt(indices[index])
    }
    val id = type.name.lowercase()
    this += TerrainArtifactStream(
        partitionId = "near:$id",
        material = TerrainSemanticMaterialId("minosoft:terrain/$id"),
        vertexStrideBytes = builder.struct.bytes,
        vertexBytes = vertexBytes.array(),
        indexElementBytes = Int.SIZE_BYTES,
        indexBytes = indexBytes.array(),
        topology = when (builder.primitive) {
            PrimitiveTypes.TRIANGLE -> TerrainPrimitiveTopology.TRIANGLES
            PrimitiveTypes.QUAD -> TerrainPrimitiveTopology.QUADS
            else -> error("Unsupported near-terrain primitive topology: ${builder.primitive}")
        },
    )
}

private fun de.bixilon.minosoft.data.world.positions.InSectionPosition.artifactCoordinate() =
    TerrainArtifactCoordinate(x, y, z)
