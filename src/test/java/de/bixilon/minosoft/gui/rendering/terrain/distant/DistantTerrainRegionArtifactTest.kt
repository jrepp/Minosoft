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

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFaceDirection
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantMeshQuad
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMeshArtifact
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalColumn
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionExtent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DistantTerrainRegionArtifactTest {
    @Test
    fun `encoder makes page positions region relative and partitions water`() {
        val key = TerrainPageKey(TerrainDomain.DISTANT, 0, 9L, 0L, -7L, 3L)
        val page = DistantVerticalPage(
            key,
            width = 16,
            originY = -64,
            sourceRevision = 4L,
            completeness = DistantSourceCompleteness.COMPLETE,
            columns = List(16 * 16) { DistantVerticalColumn(emptyList()) },
        )
        val stone = TerrainSemanticMaterialId("minecraft:stone")
        val water = DistantFluidSample(TerrainSemanticMaterialId("minecraft:water"), 0, "water")
        val quads = listOf(
            quad(stone, fluid = null),
            quad(TerrainSemanticMaterialId("minecraft:water"), fluid = water),
        )
        val mesh = DistantPageMeshArtifact(key, 4L, page.semanticDigest, 2, quads)

        val artifact = DistantTerrainRegionArtifactEncoder.encode(identity(key), page, mesh, TerrainRegionExtent(8, 1, 8))

        assertEquals(listOf("distant:opaque", "distant:water"), artifact.streams.map { it.partitionId })
        val vertices = ByteBuffer.wrap(artifact.streams.first().vertices.copyBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16.0f, vertices.float) // page x=9 is one 16-block page into region x=1
        assertEquals(8.0f, vertices.float)
        assertEquals(32.0f, vertices.float) // far edge of the page one slot into floor-divided region z=-1
        assertEquals(16, artifact.bounds?.minimum?.x)
        assertEquals(32, artifact.bounds?.maximumInclusive?.x)
        artifact.close()
    }

    private fun quad(material: TerrainSemanticMaterialId, fluid: DistantFluidSample?) = DistantMeshQuad(
        direction = DistantFaceDirection.UP,
        plane = 8,
        minimumU = 0,
        maximumUExclusive = 16,
        minimumV = 0,
        maximumVExclusive = 16,
        material = material,
        fluid = fluid,
        blockLight = 2,
        skyLight = 15,
        tint = null,
        flags = emptySet(),
        confidence = 100,
        fallback = false,
    )

    private fun identity(page: TerrainPageKey) = TerrainBuildIdentity(
        page = page,
        requestRevision = 1L,
        capturedModelRevision = 4L,
        providerGeneration = 1L,
        layoutGeneration = 1L,
        materialGeneration = 1L,
        coverageGeneration = 0L,
        prioritySequence = 1L,
        sourceDataRevision = 4L,
    )
}
