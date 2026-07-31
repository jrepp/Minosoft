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

package de.bixilon.minosoft.terrain.model.mesh

import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainMeshArtifactTest {
    @Test
    fun `artifact owns copied bytes and has deterministic semantic digest`() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val first = artifact(source)
        source.fill(99)
        val second = artifact(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), first.streams.single().vertices.copyBytes())
        assertEquals(first.digest, second.digest)
        assertTrue(first.compareSemantic(second).equivalent)
        assertEquals(11L, first.byteCount)
        first.close()
        second.close()
    }

    @Test
    fun `comparison classifies layout and content differences`() {
        val expected = artifact(ByteArray(8) { it.toByte() })
        val content = artifact(ByteArray(8) { (it + 1).toByte() })
        val layout = artifact(ByteArray(8) { it.toByte() }, material = "minosoft:cutout")
        val topology = artifact(ByteArray(8) { it.toByte() }, topology = TerrainPrimitiveTopology.QUADS)

        val contentComparison = expected.compareSemantic(content)
        assertFalse(contentComparison.equivalent)
        assertEquals(setOf(TerrainArtifactDifference.CONTENT), contentComparison.differences)
        assertEquals(setOf(TerrainArtifactDifference.STREAM_LAYOUT), expected.compareSemantic(layout).differences)
        assertEquals(setOf(TerrainArtifactDifference.STREAM_LAYOUT), expected.compareSemantic(topology).differences)
        expected.close()
        content.close()
        layout.close()
        topology.close()
    }

    @Test
    fun `closing artifact releases every owned buffer`() {
        val artifact = artifact(ByteArray(8))
        val stream = artifact.streams.single()

        artifact.close()
        artifact.close()

        assertTrue(artifact.isClosed)
        assertTrue(stream.vertices.isClosed)
        assertTrue(stream.indices.isClosed)
        assertFailsWith<IllegalStateException> { stream.vertices.copyBytes() }
    }

    private fun artifact(
        vertices: ByteArray,
        material: String = "minosoft:opaque",
        topology: TerrainPrimitiveTopology = TerrainPrimitiveTopology.TRIANGLES,
    ) = TerrainMeshArtifact(
        identity = IDENTITY,
        streams = listOf(
            TerrainArtifactStream(
                partitionId = "near:main",
                material = TerrainSemanticMaterialId(material),
                vertexStrideBytes = 4,
                vertexBytes = vertices,
                indexElementBytes = 1,
                indexBytes = when (topology) {
                    TerrainPrimitiveTopology.TRIANGLES -> byteArrayOf(0, 1, 0)
                    TerrainPrimitiveTopology.QUADS -> byteArrayOf(0, 1, 1, 0)
                },
                topology = topology,
            ),
        ),
        bounds = TerrainArtifactBounds(
            TerrainArtifactCoordinate(0, 0, 0),
            TerrainArtifactCoordinate(15, 15, 15),
        ),
        connectivityBits = 0x3FL,
        coverageContribution = listOf(IDENTITY.page),
        entityPositions = listOf(TerrainArtifactCoordinate(1, 2, 3)),
    )

    private companion object {
        val IDENTITY = TerrainBuildIdentity(
            page = TerrainPageKey(TerrainDomain.NEAR, 0, 1L, 2L, 3L, 4L),
            requestRevision = 5L,
            capturedModelRevision = 6L,
            providerGeneration = 7L,
            layoutGeneration = 8L,
            materialGeneration = 9L,
            coverageGeneration = 10L,
            prioritySequence = 11L,
        )
    }
}
