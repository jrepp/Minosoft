/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the license, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.local.generator.ChunkBuilder
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantRunFlag
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class DistantGeneratedLightingTest {
    @Test(enabled = false)
    fun assertGeneratedPageUsesDetachedHaloLighting() {
        val session = SessionTestUtil.createSession(light = true)
        val center = ChunkPosition()
        val neighbourhood = linkedMapOf<ChunkPosition, ChunkBuilder>()
        for (z in -1..1) {
            for (x in -1..1) {
                val position = ChunkPosition(x, z)
                neighbourhood[position] = ChunkBuilder(session.world, position)
            }
        }
        val stone = session.registries.block[minecraft("stone")]!!.states.default
        val water = session.registries.block[minecraft("water")]!!.states.default
        val torch = session.registries.block[minecraft("torch")]!!.states.default
        val builder = neighbourhood.getValue(center)
        builder[0, 0, 8] = stone
        builder[0, 1, 8] = water
        neighbourhood.getValue(ChunkPosition(-1, 0))[15, 1, 8] = torch

        val page = DistantWorldVerticalSampler.captureGenerated(builder, neighbourhood)
        val waterRun = page.columns[8 * 16].runs.single { it.minimumY == 1 }

        assertEquals(page.completeness, DistantSourceCompleteness.COMPLETE)
        assertEquals(waterRun.material?.value, "minecraft:water")
        assertEquals(waterRun.blockLight, torch.luminance - 1)
        assertEquals(waterRun.skyLight, 14)
        assertTrue(DistantRunFlag.GENERATED in waterRun.flags)
    }
}
