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

import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFaceDirection
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantMeshQuad
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMeshArtifact
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalColumn
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionExtent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DistantTerrainRegionArtifactBaselineTest {
    @Test
    fun `encoder freezes positions indices and packed fields for every view-facing direction`() {
        val page = emptyPage(TerrainPageKey(TerrainDomain.DISTANT, 0, -15L, 0L, 9L, 3L))
        val quads = DistantFaceDirection.entries.map { direction -> quad(direction, STONE, null) }
        val mesh = DistantPageMeshArtifact(page.key, 4L, page.semanticDigest, quads.size, quads)

        val artifact = DistantTerrainRegionArtifactEncoder.encode(
            identity(page.key),
            page,
            mesh,
            TerrainRegionExtent(8, 1, 8),
        )

        try {
            assertEquals(listOf("distant:opaque"), artifact.streams.map { it.partitionId })
            val stream = artifact.streams.single()
            val vertices = ByteBuffer.wrap(stream.vertices.copyBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val indices = ByteBuffer.wrap(stream.indices.copyBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val offsetX = 16
            val offsetZ = 16
            for ((quadIndex, direction) in DistantFaceDirection.entries.withIndex()) {
                val expected = expectedPositions(direction, offsetX, offsetZ)
                val normalMaterial = (DistantLodMaterial.STONE.dhId shl 3) or NORMALS.getValue(direction)
                repeat(4) { vertexIndex ->
                    assertEquals(expected[vertexIndex][0], vertices.float, "$direction vertex $vertexIndex x")
                    assertEquals(expected[vertexIndex][1], vertices.float, "$direction vertex $vertexIndex y")
                    assertEquals(expected[vertexIndex][2], vertices.float, "$direction vertex $vertexIndex z")
                    assertEquals(RGBAColor(120, 120, 120).rgba, vertices.int, "$direction color")
                    assertEquals(0xF2, vertices.int, "$direction light")
                    assertEquals(normalMaterial, vertices.int, "$direction normal/material")
                }
                val base = Math.multiplyExact(quadIndex, 4)
                assertContentEquals(
                    intArrayOf(base + 3, base + 2, base + 1, base + 3, base + 1, base),
                    IntArray(6) { indices.int },
                    "$direction indices",
                )
            }
            assertEquals(0, vertices.remaining())
            assertEquals(0, indices.remaining())
        } finally {
            artifact.close()
        }
    }

    @Test
    fun `encoder partitions water and retains tint alpha independently of camera-facing view`() {
        val page = emptyPage(TerrainPageKey(TerrainDomain.DISTANT, 0, 1_875_000L, 0L, -1_875_000L, 3L))
        val water = DistantFluidSample(WATER, 0, "water")
        val tintedWater = DistantMeshQuad(
            direction = DistantFaceDirection.UP,
            plane = 9,
            minimumU = 2,
            maximumUExclusive = 14,
            minimumV = 3,
            maximumVExclusive = 13,
            material = WATER,
            fluid = water,
            blockLight = 7,
            skyLight = 11,
            tint = de.bixilon.minosoft.terrain.distant.hierarchy.DistantTintSample(0x204060, null, 2L),
            flags = emptySet(),
            confidence = 100,
            fallback = false,
        )
        val mesh = DistantPageMeshArtifact(page.key, 4L, page.semanticDigest, 2, listOf(quad(DistantFaceDirection.UP, STONE, null), tintedWater))

        val artifact = DistantTerrainRegionArtifactEncoder.encode(identity(page.key), page, mesh, TerrainRegionExtent(8, 1, 8))

        try {
            assertEquals(listOf("distant:opaque", "distant:water"), artifact.streams.map { it.partitionId })
            val waterVertices = ByteBuffer.wrap(artifact.streams[1].vertices.copyBytes()).order(ByteOrder.LITTLE_ENDIAN)
            waterVertices.position(3 * Float.SIZE_BYTES)
            assertEquals(RGBAColor(0x20, 0x40, 0x60, DistantLodMaterial.WATER.color.alpha).rgba, waterVertices.int)
            assertEquals(0xB7, waterVertices.int)
            assertEquals((DistantLodMaterial.WATER.dhId shl 3) or NORMALS.getValue(DistantFaceDirection.UP), waterVertices.int)
        } finally {
            artifact.close()
        }
    }

    private fun expectedPositions(direction: DistantFaceDirection, offsetX: Int, offsetZ: Int): Array<FloatArray> {
        val minimumU = 2
        val maximumU = 14
        val minimumV = 3
        val maximumV = 13
        val plane = 9
        fun position(x: Int, y: Int, z: Int) = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
        fun side(u: Int, v: Int) = when (direction) {
            DistantFaceDirection.NORTH, DistantFaceDirection.SOUTH -> position(offsetX + u, v, offsetZ + plane)
            DistantFaceDirection.WEST, DistantFaceDirection.EAST -> position(offsetX + plane, v, offsetZ + u)
            else -> error("horizontal face")
        }
        return when (direction) {
            DistantFaceDirection.UP -> arrayOf(
                position(offsetX + minimumU, plane, offsetZ + maximumV),
                position(offsetX + maximumU, plane, offsetZ + maximumV),
                position(offsetX + maximumU, plane, offsetZ + minimumV),
                position(offsetX + minimumU, plane, offsetZ + minimumV),
            )
            DistantFaceDirection.DOWN -> arrayOf(
                position(offsetX + minimumU, plane, offsetZ + minimumV),
                position(offsetX + maximumU, plane, offsetZ + minimumV),
                position(offsetX + maximumU, plane, offsetZ + maximumV),
                position(offsetX + minimumU, plane, offsetZ + maximumV),
            )
            DistantFaceDirection.NORTH, DistantFaceDirection.EAST -> arrayOf(
                side(minimumU, maximumV), side(maximumU, maximumV),
                side(maximumU, minimumV), side(minimumU, minimumV),
            )
            DistantFaceDirection.SOUTH, DistantFaceDirection.WEST -> arrayOf(
                side(maximumU, maximumV), side(minimumU, maximumV),
                side(minimumU, minimumV), side(maximumU, minimumV),
            )
        }
    }

    private fun quad(
        direction: DistantFaceDirection,
        material: TerrainSemanticMaterialId,
        fluid: DistantFluidSample?,
    ) = DistantMeshQuad(
        direction = direction,
        plane = 9,
        minimumU = 2,
        maximumUExclusive = 14,
        minimumV = 3,
        maximumVExclusive = 13,
        material = material,
        fluid = fluid,
        blockLight = 2,
        skyLight = 15,
        tint = null,
        flags = emptySet(),
        confidence = 100,
        fallback = false,
    )

    private fun emptyPage(key: TerrainPageKey) = DistantVerticalPage(
        key,
        width = 16,
        originY = -64,
        sourceRevision = 4L,
        completeness = DistantSourceCompleteness.COMPLETE,
        columns = List(16 * 16) { DistantVerticalColumn(emptyList()) },
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

    private companion object {
        val STONE = TerrainSemanticMaterialId("minecraft:stone")
        val WATER = TerrainSemanticMaterialId("minecraft:water")
        val NORMALS = mapOf(
            DistantFaceDirection.UP to 1,
            DistantFaceDirection.NORTH to 2,
            DistantFaceDirection.SOUTH to 3,
            DistantFaceDirection.WEST to 4,
            DistantFaceDirection.EAST to 5,
            DistantFaceDirection.DOWN to 6,
        )
    }
}
