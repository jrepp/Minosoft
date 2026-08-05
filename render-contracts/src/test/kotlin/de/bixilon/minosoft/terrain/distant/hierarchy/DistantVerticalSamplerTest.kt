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
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DistantVerticalSamplerTest {
    @Test
    fun `sampler retains overhang cave fluid light tint and detaches inputs`() {
        val samples = HashMap<Int, DistantVoxelSample>()
        samples[12] = voxel("grass", opaque = true, tint = DistantTintSample(0x55AA33, "minecraft:plains", 4))
        samples[11] = voxel("stone", opaque = true)
        samples[8] = voxel("water", fluid = DistantFluidSample(id("water"), 3, "minecraft:water"), sky = 7)
        samples[7] = voxel("stone", opaque = true, block = 9, emissive = true)
        samples[3] = voxel("stone", opaque = true)

        val page = DistantVerticalSampler.capture(
            key = key(),
            width = 1,
            minimumY = 0,
            maximumYExclusive = 16,
            sourceRevision = 8,
            completeness = DistantSourceCompleteness.COMPLETE,
        ) { _, y, _ -> samples[y] ?: DistantVoxelSample(null, skyLight = 4) }
        samples.clear()

        val runs = page[0, 0].runs
        assertEquals(listOf(13, 12, 11, 9, 8, 7, 4, 3), runs.map(DistantColumnRun::minimumY))
        assertTrue(DistantRunFlag.VOID in runs.first().flags)
        assertEquals(3, runs.first().height)
        assertEquals(4, runs.first().skyLight)
        assertTrue(DistantRunFlag.CAVE in runs.single { it.minimumY == 9 }.flags)
        assertTrue(DistantRunFlag.CAVE in runs.single { it.minimumY == 4 }.flags)
        assertEquals(3, runs.single { it.minimumY == 8 }.fluid?.level)
        assertEquals(9, runs.single { it.minimumY == 7 }.blockLight)
        assertEquals(0x55AA33, runs.single { it.minimumY == 12 }.tint?.resolvedRgb)
        assertEquals("minecraft:grass", runs.single { it.minimumY == 12 }.material?.value)
    }

    @Test
    fun `sampler bounds exterior light and deterministically limits pathological columns`() {
        val column = DistantVerticalSampler.captureColumn(0, 0, 0, 160) { _, y, _ ->
            if (y < 10 || y >= 150) DistantVoxelSample(null, skyLight = 12)
            else voxel(if (y % 2 == 0) "stone" else "dirt", opaque = true)
        }

        assertEquals(DistantVerticalColumn.MAXIMUM_RUNS, column.runs.size)
        assertEquals(150, column.runs.first().minimumY)
        assertEquals(10, column.runs.first().height)
        assertEquals(12, column.runs.first().skyLight)
        assertTrue(DistantRunFlag.VOID in column.runs.first().flags)
        assertEquals(10, column.runs.last().minimumY)
        assertEquals(column.digest, DistantVerticalSampler.captureColumn(0, 0, 0, 160) { _, y, _ ->
            if (y < 10 || y >= 150) DistantVoxelSample(null, skyLight = 12)
            else voxel(if (y % 2 == 0) "stone" else "dirt", opaque = true)
        }.digest)
    }

    @Test
    fun `sampler retains bounded light without rendering an empty column`() {
        val column = DistantVerticalSampler.captureColumn(0, 0, -64, 320) { _, y, _ ->
            DistantVoxelSample(
                material = null,
                blockLight = if (y == 72) 9 else 0,
                skyLight = 15,
            )
        }

        val exterior = column.runs.single()
        assertEquals(-64, exterior.minimumY)
        assertEquals(384, exterior.height)
        assertEquals(9, exterior.blockLight)
        assertEquals(15, exterior.skyLight)
        assertTrue(DistantRunFlag.VOID in exterior.flags)
    }

    @Test
    fun `sampler validates geometry and bounded height before invoking an accessor`() {
        var samples = 0
        assertFailsWith<IllegalArgumentException> {
            DistantVerticalSampler.capture(
                key(), 65, 0, 16, 1, DistantSourceCompleteness.COMPLETE,
            ) { _, _, _ ->
                samples++
                DistantVoxelSample(null)
            }
        }
        assertEquals(0, samples)

        assertFailsWith<IllegalArgumentException> {
            DistantVerticalSampler.captureColumn(
                0, 0, Int.MIN_VALUE, Int.MAX_VALUE,
            ) { _, _, _ ->
                samples++
                DistantVoxelSample(null)
            }
        }
        assertEquals(0, samples)
    }

    private fun key() = TerrainPageKey(TerrainDomain.DISTANT, 0, -2, 0, 3, 7)

    private fun id(value: String) = TerrainSemanticMaterialId("minecraft:$value")

    private fun voxel(
        material: String,
        opaque: Boolean = false,
        fluid: DistantFluidSample? = null,
        block: Int = 0,
        sky: Int = 15,
        tint: DistantTintSample? = null,
        emissive: Boolean = false,
    ) = DistantVoxelSample(id(material), fluid, block, sky, tint, opaque, emissive)
}
