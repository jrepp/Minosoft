/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.light.terrain

import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.world.chunk.LightTestingUtil
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class SmoothTerrainLightingTest {
    @Test
    fun `water top retains four corner block light samples`() {
        val chunk = LightTestingUtil.createEmptyChunk(ChunkPosition.EMPTY)
        val section = requireNotNull(chunk.sections.create(0, light = false))
        val y = 9
        for (x in 7..9) {
            for (z in 7..9) {
                section.light.light[InSectionPosition(x, y, z)] =
                    LightLevel(block = if (x == 9 && z == 9) 15 else 0, sky = 0)
            }
        }

        val result = SmoothTerrainLighting(chunk).calculate(
            BlockPosition(8, 8, 8),
            Directions.UP,
            WATER_TOP,
            LightLevel.EMPTY.index,
            ambientOcclusion = false,
        )

        assertTrue(result.light.toSet().size > 1)
        assertNotEquals(result.light[0], result.light[2])
        assertTrue(result.brightness.all { it == 1.0f })
    }

    @Test
    fun `inset terrain face interpolates toward its own light`() {
        val chunk = LightTestingUtil.createEmptyChunk(ChunkPosition.EMPTY)
        val section = requireNotNull(chunk.sections.create(0, light = false))
        for (x in 7..9) {
            for (z in 7..9) {
                section.light.light[InSectionPosition(x, 9, z)] = LightLevel.EMPTY
            }
        }
        val pipeline = SmoothTerrainLighting(chunk)
        val fallback = LightLevel(block = 15, sky = 0)

        val boundary = pipeline.calculate(BlockPosition(8, 8, 8), Directions.UP, WATER_TOP, fallback.index, false)
        val inset = pipeline.calculate(BlockPosition(8, 8, 8), Directions.UP, INSET_TOP, fallback.index, false)

        assertTrue(LightLevel(inset.light[0].toByte()).block > LightLevel(boundary.light[0].toByte()).block)
    }

    @Test
    fun `detached snapshot lighting does not observe later section mutation`() {
        val chunk = LightTestingUtil.createChunkWithNeighbours()
        val section = requireNotNull(chunk.sections.create(0, light = false))
        val sampled = InSectionPosition(9, 9, 9)
        section.light.light[sampled] = LightLevel(block = 13, sky = 0)
        val snapshot = TerrainBuildSnapshot.capture(section)
        val position = BlockPosition(8, 8, 8)

        val before = SmoothTerrainLighting(snapshot).calculate(
            position,
            Directions.UP,
            WATER_TOP,
            LightLevel.EMPTY.index,
            ambientOcclusion = false,
        )
        section.light.light[sampled] = LightLevel.EMPTY
        val after = SmoothTerrainLighting(snapshot).calculate(
            position,
            Directions.UP,
            WATER_TOP,
            LightLevel.EMPTY.index,
            ambientOcclusion = false,
        )

        assertTrue(after.light.contentEquals(before.light))
        assertTrue(after.brightness.contentEquals(before.brightness))
        val current = SmoothTerrainLighting(chunk).calculate(
            position,
            Directions.UP,
            WATER_TOP,
            LightLevel.EMPTY.index,
            ambientOcclusion = false,
        )
        assertFalse(current.light.contentEquals(before.light))
    }

    private companion object {
        val WATER_TOP = floatArrayOf(
            0.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f,
            0.0f, 1.0f, 1.0f,
        )
        val INSET_TOP = WATER_TOP.copyOf().apply {
            this[1] = 0.5f
            this[4] = 0.5f
            this[7] = 0.5f
            this[10] = 0.5f
        }
    }
}
