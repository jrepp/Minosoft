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

package de.bixilon.minosoft.gui.rendering.terrain.runtime

import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainRuntimePipelineTest {
    @Test
    fun `connectivity is conservative and detects an opaque dividing wall`() {
        val empty = TerrainConnectivityBuilder.build(BooleanArray(ChunkSize.BLOCKS_PER_SECTION))
        for (from in Directions.VALUES) {
            for (to in Directions.VALUES) assertTrue(empty.connects(from, to))
        }

        val opaque = BooleanArray(ChunkSize.BLOCKS_PER_SECTION) { true }
        val closed = TerrainConnectivityBuilder.build(opaque)
        assertFalse(closed.connects(Directions.WEST, Directions.EAST))
        assertFalse(closed.connects(Directions.DOWN, Directions.UP))

        val wall = BooleanArray(ChunkSize.BLOCKS_PER_SECTION)
        for (y in 0 until 16) {
            for (z in 0 until 16) wall[index(8, y, z)] = true
        }
        val divided = TerrainConnectivityBuilder.build(wall)
        assertFalse(divided.connects(Directions.WEST, Directions.EAST))
        assertTrue(divided.connects(Directions.WEST, Directions.UP))
        assertTrue(divided.connects(Directions.EAST, Directions.DOWN))
    }

    @Test
    fun `graph traversal respects measured connectivity and falls back conservatively`() {
        val camera = SectionPosition(0, 0, 0)
        val blocked = mapOf(
            camera to TerrainVisibilityNode(camera, TerrainDirectionalVisibility.ALL),
            SectionPosition(1, 0, 0) to TerrainVisibilityNode(
                SectionPosition(1, 0, 0),
                TerrainDirectionalVisibility.NONE,
            ),
            SectionPosition(2, 0, 0) to TerrainVisibilityNode(
                SectionPosition(2, 0, 0),
                TerrainDirectionalVisibility.ALL,
            ),
        )
        assertEquals(
            listOf(camera, SectionPosition(1, 0, 0)),
            TerrainVisibilityTraversal.traverse(camera, blocked),
        )

        val fallback = blocked.toMutableMap()
        fallback[SectionPosition(1, 0, 0)] = TerrainVisibilityNode(SectionPosition(1, 0, 0), null)
        assertEquals(3, TerrainVisibilityTraversal.traverse(camera, fallback).size)
    }

    @Test
    fun `allocator coalesces released ranges and rejects invalid release`() {
        val allocator = TerrainRangeAllocator(64)
        val first = assertNotNull(allocator.allocate(12))
        val second = assertNotNull(allocator.allocate(20))
        assertEquals(32, allocator.allocatedBytes)
        allocator.release(first)
        allocator.release(second)
        assertEquals(0, allocator.allocatedBytes)
        assertEquals(64, allocator.largestFreeRange)
        assertFailsWith<IllegalArgumentException> {
            allocator.release(TerrainRange(60, 8))
        }
    }

    @Test
    fun `failed replacement preserves published section and releases candidates`() {
        val staging = FailingStaging(128)
        val position = SectionPosition(0, 0, 0)
        val storage = TerrainRegionStorage(TerrainRegionKey.of(position), 128, staging)

        val initial = product(position, 1L, byteArrayOf(1, 2, 3, 4))
        val published = assertNotNull(storage.publish(initial))
        val initialRange = published.streams.single().vertexRange
        assertContentEquals(initial.streams.single().vertices, staging.delegate.read(initialRange))

        staging.fail = true
        assertFailsWith<IllegalStateException> {
            storage.publish(product(position, 2L, byteArrayOf(9, 9, 9, 9)))
        }
        assertEquals(1L, storage.get(position)?.modelRevision)
        assertContentEquals(initial.streams.single().vertices, staging.delegate.read(initialRange))
        assertEquals(4L, storage.metrics().allocatedBytes)
        assertEquals(1L, storage.metrics().failures)
    }

    @Test
    fun `allocation failure preserves old geometry without throwing`() {
        val position = SectionPosition(0, 0, 0)
        val storage = TerrainRegionStorage(TerrainRegionKey.of(position), 16)
        assertNotNull(storage.publish(product(position, 1L, ByteArray(12) { 1 })))

        assertNull(storage.publish(product(position, 2L, ByteArray(12) { 2 })))
        assertEquals(1L, storage.get(position)?.modelRevision)
        assertEquals(12L, storage.metrics().allocatedBytes)
        assertEquals(1L, storage.metrics().failures)
    }

    @Test
    fun `end to end publication visibility batching replacement and unload`() {
        val pipeline = TerrainRuntimePipeline(regionCapacityBytes = 256)
        val camera = SectionPosition(0, 0, 0)
        val second = SectionPosition(1, 0, 0)
        val third = SectionPosition(2, 0, 0)

        assertTrue(pipeline.publish(product(camera, 1L, ByteArray(16) { 1 })))
        assertTrue(pipeline.publish(product(second, 1L, ByteArray(20) { 2 })))
        assertTrue(pipeline.publish(product(third, 1L, ByteArray(24) { 3 })))

        val candidates = setOf(camera, second, third)
        val first = pipeline.buildBatches(
            camera,
            candidates,
            TerrainMaterialClass.OPAQUE,
            terrainGeneration = 7L,
            materialGeneration = "m1",
        )
        assertEquals(1, first.size)
        assertEquals(3, first.single().commands.size)

        val cached = pipeline.buildBatches(
            camera,
            candidates,
            TerrainMaterialClass.OPAQUE,
            terrainGeneration = 7L,
            materialGeneration = "m1",
        )
        assertEquals(first, cached)
        assertEquals(1L, pipeline.metrics().batchHits)

        assertTrue(pipeline.publish(product(second, 2L, ByteArray(8) { 4 })))
        val replaced = pipeline.buildBatches(
            camera,
            candidates,
            TerrainMaterialClass.OPAQUE,
            terrainGeneration = 7L,
            materialGeneration = "m1",
        )
        assertEquals(3, replaced.single().commands.size)
        assertTrue(pipeline.metrics().batchBuilds >= 2L)
        assertTrue(pipeline.remove(third))

        val afterUnload = pipeline.buildBatches(
            camera,
            candidates,
            TerrainMaterialClass.OPAQUE,
            terrainGeneration = 7L,
            materialGeneration = "m1",
        )
        assertEquals(2, afterUnload.single().commands.size)
        val metrics = pipeline.metrics()
        assertEquals(2, metrics.publishedSections)
        assertEquals(2, metrics.visibleSections)
        assertEquals(24L, metrics.allocatedBytes)
        assertEquals(2, metrics.drawCommands)
    }

    @Test
    fun `budget scheduler orders urgency and enforces both budgets`() {
        val selection = TerrainBudgetScheduler.select(
            listOf(
                TerrainBudgetItem("deferred", TerrainUrgency.DEFERRED, 0, 2, 2),
                TerrainBudgetItem("next", TerrainUrgency.NEXT_FRAME, 1, 4, 4),
                TerrainBudgetItem("same", TerrainUrgency.SAME_FRAME, 2, 5, 5),
            ),
            cpuBudgetNanos = 9,
            uploadBudgetBytes = 9,
        )
        assertEquals(listOf("same", "next"), selection.selected.map { it.value })
        assertEquals(listOf("deferred"), selection.deferred.map { it.value })
        assertEquals(9, selection.cpuNanos)
        assertEquals(9, selection.uploadBytes)
    }

    private fun product(
        position: SectionPosition,
        revision: Long,
        bytes: ByteArray,
    ) = TerrainBuildProduct(
        position = position,
        modelRevision = revision,
        backendGeneration = 7L,
        materialGeneration = "m1",
        connectivity = TerrainDirectionalVisibility.ALL,
        streams = listOf(
            TerrainMaterialStream(
                TerrainMaterialClass.OPAQUE,
                TerrainFaceSegment.UNASSIGNED,
                bytes,
            ),
        ),
    )

    private fun index(x: Int, y: Int, z: Int) = y * 16 * 16 + z * 16 + x

    private class FailingStaging(override val capacity: Int) : TerrainStagingBuffer {
        val delegate = ByteArrayTerrainStagingBuffer(capacity)
        var fail = false

        override fun write(range: TerrainRange, source: ByteArray) {
            if (fail) throw IllegalStateException("upload failed")
            delegate.write(range, source)
        }
    }
}
