/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistantPageMeshingTest {
    @Test
    fun `flat page greedily merges known horizontal coverage without inventing boundary walls`() {
        val column = column(run(0, 8, STONE, opaque = true))
        val page = page(key(0, 0), width = 2, columns = List(4) { column })

        val artifact = DistantPageMesher.mesh(page)

        assertEquals(4, artifact.primitiveFaceCount)
        assertEquals(1, artifact.mergedFaceCount)
        assertEquals(0, artifact.fallbackFaceCount)
        assertEquals(4, artifact.vertexCount)
        assertEquals(6, artifact.indexCount)
        assertTrue(artifact.quads.any {
            it.direction == DistantFaceDirection.UP && it.minimumU == 0 && it.maximumUExclusive == 2 &&
                it.minimumV == 0 && it.maximumVExclusive == 2
        })
    }

    @Test
    fun `true run sides represent a cliff instead of lowering relief`() {
        val high = column(run(0, 10, STONE, opaque = true))
        val low = column(run(0, 5, STONE, opaque = true))
        val page = page(key(0, 0), width = 2, columns = listOf(high, low, high, low))

        val artifact = DistantPageMesher.mesh(page)
        val cliff = artifact.quads.single {
            !it.fallback && it.direction == DistantFaceDirection.EAST && it.plane == 1
        }

        assertEquals(5, cliff.minimumV)
        assertEquals(10, cliff.maximumVExclusive)
        assertEquals(0, cliff.minimumU)
        assertEquals(2, cliff.maximumUExclusive)
    }

    @Test
    fun `cave intervals emit independent top bottom and side faces`() {
        val caveColumn = column(
            run(8, 2, STONE, opaque = true, flags = setOf(DistantRunFlag.CAVE)),
            run(0, 2, STONE, opaque = true),
        )
        val subject = page(key(0, 0), width = 1, columns = listOf(caveColumn))
        val emptyNeighbours = cardinalPages(subject.key).map { page(it, 1, listOf(column())) }

        val artifact = DistantPageMesher.mesh(subject, DistantPageNeighbourhood(subject, emptyNeighbours))

        assertEquals(0, artifact.fallbackFaceCount)
        assertTrue(artifact.quads.any { it.direction == DistantFaceDirection.DOWN && it.plane == 8 })
        assertTrue(artifact.quads.any { it.direction == DistantFaceDirection.UP && it.plane == 2 })
        assertTrue(artifact.quads.any {
            it.direction == DistantFaceDirection.NORTH && it.minimumV == 8 && it.maximumVExclusive == 10
        })
        assertTrue(artifact.quads.any {
            it.direction == DistantFaceDirection.NORTH && it.minimumV == 0 && it.maximumVExclusive == 2
        })
    }

    @Test
    fun `fluid bed light and tint remain semantic and independently drawable`() {
        val tint = DistantTintSample(0x3366AA, "minecraft:swamp", 9)
        val fluid = DistantFluidSample(WATER, 3, "water")
        val subject = page(
            key(0, 0),
            width = 1,
            columns = listOf(
                column(
                    run(5, 5, WATER, fluid = fluid, blockLight = 4, skyLight = 12, tint = tint),
                    run(4, 1, STONE, opaque = true, blockLight = 2, skyLight = 7),
                ),
            ),
        )
        val emptyNeighbours = cardinalPages(subject.key).map { page(it, 1, listOf(column())) }

        val first = DistantPageMesher.mesh(subject, DistantPageNeighbourhood(subject, emptyNeighbours))
        val second = DistantPageMesher.mesh(subject, DistantPageNeighbourhood(subject, emptyNeighbours))
        val waterFaces = first.quads.filter { it.fluid != null }
        val bedTop = first.quads.single { it.direction == DistantFaceDirection.UP && it.material == STONE }

        assertTrue(waterFaces.isNotEmpty())
        assertTrue(waterFaces.all { it.fluid == fluid && it.tint == tint && it.blockLight == 4 && it.skyLight == 12 })
        assertEquals(5, bedTop.plane)
        assertEquals(2, bedTop.blockLight)
        assertEquals(7, bedTop.skyLight)
        assertEquals(first.digest, second.digest)
    }

    @Test
    fun `coast and custom fluid preserve independent material tint and light semantics`() {
        val brineMaterial = TerrainSemanticMaterialId("example:brine")
        val brine = DistantFluidSample(brineMaterial, 5, "example:brine")
        val coastTint = DistantTintSample(0x2A7F91, "example:salt_marsh", 12)
        val subject = page(
            key(-2, 4),
            width = 2,
            columns = listOf(
                column(run(0, 12, STONE, opaque = true, blockLight = 1, skyLight = 15)),
                column(
                    run(6, 4, brineMaterial, fluid = brine, blockLight = 6, skyLight = 11, tint = coastTint),
                    run(0, 6, STONE, opaque = true, blockLight = 3, skyLight = 8),
                ),
                column(run(0, 12, STONE, opaque = true, blockLight = 1, skyLight = 14)),
                column(
                    run(6, 4, brineMaterial, fluid = brine, blockLight = 7, skyLight = 10, tint = coastTint),
                    run(0, 6, STONE, opaque = true, blockLight = 2, skyLight = 7),
                ),
            ),
        )
        val emptyNeighbours = cardinalPages(subject.key).map {
            page(it, 2, List(4) { column() })
        }

        val first = DistantPageMesher.mesh(subject, DistantPageNeighbourhood(subject, emptyNeighbours))
        val second = DistantPageMesher.mesh(subject, DistantPageNeighbourhood(subject, emptyNeighbours))
        val fluidFaces = first.quads.filter { it.fluid == brine }

        assertTrue(fluidFaces.isNotEmpty())
        assertTrue(fluidFaces.all { it.material == brineMaterial && it.tint == coastTint })
        assertEquals(setOf(6, 7), fluidFaces.map { it.blockLight }.toSet())
        assertEquals(setOf(10, 11), fluidFaces.map { it.skyLight }.toSet())
        assertTrue(first.quads.any {
            it.direction == DistantFaceDirection.EAST && it.material == STONE && it.maximumVExclusive == 12
        })
        assertEquals(first.digest, second.digest)
        assertEquals("a1103f87f1200c6eb1dc86cdaa256b50d6d25dd72867a71bed217343c235365e", first.digest)
    }

    @Test
    fun `missing and partial neighbours do not invent boundary skirts`() {
        val subject = page(key(0, 0), 1, listOf(column(run(0, 100, STONE, opaque = true))))
        val missing = DistantPageMesher.mesh(subject)
        assertEquals(0, missing.fallbackFaceCount)
        assertFalse(missing.quads.any { it.direction in SIDE_DIRECTIONS })

        val complete = cardinalPages(subject.key).map { page(it, 1, listOf(column())) }
        val exact = DistantPageMesher.mesh(subject, DistantPageNeighbourhood(subject, complete))
        assertEquals(0, exact.fallbackFaceCount)
        assertTrue(exact.quads.filter { it.direction in SIDE_DIRECTIONS }.all {
            it.minimumV == 0 && it.maximumVExclusive == 100
        })

        val incomplete = page(
            key(-1, 0),
            1,
            listOf(column(run(0, 100, STONE, opaque = true))),
            completeness = DistantSourceCompleteness.PARTIAL,
        )
        val mixed = DistantPageMesher.mesh(
            subject,
            DistantPageNeighbourhood(subject, complete.filterNot { it.key == incomplete.key } + incomplete),
        )
        assertFalse(mixed.quads.any { it.direction == DistantFaceDirection.WEST })
        assertEquals(0, mixed.fallbackFaceCount)
    }

    @Test
    fun `mixed detail edge is split deterministically against finer neighbours`() {
        val subject = page(
            key(0, 0, detail = 1),
            width = 1,
            columns = listOf(column(run(0, 10, STONE, opaque = true))),
        )
        val covered = page(key(2, 0), 1, listOf(column(run(0, 10, STONE, opaque = true))))
        val exposed = page(key(2, 1), 1, listOf(column()))

        val artifact = DistantPageMesher.mesh(
            subject,
            DistantPageNeighbourhood(subject, listOf(covered, exposed)),
        )
        val stitched = artifact.quads.single {
            it.direction == DistantFaceDirection.EAST && !it.fallback
        }

        assertEquals(1, stitched.minimumU)
        assertEquals(2, stitched.maximumUExclusive)
        assertEquals(0, stitched.minimumV)
        assertEquals(10, stitched.maximumVExclusive)
        assertEquals(0, artifact.fallbackFaceCount)
    }

    @Test
    fun `partial surface does not emit an unobserved underside`() {
        val subject = page(
            key(0, 0),
            width = 1,
            columns = listOf(column(run(8, 1, STONE, opaque = true))),
            completeness = DistantSourceCompleteness.PARTIAL,
        )

        val artifact = DistantPageMesher.mesh(subject)

        assertTrue(artifact.quads.any { it.direction == DistantFaceDirection.UP })
        assertFalse(artifact.quads.any { it.direction == DistantFaceDirection.DOWN })
        assertFalse(artifact.quads.any { it.direction in SIDE_DIRECTIONS })
    }

    @Test
    fun `parent reduction rejects minority relief and fluid without losing completeness`() {
        val parentKey = key(0, 0, detail = 1)
        val childKeys = DistantPageHierarchy.children(parentKey)
        val fluid = DistantFluidSample(WATER, 0, "water")
        val children = listOf(
            page(childKeys[0], 1, listOf(column(run(0, 10, STONE, opaque = true)))),
            page(childKeys[1], 1, listOf(column(run(0, 5, STONE, opaque = true)))),
            page(childKeys[2], 1, listOf(column(run(0, 8, WATER, fluid = fluid)))),
            page(
                childKeys[3],
                1,
                listOf(column(run(0, 5, STONE, opaque = true))),
                completeness = DistantSourceCompleteness.PARTIAL,
            ),
        )

        val first = DistantVerticalPageReducer.reduce(parentKey, children, sourceRevision = 8)
        val second = DistantVerticalPageReducer.reduce(parentKey, children.reversed(), sourceRevision = 8)

        assertEquals(DistantSourceCompleteness.PARTIAL, first.completeness)
        assertEquals(5, first[0, 0].runs.maxOf(DistantColumnRun::maximumYExclusive))
        assertFalse(first[0, 0].runs.any { it.fluid == fluid })
        assertEquals(first.semanticDigest, second.semanticDigest)
    }

    @Test
    fun `parent reduction retains fluid covered by a child majority`() {
        val parentKey = key(0, 0, detail = 1)
        val childKeys = DistantPageHierarchy.children(parentKey)
        val fluid = DistantFluidSample(WATER, 0, "water")
        val water = column(run(0, 8, WATER, fluid = fluid))
        val children = listOf(
            page(childKeys[0], 1, listOf(water)),
            page(childKeys[1], 1, listOf(water)),
            page(childKeys[2], 1, listOf(water)),
            page(childKeys[3], 1, listOf(column(run(0, 10, STONE, opaque = true)))),
        )

        val reduced = DistantVerticalPageReducer.reduce(parentKey, children, sourceRevision = 9)

        assertTrue(reduced[0, 0].runs.any { it.fluid == fluid })
    }

    @Test
    fun `parent reduction bounds the union of child run boundaries`() {
        val parentKey = key(0, 0, detail = 1)
        val childKeys = DistantPageHierarchy.children(parentKey)
        val alternating = (84 downTo 0).map { y ->
            run(y, 1, if (y % 2 == 0) STONE else DIRT, opaque = true)
        }
        val children = childKeys.mapIndexed { childIndex, child ->
            page(
                child,
                1,
                listOf(DistantVerticalColumn(alternating.filterIndexed { index, _ -> index % 4 != childIndex })),
            )
        }

        val reduced = DistantVerticalPageReducer.reduce(parentKey, children, sourceRevision = 9)

        assertEquals(DistantVerticalColumn.MAXIMUM_RUNS, reduced[0, 0].runs.size)
        assertEquals(0, reduced[0, 0].runs.last().minimumY)
        assertEquals(85, reduced[0, 0].runs.first().maximumYExclusive)
    }

    private fun page(
        key: TerrainPageKey,
        width: Int,
        columns: List<DistantVerticalColumn>,
        completeness: DistantSourceCompleteness = DistantSourceCompleteness.COMPLETE,
    ) = DistantVerticalPage(key, width, originY = 0, sourceRevision = 3, completeness, columns)

    private fun column(vararg runs: DistantColumnRun) = DistantVerticalColumn(runs.toList())

    private fun run(
        minimumY: Int,
        height: Int,
        material: TerrainSemanticMaterialId,
        opaque: Boolean = false,
        fluid: DistantFluidSample? = null,
        blockLight: Int = 0,
        skyLight: Int = 15,
        tint: DistantTintSample? = null,
        flags: Set<DistantRunFlag> = emptySet(),
    ) = DistantColumnRun(
        minimumY,
        height,
        material,
        fluid,
        blockLight,
        skyLight,
        tint,
        flags + if (opaque) setOf(DistantRunFlag.OPAQUE) else emptySet(),
        confidence = 100,
    )

    private fun cardinalPages(page: TerrainPageKey) = DistantPageHierarchy.cardinalNeighbours(page)

    private fun key(x: Long, z: Long, detail: Int = 0) =
        TerrainPageKey(TerrainDomain.DISTANT, detail, x, 0L, z, WORLD_EPOCH)

    private companion object {
        const val WORLD_EPOCH = 23L
        val STONE = TerrainSemanticMaterialId("minecraft:stone")
        val DIRT = TerrainSemanticMaterialId("minecraft:dirt")
        val WATER = TerrainSemanticMaterialId("minecraft:water")
        val SIDE_DIRECTIONS = setOf(
            DistantFaceDirection.NORTH,
            DistantFaceDirection.SOUTH,
            DistantFaceDirection.WEST,
            DistantFaceDirection.EAST,
        )
    }
}
