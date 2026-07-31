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

package de.bixilon.minosoft.gui.rendering.terrain.near

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.world.chunk.heightmap.Heightmap
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState

class NearSurfaceCoverageTest {
    @Test
    fun `every surface section must be uploaded before chunk is ready`() {
        val heights = IntArray(256) { 64 }
        heights[0] = 96
        val required = NearSurfaceCoverage.requiredSections(ArrayHeightmap(heights))

        assertEquals(setOf(3, 5), required)
        assertFalse(NearSurfaceCoverage.isReady(required, setOf(3)))
        assertFalse(NearSurfaceCoverage.isReady(required, setOf(5)))
        assertTrue(NearSurfaceCoverage.isReady(required, setOf(3, 5)))
        assertTrue(NearSurfaceCoverage.isReady(required, setOf(2, 3, 5, 6)))
    }

    @Test
    fun `unknown or fixed maximum height remains conservatively uncovered`() {
        assertTrue(NearSurfaceCoverage.requiredSections(ArrayHeightmap(IntArray(256) { Int.MIN_VALUE })).isEmpty())
        assertTrue(NearSurfaceCoverage.requiredSections(ArrayHeightmap(IntArray(256) { Int.MAX_VALUE })).isEmpty())
        assertFalse(NearSurfaceCoverage.isReady(emptySet(), setOf(0)))
    }

    @Test
    fun `coverage revision changes only when spatial readiness changes`() {
        val index = NearSurfaceCoverageIndex()
        val position = ChunkPosition(2, 3)

        assertFalse(index.update(position, setOf(3, 5), setOf(3)))
        assertEquals(0L, index.revision)
        assertTrue(index.update(position, setOf(3, 5), setOf(3, 5)))
        assertEquals(1L, index.revision)
        assertFalse(index.update(position, setOf(3, 5), setOf(3, 5)))
        assertEquals(1L, index.revision)
        assertFalse(index.update(position, setOf(3, 5), setOf(2, 3, 5)))
        assertEquals(1L, index.revision)
        assertTrue(index.update(position, setOf(3, 5), setOf(3)))
        assertEquals(2L, index.revision)
    }

    @Test
    fun `production lifecycle retains ready ownership through replacement and resets by provider`() {
        var environment = NearSurfaceCoverageEnvironment(7L, 1L, 0L)
        val index = NearSurfaceCoverageIndex { environment }
        val position = ChunkPosition(2, 3)
        index.update(position, setOf(3), setOf(3))
        val readyRevision = index.revision

        environment = environment.copy(frame = 1L)
        index.requested(position)
        environment = environment.copy(frame = 2L)
        index.building(position)
        environment = environment.copy(frame = 3L)
        index.uploadPending(position)
        environment = environment.copy(frame = 4L)
        index.requested(position)

        val retry = index.snapshot()
        assertEquals(readyRevision, retry.revision)
        assertEquals(setOf(position), retry.chunks)
        assertEquals(TerrainCoverageState.REQUESTED, retry.lifecycle.cells.single().state)
        assertTrue(retry.lifecycle.cells.single().contributesCoverage)

        environment = environment.copy(providerGeneration = 2L, frame = 5L)
        val reset = index.snapshot()
        assertTrue(reset.chunks.isEmpty())
        assertEquals(2L, reset.revision)
        assertEquals(2L, reset.lifecycle.providerGeneration)
    }

    @Test
    fun `clear retires ready coverage before removing it`() {
        var environment = NearSurfaceCoverageEnvironment(7L, 1L, 0L)
        val index = NearSurfaceCoverageIndex { environment }
        val position = ChunkPosition(2, 3)
        index.update(position, setOf(3), setOf(3))

        environment = environment.copy(frame = 1L)
        assertTrue(index.clear())
        assertTrue(index.snapshot().chunks.isEmpty())
        assertTrue(index.snapshot().lifecycle.cells.isEmpty())
        assertEquals(2L, index.revision)
    }

    private class ArrayHeightmap(private val values: IntArray) : Heightmap {
        override fun recalculate() = Unit
        override fun get(x: Int, z: Int) = values[(z shl 4) or x]
        override fun get(index: Int) = values[index]
        override fun get(xz: InChunkPosition) = values[xz.xz]
        override fun get(xz: InSectionPosition) = values[xz.xz]
        override fun onBlockChange(position: InChunkPosition, state: BlockState?) = Unit
    }
}
