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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DistantPageMeshingTest {
    @Test
    fun `flat page greedily merges to a closed box with boundary fallback`() {
        val column = column(run(0, 8, STONE, opaque = true))
        val page = page(key(0, 0), width = 2, columns = List(4) { column })

        val artifact = DistantPageMesher.mesh(page)

        assertEquals(16, artifact.primitiveFaceCount)
        assertEquals(6, artifact.mergedFaceCount)
        assertEquals(4, artifact.fallbackFaceCount)
        assertEquals(24, artifact.vertexCount)
        assertEquals(36, artifact.indexCount)
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
        assertEquals("24fda655859bdaf5bbc279f272596267fa3ae9c20009ea720f1c6185942894c5", first.digest)
    }

    @Test
    fun `fallback is bounded and used only for missing or incomplete neighbours`() {
        val subject = page(key(0, 0), 1, listOf(column(run(0, 100, STONE, opaque = true))))
        val missing = DistantPageMesher.mesh(subject, maximumFallbackDepth = 32)
        assertEquals(4, missing.fallbackFaceCount)
        assertTrue(missing.quads.filter(DistantMeshQuad::fallback).all {
            it.minimumV == 68 && it.maximumVExclusive == 100
        })

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
        assertTrue(mixed.quads.any { it.direction == DistantFaceDirection.WEST && it.fallback })
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
        assertNotEquals(0, artifact.fallbackFaceCount)
    }

    @Test
    fun `parent reduction preserves extrema fluid and minimum completeness deterministically`() {
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
        assertEquals(10, first[0, 0].runs.maxOf(DistantColumnRun::maximumYExclusive))
        assertTrue(first[0, 0].runs.any { it.fluid == fluid })
        assertEquals(first.semanticDigest, second.semanticDigest)
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
        val WATER = TerrainSemanticMaterialId("minecraft:water")
        val SIDE_DIRECTIONS = setOf(
            DistantFaceDirection.NORTH,
            DistantFaceDirection.SOUTH,
            DistantFaceDirection.WEST,
            DistantFaceDirection.EAST,
        )
    }
}
