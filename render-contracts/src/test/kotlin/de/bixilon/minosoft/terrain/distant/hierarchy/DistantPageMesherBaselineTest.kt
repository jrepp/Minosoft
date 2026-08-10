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

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential baselines for the greedy distant mesher. The oracles deliberately model bounded
 * height fields and multi-run columns as unit faces instead of sharing the production interval
 * subtraction or greedy merge implementation.
 */
class DistantPageMesherBaselineTest {
    @Test
    fun `deterministic heightfields exactly preserve their unit surface`() {
        val random = Random(SEED)
        var cases = 0
        for (detail in 0..1) {
            for (width in WIDTHS) {
                repeat(CASES_PER_GEOMETRY) { caseIndex ->
                    val heights = IntArray(Math.multiplyExact(width, width)) { random.nextInt(MAXIMUM_HEIGHT + 1) }
                    val semantics = Array(heights.size) { index ->
                        Semantic(
                            material = if ((index + caseIndex) and 1 == 0) STONE else DIRT,
                            blockLight = (index + caseIndex) and 0x0F,
                            skyLight = 15 - ((index * 3 + caseIndex) and 0x0F),
                        )
                    }
                    val page = page(
                        detail = detail,
                        width = width,
                        pageX = if (caseIndex and 1 == 0) caseIndex.toLong() else -caseIndex.toLong() - 1L,
                        pageZ = if (caseIndex and 2 == 0) -caseIndex.toLong() - 1L else caseIndex.toLong(),
                        heights = heights,
                        semantics = semantics,
                    )

                    val first = DistantPageMesher.mesh(page)
                    val second = DistantPageMesher.mesh(page)
                    val expected = expectedSurface(width, page.cellSizeBlocks, heights, semantics)
                    val actual = expand(first.quads)

                    assertEquals(expected.faces, actual, "surface mismatch in case $cases")
                    assertEquals(expected.primitiveFaceCount, first.primitiveFaceCount, "primitive mismatch in case $cases")
                    assertTrue(first.mergedFaceCount <= first.primitiveFaceCount, "greedy mesh expanded in case $cases")
                    assertEquals(first.digest, second.digest, "digest changed in case $cases")
                    assertEquals(first.quads.map(::quadSignature), second.quads.map(::quadSignature))
                    cases++
                }
            }
        }
        assertEquals(EXPECTED_CASES, cases)
    }

    @Test
    fun `structured ramps terraces and diagonal relief exactly preserve their unit surface`() {
        var cases = 0
        for (detail in 0..1) {
            for ((name, heights) in structuredHeightfields(STRUCTURED_WIDTH)) {
                val semantics = Array(heights.size) { index ->
                    Semantic(
                        material = if ((index / STRUCTURED_WIDTH + index) and 2 == 0) STONE else DIRT,
                        blockLight = index and 0x0F,
                        skyLight = 15 - ((index / STRUCTURED_WIDTH * 2 + index) and 0x0F),
                    )
                }
                val page = page(
                    detail = detail,
                    width = STRUCTURED_WIDTH,
                    pageX = -31L + cases,
                    pageZ = 47L - cases,
                    heights = heights,
                    semantics = semantics,
                )

                assertExactSurface(
                    page,
                    expectedSurface(STRUCTURED_WIDTH, page.cellSizeBlocks, heights, semantics),
                    "$name detail $detail",
                )
                cases++
            }
        }
        assertEquals(EXPECTED_STRUCTURED_CASES, cases)
    }

    @Test
    fun `seeded multi-run shelves and overhangs exactly preserve every exposed face`() {
        val random = Random(MULTI_RUN_SEED)
        var cases = 0
        for (detail in 0..1) {
            for (width in MULTI_RUN_WIDTHS) {
                repeat(MULTI_RUN_CASES_PER_GEOMETRY) { caseIndex ->
                    val columns = List(Math.multiplyExact(width, width)) { index ->
                        multiRunColumn(random, index, caseIndex)
                    }
                    val page = page(
                        detail = detail,
                        width = width,
                        pageX = if (caseIndex and 1 == 0) -10_000L - caseIndex else 10_000L + caseIndex,
                        pageZ = if (caseIndex and 2 == 0) 20_000L + caseIndex else -20_000L - caseIndex,
                        columns = columns,
                    )

                    assertExactSurface(
                        page,
                        expectedSurface(width, page.cellSizeBlocks, columns),
                        "multi-run case $cases",
                    )
                    cases++
                }
            }
        }
        assertEquals(EXPECTED_MULTI_RUN_CASES, cases)
    }

