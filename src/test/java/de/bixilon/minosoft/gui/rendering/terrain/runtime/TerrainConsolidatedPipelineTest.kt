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
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageMasking
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTracker
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTransitionPolicy
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactStream
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainProcessScopeId
import de.bixilon.minosoft.terrain.runtime.TerrainSubmission
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionCompletion
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionState
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildOutcome
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildRuntime
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildUrgency
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSchedulerTenantId
import de.bixilon.minosoft.terrain.runtime.storage.ByteArrayTerrainRegionDevice
import de.bixilon.minosoft.terrain.runtime.storage.TerrainBatchCache
import de.bixilon.minosoft.terrain.runtime.storage.TerrainBufferArena
import de.bixilon.minosoft.terrain.runtime.storage.TerrainConventionalDrawLoop
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionKey
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionStorage
import de.bixilon.minosoft.terrain.runtime.storage.TerrainViewKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerrainConsolidatedPipelineTest {
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
    fun `real consolidated contracts execute the headless terrain pipeline`() {
        val contextClosed = AtomicBoolean()
        val runtime = TerrainBuildRuntime<WorkerContext, TerrainMeshArtifact>(
            workerCount = 1,
            queueCapacity = 2,
            threadNamePrefix = "Terrain consolidated pipeline test",
            contextFactory = { WorkerContext(contextClosed) },
        )
        val page = TerrainPageKey(TerrainDomain.NEAR, 0, 0L, 0L, 0L, WORLD_EPOCH)
        val identity = TerrainBuildIdentity(
            page = page,
            requestRevision = 1L,
            capturedModelRevision = 1L,
            providerGeneration = PROVIDER_GENERATION,
            layoutGeneration = LAYOUT_GENERATION,
            materialGeneration = MATERIAL_GENERATION,
            coverageGeneration = 0L,
            prioritySequence = 1L,
        )
        val coverage = TerrainCoverageTracker(WORLD_EPOCH, PROVIDER_GENERATION)
        coverage.transition(page, TerrainCoverageState.REQUESTED, surfaceRelevant = true, frame = 0L)
        coverage.transition(page, TerrainCoverageState.BUILDING, surfaceRelevant = true, frame = 1L)

        try {
            assertNotNull(runtime.submit(TENANT, identity, TerrainBuildUrgency.IMMEDIATE) { _, token ->
                check(!token.isCancelled)
                artifact(identity)
            })
            val artifact = awaitArtifact(runtime)
            coverage.transition(page, TerrainCoverageState.UPLOAD_PENDING, surfaceRelevant = true, frame = 2L)

            val deviceId = TerrainDeviceRuntimeId(TerrainProcessScopeId(1L), 1L)
            val completion = Completion(deviceId)
            val device = ByteArrayTerrainRegionDevice(
                deviceRuntime = deviceId,
                layoutGeneration = LAYOUT_GENERATION,
                vertexCapacityBytes = 64,
                indexCapacityBytes = 32,
            )
            val storage = TerrainRegionStorage(
                key = TerrainRegionKey.containing(page, 8),
                regionWidth = 8,
                device = device,
                completion = completion,
            )
            val published = artifact.use { assertNotNull(storage.publish(it)) }
            coverage.transition(page, TerrainCoverageState.READY, surfaceRelevant = true, frame = 3L)

            val stream = published.streams.single()
            assertContentEquals(VERTICES, device.read(TerrainBufferArena.VERTEX, stream.vertexRange))
            assertContentEquals(INDICES, device.read(TerrainBufferArena.INDEX, stream.indexRange))

            val visible = TerrainVisibilityTraversal.traverse(
                camera = SectionPosition(0, 0, 0),
                nodes = mapOf(SectionPosition(0, 0, 0) to TerrainVisibilityNode(
                    SectionPosition(0, 0, 0),
                    TerrainDirectionalVisibility.ALL,
                )),
            )
            assertEquals(listOf(SectionPosition(0, 0, 0)), visible)

            val batch = assertNotNull(TerrainBatchCache().batch(
                storage = storage,
                material = MATERIAL,
                view = TerrainViewKey("main"),
                orderedPages = listOf(page),
                layoutGeneration = LAYOUT_GENERATION,
                materialGeneration = MATERIAL_GENERATION,
            ))
            val commands = ArrayList<TerrainPageKey>()
            assertEquals(1, TerrainConventionalDrawLoop.submit(batch) { commands += it.page })
            assertEquals(listOf(page), commands)
            batch.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
            batch.close()

            val readyCell = coverage.snapshot(frame = 4L).cells.single()
            val mask = TerrainCoverageMasking.decide(
                readyCell,
                TerrainCoverageTransitionPolicy.CONSERVATIVE_OVERLAP,
            )
            assertTrue(mask.drawNear)
            assertTrue(mask.drawDistant)

            coverage.transition(page, TerrainCoverageState.RETIRING, surfaceRelevant = true, frame = 5L)
            assertTrue(storage.remove(page))
            assertEquals(1, storage.metrics().retiredPages)
            completion.states[TerrainSubmissionSerial(1L)] = TerrainSubmissionState.COMPLETE
            assertEquals(1, storage.collectRetired())
            coverage.transition(page, TerrainCoverageState.ABSENT, surfaceRelevant = false, frame = 6L)

            val metrics = storage.metrics()
            assertEquals(0, metrics.activePages)
            assertEquals(0, metrics.retiredPages)
            assertEquals(0, metrics.vertexAllocatedBytes)
            assertEquals(0, metrics.indexAllocatedBytes)
            assertTrue(coverage.snapshot(frame = 6L).coveredPages.isEmpty())
            assertEquals(0, runtime.snapshot().outstanding)
        } finally {
            runtime.close()
        }
        assertTrue(contextClosed.get())
    }

    private fun awaitArtifact(runtime: TerrainBuildRuntime<WorkerContext, TerrainMeshArtifact>): TerrainMeshArtifact {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            var artifact: TerrainMeshArtifact? = null
            runtime.drain(1) { completion ->
                artifact = when (val outcome = completion.outcome) {
                    is TerrainBuildOutcome.Success -> outcome.value
                    is TerrainBuildOutcome.Cancelled -> error("Terrain build was cancelled")
                    is TerrainBuildOutcome.Failure -> throw outcome.error
                }
            }
            artifact?.let { return it }
            Thread.onSpinWait()
        }
        error("Timed out waiting for the terrain build completion")
    }

    private fun artifact(identity: TerrainBuildIdentity) = TerrainMeshArtifact(
        identity = identity,
        streams = listOf(TerrainArtifactStream(
            partitionId = "near:opaque",
            material = MATERIAL,
            vertexStrideBytes = 4,
            vertexBytes = VERTICES,
            indexElementBytes = 2,
            indexBytes = INDICES,
        )),
        bounds = null,
        connectivityBits = TerrainDirectionalVisibility.ALL.encodedBits,
        coverageContribution = listOf(identity.page),
    )

    private fun index(x: Int, y: Int, z: Int) = y * 16 * 16 + z * 16 + x

    private class WorkerContext(private val closed: AtomicBoolean) : AutoCloseable {
        override fun close() {
            check(closed.compareAndSet(false, true))
        }
    }

    private class Completion(override val deviceRuntime: TerrainDeviceRuntimeId) : TerrainSubmissionCompletion {
        val states = HashMap<TerrainSubmissionSerial, TerrainSubmissionState>()

        override fun state(serial: TerrainSubmissionSerial): TerrainSubmissionState =
            states[serial] ?: TerrainSubmissionState.PENDING
    }

    private companion object {
        const val WORLD_EPOCH = 7L
        const val PROVIDER_GENERATION = 3L
        const val LAYOUT_GENERATION = 4L
        const val MATERIAL_GENERATION = 5L
        val TENANT = TerrainSchedulerTenantId("near:test")
        val MATERIAL = TerrainSemanticMaterialId("minosoft:terrain/opaque")
        val VERTICES = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val INDICES = byteArrayOf(0, 0, 1, 0, 0, 0)
    }
}
