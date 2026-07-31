/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the license, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.world.positions.ChunkPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DistantGeneratedChunkCacheTest {
    @Test
    fun `neighbourhood preparation never exceeds the chunk generation budget`() {
        val cache = DistantGeneratedChunkCache<ChunkPosition>()
        val center = ChunkPosition(10, 20)
        var generated = 0

        repeat(8) {
            val preparation = cache.prepare(center, 1) { generated++; it }
            assertEquals(1, preparation.generatedCount)
            assertNull(preparation.chunks)
        }
        val complete = cache.prepare(center, 1) { generated++; it }

        assertEquals(9, generated)
        assertEquals(1, complete.generatedCount)
        val chunks = assertNotNull(complete.chunks)
        assertEquals(9, chunks.size)
        assertEquals(center, chunks[center])
    }

    @Test
    fun `adjacent centers reuse six of nine halo chunks`() {
        val cache = DistantGeneratedChunkCache<ChunkPosition>()
        var generated = 0
        val first = cache.prepare(ChunkPosition(0, 0), 9) { generated++; it }
        val second = cache.prepare(ChunkPosition(1, 0), 9) { generated++; it }

        assertNotNull(first.chunks)
        assertEquals(3, second.generatedCount)
        assertNotNull(second.chunks)
        assertEquals(12, generated)
    }

    @Test
    fun `zero budget reports an incomplete neighbourhood without invoking generation`() {
        val cache = DistantGeneratedChunkCache<String>()
        val result = cache.prepare(ChunkPosition(), 0) { error("must not generate") }

        assertEquals(0, result.generatedCount)
        assertNull(result.chunks)
    }

    @Test
    fun `clear prevents reuse across a world owner or epoch boundary`() {
        val cache = DistantGeneratedChunkCache<ChunkPosition>()
        val center = ChunkPosition(4, 5)
        cache.prepare(center, 9) { it }
        cache.clear()

        val rebuilt = cache.prepare(center, 9) { it }

        assertEquals(9, rebuilt.generatedCount)
        assertNotNull(rebuilt.chunks)
    }
}