    @Test
    fun `page and vertical translation preserve local surface geometry`() {
        val heights = intArrayOf(3, 8, 0, 5)
        val semantics = Array(4) { index -> Semantic(STONE, index, 15 - index) }
        val origin = page(1, 2, -1L, 2L, heights, semantics, originY = -64)
        val translated = page(1, 2, 1_875_000L, -1_875_000L, heights, semantics, originY = 256)

        val originQuads = DistantPageMesher.mesh(origin).quads.map(::quadSignature)
        val translatedQuads = DistantPageMesher.mesh(translated).quads.map(::quadSignature)

        assertEquals(originQuads, translatedQuads)
    }

    private fun assertExactSurface(page: DistantVerticalPage, expected: ExpectedSurface, context: String) {
        val first = DistantPageMesher.mesh(page)
        val second = DistantPageMesher.mesh(page)
        val actual = expand(first.quads)

        assertEquals(expected.faces, actual, "surface mismatch in $context")
        assertEquals(expected.primitiveFaceCount, first.primitiveFaceCount, "primitive mismatch in $context")
        assertTrue(first.mergedFaceCount <= first.primitiveFaceCount, "greedy mesh expanded in $context")
        assertEquals(first.digest, second.digest, "digest changed in $context")
        assertEquals(first.quads.map(::quadSignature), second.quads.map(::quadSignature), context)
    }

    private fun page(
        detail: Int,
        width: Int,
        pageX: Long,
        pageZ: Long,
        heights: IntArray,
        semantics: Array<Semantic>,
        originY: Int = -64,
    ): DistantVerticalPage {
        require(heights.size == Math.multiplyExact(width, width) && semantics.size == heights.size)
        val columns = heights.indices.map { index ->
            val height = heights[index]
            if (height == 0) {
                DistantVerticalColumn(emptyList())
            } else {
                val semantic = semantics[index]
                DistantVerticalColumn(
                    listOf(
                        DistantColumnRun(
                            minimumY = originY,
                            height = height,
                            material = semantic.material,
                            fluid = null,
                            blockLight = semantic.blockLight,
                            skyLight = semantic.skyLight,
                            tint = null,
                            flags = setOf(DistantRunFlag.OPAQUE),
                            confidence = 100,
                        ),
                    ),
                )
            }
        }
        return DistantVerticalPage(
            key = TerrainPageKey(TerrainDomain.DISTANT, detail, pageX, 0L, pageZ, WORLD_EPOCH),
            width = width,
            originY = originY,
            sourceRevision = 1L,
            completeness = DistantSourceCompleteness.COMPLETE,
            columns = columns,
        )
    }

    private fun page(
        detail: Int,
        width: Int,
        pageX: Long,
        pageZ: Long,
        columns: List<List<RunSpec>>,
        originY: Int = -64,
    ): DistantVerticalPage {
        require(columns.size == Math.multiplyExact(width, width))
        return DistantVerticalPage(
            key = TerrainPageKey(TerrainDomain.DISTANT, detail, pageX, 0L, pageZ, WORLD_EPOCH),
            width = width,
            originY = originY,
            sourceRevision = 1L,
            completeness = DistantSourceCompleteness.COMPLETE,
            columns = columns.map { runs ->
                DistantVerticalColumn(
                    runs.map { run ->
                        DistantColumnRun(
                            minimumY = Math.addExact(originY, run.minimumY),
                            height = Math.subtractExact(run.maximumYExclusive, run.minimumY),
                            material = run.semantic.material,
                            fluid = null,
                            blockLight = run.semantic.blockLight,
                            skyLight = run.semantic.skyLight,
                            tint = null,
                            flags = setOf(DistantRunFlag.OPAQUE),
                            confidence = 100,
                        )
                    },
                )
            },
        )
    }

    private fun multiRunColumn(random: Random, index: Int, caseIndex: Int): List<RunSpec> {
        val base = RunSpec(
            minimumY = 0,
            maximumYExclusive = 1 + random.nextInt(4),
            semantic = semantic(index, caseIndex, 0),
        )
        val runs = ArrayList<RunSpec>(3)
        if ((index + caseIndex) % 5 == 0) {
            val minimumY = 14 + random.nextInt(3)
            runs += RunSpec(minimumY, minimumY + 1 + random.nextInt(2), semantic(index, caseIndex, 2))
        }
        if ((index + caseIndex) % 3 != 0) {
            val minimumY = 7 + random.nextInt(3)
            runs += RunSpec(minimumY, minimumY + 1 + random.nextInt(3), semantic(index, caseIndex, 1))
        }
        runs += base
        return runs
    }

