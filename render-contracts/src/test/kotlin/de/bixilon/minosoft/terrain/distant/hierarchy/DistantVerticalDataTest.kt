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

import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DistantVerticalDataTest {
    @Test
    fun `vertical column is top down bounded and deterministic`() {
        val runs = listOf(run(90, 10, "stone", opaque = true), run(70, 20, "dirt"))
        val first = DistantVerticalColumn(runs)
        val second = DistantVerticalColumn(runs.toList())

        assertEquals(first.digest, second.digest)
        assertThrows<IllegalArgumentException> {
            DistantVerticalColumn(listOf(runs[1], runs[0]))
        }
        assertThrows<IllegalArgumentException> {
            DistantVerticalColumn(listOf(run(80, 20, "stone"), run(85, 10, "dirt")))
        }
    }

    @Test
    fun `reducer preserves extrema opaque fluid and strongest semantic transitions`() {
        val water = DistantFluidSample(TerrainSemanticMaterialId("minecraft:water"), 0, "water")
        val runs = listOf(
            run(100, 1, "air", flags = setOf(DistantRunFlag.VOID)),
            run(99, 1, "water", fluid = water),
            run(95, 4, "stone", opaque = true),
            run(90, 5, "stone", blockLight = 15, flags = setOf(DistantRunFlag.EMISSIVE)),
            run(80, 10, "dirt"),
            run(60, 20, "stone"),
        )
        val column = DistantVerticalColumn(runs)
        val reduced = DistantColumnReducer.reduce(column, 5)

        assertEquals(5, reduced.runs.size)
        assertTrue(reduced.runs.first() === runs.first())
        assertTrue(reduced.runs.last() === runs.last())
        assertTrue(reduced.runs.any { it.fluid != null })
        assertTrue(reduced.runs.any { DistantRunFlag.OPAQUE in it.flags })
        assertTrue(reduced.runs.any { DistantRunFlag.EMISSIVE in it.flags })
        assertEquals(reduced.digest, DistantColumnReducer.reduce(column, 5).digest)
        assertSame(column, DistantColumnReducer.reduce(column, runs.size))
    }

    private fun run(
        y: Int,
        height: Int,
        material: String,
        opaque: Boolean = false,
        fluid: DistantFluidSample? = null,
        blockLight: Int = 0,
        flags: Set<DistantRunFlag> = emptySet(),
    ) = DistantColumnRun(
        minimumY = y,
        height = height,
        material = if (DistantRunFlag.VOID in flags) null else TerrainSemanticMaterialId("minecraft:$material"),
        fluid = fluid,
        blockLight = blockLight,
        skyLight = 15,
        tint = null,
        flags = flags + if (opaque) setOf(DistantRunFlag.OPAQUE) else emptySet(),
        confidence = 100,
    )
}