    private fun semantic(index: Int, caseIndex: Int, layer: Int) = Semantic(
        material = if ((index + caseIndex + layer) and 1 == 0) STONE else DIRT,
        blockLight = (index * 3 + caseIndex + layer) and 0x0F,
        skyLight = 15 - ((index + caseIndex * 2 + layer * 3) and 0x0F),
    )

    private fun structuredHeightfields(width: Int): List<Pair<String, IntArray>> = listOf(
        "ascending ramp" to IntArray(width * width) { index -> 1 + index % width * 2 },
        "descending ramp" to IntArray(width * width) { index -> 1 + (width - 1 - index % width) * 2 },
        "diagonal ramp" to IntArray(width * width) { index -> 1 + index % width + index / width },
        "two-axis terraces" to IntArray(width * width) { index ->
            1 + index % width / 2 * 2 + index / width / 2 * 2
        },
    )

    private fun expectedSurface(
        width: Int,
        cellSize: Int,
        heights: IntArray,
        semantics: Array<Semantic>,
    ): ExpectedSurface {
        val faces = HashSet<FaceCell>()
        var primitives = 0
        fun height(x: Int, z: Int): Int = heights[z * width + x]
        for (z in 0 until width) {
            for (x in 0 until width) {
                val currentHeight = height(x, z)
                if (currentHeight == 0) continue
                val semantic = semantics[z * width + x]
                primitives++
                for (blockX in x * cellSize until (x + 1) * cellSize) {
                    for (blockZ in z * cellSize until (z + 1) * cellSize) {
                        faces += FaceCell(DistantFaceDirection.UP, currentHeight, blockX, blockZ, semantic)
                    }
                }
                for (direction in HORIZONTAL_DIRECTIONS) {
                    val neighbourX = x + when (direction) {
                        DistantFaceDirection.WEST -> -1
                        DistantFaceDirection.EAST -> 1
                        else -> 0
                    }
                    val neighbourZ = z + when (direction) {
                        DistantFaceDirection.NORTH -> -1
                        DistantFaceDirection.SOUTH -> 1
                        else -> 0
                    }
                    if (neighbourX !in 0 until width || neighbourZ !in 0 until width) continue
                    val neighbourHeight = height(neighbourX, neighbourZ)
                    if (currentHeight <= neighbourHeight) continue
                    primitives++
                    val plane = when (direction) {
                        DistantFaceDirection.NORTH -> z * cellSize
                        DistantFaceDirection.SOUTH -> (z + 1) * cellSize
                        DistantFaceDirection.WEST -> x * cellSize
                        DistantFaceDirection.EAST -> (x + 1) * cellSize
                        else -> error("not a side")
                    }
                    val minimumU = when (direction) {
                        DistantFaceDirection.NORTH, DistantFaceDirection.SOUTH -> x * cellSize
                        DistantFaceDirection.WEST, DistantFaceDirection.EAST -> z * cellSize
                    }
                    for (u in minimumU until minimumU + cellSize) {
                        for (y in neighbourHeight until currentHeight) {
                            faces += FaceCell(direction, plane, u, y, semantic)
                        }
                    }
                }
            }
        }
        return ExpectedSurface(faces, primitives)
    }

    private fun expectedSurface(
        width: Int,
        cellSize: Int,
        columns: List<List<RunSpec>>,
    ): ExpectedSurface {
        require(columns.size == Math.multiplyExact(width, width))
        val faces = HashSet<FaceCell>()
        var primitives = 0
        fun column(x: Int, z: Int): List<RunSpec> = columns[z * width + x]
        fun occupied(x: Int, z: Int, y: Int): Boolean = column(x, z).any {
            y >= it.minimumY && y < it.maximumYExclusive
        }

        for (z in 0 until width) {
            for (x in 0 until width) {
                for (run in column(x, z)) {
                    if (!occupied(x, z, run.maximumYExclusive)) {
                        primitives++
                        for (blockX in x * cellSize until (x + 1) * cellSize) {
                            for (blockZ in z * cellSize until (z + 1) * cellSize) {
                                faces += FaceCell(
                                    DistantFaceDirection.UP,
                                    run.maximumYExclusive,
                                    blockX,
                                    blockZ,
                                    run.semantic,
                                )
                            }
                        }
                    }

                    val hasKnownInteriorBelow = column(x, z).any {
                        it.maximumYExclusive < run.minimumY
                    }
                    if (hasKnownInteriorBelow && !occupied(x, z, run.minimumY - 1)) {
                        primitives++
                        for (blockX in x * cellSize until (x + 1) * cellSize) {
                            for (blockZ in z * cellSize until (z + 1) * cellSize) {
                                faces += FaceCell(
                                    DistantFaceDirection.DOWN,
                                    run.minimumY,
                                    blockX,
                                    blockZ,
                                    run.semantic,
                                )
                            }
                        }
                    }

                    for (direction in HORIZONTAL_DIRECTIONS) {
                        val neighbourX = x + when (direction) {
                            DistantFaceDirection.WEST -> -1
                            DistantFaceDirection.EAST -> 1
                            else -> 0
                        }
                        val neighbourZ = z + when (direction) {
                            DistantFaceDirection.NORTH -> -1
                            DistantFaceDirection.SOUTH -> 1
                            else -> 0
                        }
                        if (neighbourX !in 0 until width || neighbourZ !in 0 until width) continue

                        var previousExposed = false
                        for (y in run.minimumY until run.maximumYExclusive) {
                            val exposed = !occupied(neighbourX, neighbourZ, y)
                            if (exposed && !previousExposed) primitives++
                            previousExposed = exposed
                            if (!exposed) continue

                            val plane = when (direction) {
                                DistantFaceDirection.NORTH -> z * cellSize
                                DistantFaceDirection.SOUTH -> (z + 1) * cellSize
                                DistantFaceDirection.WEST -> x * cellSize
                                DistantFaceDirection.EAST -> (x + 1) * cellSize
                                else -> error("not a side")
                            }
                            val minimumU = when (direction) {
                                DistantFaceDirection.NORTH, DistantFaceDirection.SOUTH -> x * cellSize
                                DistantFaceDirection.WEST, DistantFaceDirection.EAST -> z * cellSize
                            }
                            for (u in minimumU until minimumU + cellSize) {
                                faces += FaceCell(direction, plane, u, y, run.semantic)
                            }
                        }
                    }
                }
            }
        }
        return ExpectedSurface(faces, primitives)
    }

    private fun expand(quads: List<DistantMeshQuad>): Set<FaceCell> {
        val faces = HashSet<FaceCell>()
        for (quad in quads) {
            val semantic = Semantic(quad.material, quad.blockLight, quad.skyLight)
            for (u in quad.minimumU until quad.maximumUExclusive) {
                for (v in quad.minimumV until quad.maximumVExclusive) {
                    val face = FaceCell(quad.direction, quad.plane, u, v, semantic)
                    assertTrue(faces.add(face), "overlapping greedy face $face")
                }
            }
        }
        return faces
    }

    private fun quadSignature(quad: DistantMeshQuad) = listOf(
        quad.direction,
        quad.plane,
        quad.minimumU,
        quad.maximumUExclusive,
        quad.minimumV,
        quad.maximumVExclusive,
        quad.material,
        quad.blockLight,
        quad.skyLight,
    )

    private data class Semantic(
        val material: TerrainSemanticMaterialId,
        val blockLight: Int,
        val skyLight: Int,
    )

    private data class RunSpec(
        val minimumY: Int,
        val maximumYExclusive: Int,
        val semantic: Semantic,
    )

    private data class FaceCell(
        val direction: DistantFaceDirection,
        val plane: Int,
        val u: Int,
        val v: Int,
        val semantic: Semantic,
    )

    private data class ExpectedSurface(
        val faces: Set<FaceCell>,
        val primitiveFaceCount: Int,
    )

    private companion object {
        const val SEED = 0x4D494E4F534F4654L
        const val CASES_PER_GEOMETRY = 256
        const val MULTI_RUN_CASES_PER_GEOMETRY = 64
        const val MAXIMUM_HEIGHT = 12
        const val STRUCTURED_WIDTH = 8
        const val WORLD_EPOCH = 7L
        val WIDTHS = intArrayOf(1, 2, 4, 8)
        val MULTI_RUN_WIDTHS = intArrayOf(2, 4, 8)
        val HORIZONTAL_DIRECTIONS = listOf(
            DistantFaceDirection.NORTH,
            DistantFaceDirection.SOUTH,
            DistantFaceDirection.WEST,
            DistantFaceDirection.EAST,
        )
        val STONE = TerrainSemanticMaterialId("minecraft:stone")
        val DIRT = TerrainSemanticMaterialId("minecraft:dirt")
        const val EXPECTED_CASES = CASES_PER_GEOMETRY * 2 * 4
        const val EXPECTED_STRUCTURED_CASES = 2 * 4
        const val EXPECTED_MULTI_RUN_CASES = MULTI_RUN_CASES_PER_GEOMETRY * 2 * 3
        const val MULTI_RUN_SEED = 0x4F56455248414E47L
    }
}
