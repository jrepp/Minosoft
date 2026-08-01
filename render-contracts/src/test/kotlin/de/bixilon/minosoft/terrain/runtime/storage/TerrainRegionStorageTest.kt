/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.storage

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
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerrainRegionStorageTest {
    private val deviceId = TerrainDeviceRuntimeId(TerrainProcessScopeId(1L), 2L)
    private val material = TerrainSemanticMaterialId("minosoft:opaque")

    @Test
    fun `allocator supports physical strides that are not powers of two`() {
        val allocator = TerrainRangeAllocator(64)
        val first = assertNotNull(allocator.allocate(5, 1))
        val second = assertNotNull(allocator.allocate(12, 6))

        assertEquals(0, first.offset)
        assertEquals(6, second.offset)
        assertEquals(0, second.offset % 6)
        allocator.release(first)
        allocator.release(second)
        assertEquals(0, allocator.allocatedBytes)
        assertEquals(64, allocator.largestFreeRange)
    }

    @Test
    fun `failed allocation and upload preserve the active page`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        val first = artifact(revision = 1L, vertexBytes = 8, indexBytes = 6)
        val published = first.use { assertNotNull(storage.publish(it)) }

        device.failure = IllegalStateException("injected upload failure")
        artifact(revision = 2L, vertexBytes = 8, indexBytes = 6).use {
            assertThrows<IllegalStateException> { storage.publish(it) }
        }
        assertSame(published, storage.get(page()))
        assertEquals(8, storage.metrics().vertexAllocatedBytes)
        assertEquals(6, storage.metrics().indexAllocatedBytes)
        assertEquals(1L, storage.metrics().uploadFailures)

        artifact(revision = 3L, vertexBytes = 12, indexBytes = 6).use {
            assertNull(storage.publish(it))
        }
        assertSame(published, storage.get(page()))
        assertEquals(1L, storage.metrics().allocationFailures)
        assertEquals(8, storage.metrics().vertexAllocatedBytes)
        assertEquals(6, storage.metrics().indexAllocatedBytes)
    }

    @Test
    fun `allocator admission predicts fit without mutating storage or failure telemetry`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L, vertexBytes = 8, indexBytes = 6).use {
            assertTrue(storage.canPublish(it))
            assertNotNull(storage.publish(it))
        }
        artifact(revision = 2L, vertexBytes = 8, indexBytes = 6).use {
            assertTrue(storage.canPublish(it))
        }
        artifact(revision = 3L, vertexBytes = 12, indexBytes = 6).use {
            assertFalse(storage.canPublish(it))
        }

        val metrics = storage.metrics()
        assertEquals(8, metrics.vertexAllocatedBytes)
        assertEquals(6, metrics.indexAllocatedBytes)
        assertEquals(0L, metrics.allocationFailures)
        assertEquals(1L, metrics.publications)
    }

    @Test
    fun `retired ranges wait for every device submission and cpu lease`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { assertNotNull(storage.publish(it)) }

        val lease = storage.acquire(listOf(page()))
        lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
        lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(2L)))
        artifact(revision = 2L).use { assertNotNull(storage.publish(it)) }
        assertEquals(1, storage.metrics().retiredPages)
        assertEquals(16, storage.metrics().vertexAllocatedBytes)
        assertEquals(1, storage.metrics().cpuLeaseCount)
        assertEquals(1, storage.metrics().retiredCpuLeasedPages)
        assertEquals(2, storage.metrics().pendingSubmissionCount)

        completion.states[TerrainSubmissionSerial(2L)] = TerrainSubmissionState.COMPLETE
        lease.close()
        assertEquals(0, storage.collectRetired())
        assertEquals(1, storage.metrics().retiredPages)
        assertEquals(1, storage.metrics().pendingSubmissionCount)
        assertEquals(1, storage.metrics().retiredPendingSubmissionPages)
        assertEquals(0, storage.metrics().retiredCpuLeasedPages)

        completion.states[TerrainSubmissionSerial(1L)] = TerrainSubmissionState.COMPLETE
        assertEquals(1, storage.collectRetired())
        assertEquals(0, storage.metrics().retiredPages)
        assertEquals(0, storage.metrics().pendingSubmissionCount)
        assertEquals(8, storage.metrics().vertexAllocatedBytes)

        artifact(revision = 3L).use { assertNotNull(storage.publish(it)) }
        assertEquals(8, storage.metrics().vertexAllocatedBytes)
        assertEquals(6, storage.metrics().indexAllocatedBytes)
    }

    @Test
    fun `active pages discard completed historical submission references`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { assertNotNull(storage.publish(it)) }
        for (value in 1L..3L) {
            storage.acquire(listOf(page())).use { lease ->
                lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(value)))
            }
        }
        assertEquals(3, storage.metrics().pendingSubmissionCount)

        completion.states[TerrainSubmissionSerial(1L)] = TerrainSubmissionState.COMPLETE
        completion.states[TerrainSubmissionSerial(2L)] = TerrainSubmissionState.COMPLETE
        assertEquals(0, storage.collectRetired())
        assertEquals(1, storage.metrics().activePages)
        assertEquals(1, storage.metrics().pendingSubmissionCount)

        completion.states[TerrainSubmissionSerial(3L)] = TerrainSubmissionState.COMPLETE
        assertEquals(0, storage.collectRetired())
        assertEquals(0, storage.metrics().pendingSubmissionCount)
    }

    @Test
    fun `failed submission never makes a retired range reusable`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { storage.publish(it) }
        storage.acquire(listOf(page())).use { lease ->
            lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
        }
        artifact(revision = 2L).use { storage.publish(it) }
        completion.states[TerrainSubmissionSerial(1L)] = TerrainSubmissionState.FAILED

        assertEquals(0, storage.collectRetired())
        val failed = storage.metrics()
        assertEquals(1, failed.retiredPages)
        assertEquals(0, failed.pendingSubmissionCount)
        assertEquals(1, failed.failedSubmissionCount)
        assertEquals(0, failed.invalidatedSubmissionCount)
        assertEquals(0, failed.retiredPendingSubmissionPages)
        assertEquals(1, failed.retiredFailedSubmissionPages)
        artifact(revision = 3L).use { assertNull(storage.publish(it)) }
    }

    @Test
    fun `device-invalidated submissions remain diagnosed until context recovery`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { storage.publish(it) }
        storage.acquire(listOf(page())).use { lease ->
            lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
        }
        artifact(revision = 2L).use { storage.publish(it) }
        completion.states[TerrainSubmissionSerial(1L)] = TerrainSubmissionState.DEVICE_INVALIDATED

        assertEquals(0, storage.collectRetired())
        val invalidated = storage.metrics()
        assertEquals(0, invalidated.pendingSubmissionCount)
        assertEquals(0, invalidated.failedSubmissionCount)
        assertEquals(1, invalidated.invalidatedSubmissionCount)
        assertEquals(1, invalidated.retiredDeviceInvalidatedPages)
        artifact(revision = 3L).use { assertNull(storage.publish(it)) }

        storage.invalidateDevice()
        val recovered = storage.metrics()
        assertEquals(0, recovered.activePages)
        assertEquals(0, recovered.retiredPages)
        assertEquals(0, recovered.invalidatedSubmissionCount)
        assertEquals(0, recovered.retiredDeviceInvalidatedPages)
        assertEquals(1L, recovered.deviceInvalidations)
    }

    @Test
    fun `submission completion is polled outside the storage monitor`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { storage.publish(it) }
        storage.acquire(listOf(page())).use { lease ->
            lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
        }
        artifact(revision = 2L).use { storage.publish(it) }
        completion.stateObserver = { assertFalse(Thread.holdsLock(storage)) }

        storage.collectRetired()
    }

    @Test
    fun `closed lease rejects a late submission`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 12)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { storage.publish(it) }
        val lease = storage.acquire(listOf(page()))
        lease.close()

        assertThrows<IllegalStateException> {
            lease.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
        }
    }

    @Test
    fun `upload plan uses separate exact vertex and index operations`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 32, indexCapacityBytes = 16)
        val storage = storage(device, completion)
        artifact(revision = 1L, vertexBytes = 12, indexBytes = 6).use { storage.publish(it) }

        val plan = assertNotNull(device.lastPlan)
        assertEquals(18L, plan.uploadBytes)
        assertEquals(listOf(TerrainBufferArena.VERTEX, TerrainBufferArena.INDEX), plan.operations.map { it.arena })
        assertContentEquals(ByteArray(12) { it.toByte() }, plan.operations[0].copyBytes())
        assertContentEquals(ByteArray(6) { (it + 32).toByte() }, plan.operations[1].copyBytes())
    }

    @Test
    fun `layout mismatch is rejected before device mutation`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 8, layoutGeneration = 4L)
        val storage = storage(device, completion)

        artifact(revision = 1L, layoutGeneration = 3L).use {
            assertThrows<IllegalArgumentException> { storage.publish(it) }
        }
        assertNull(device.lastPlan)
        assertTrue(storage.snapshot().isEmpty())
    }

    @Test
    fun `device invalidation returns logical resources to baseline`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId, vertexCapacityBytes = 16, indexCapacityBytes = 8)
        val storage = storage(device, completion)
        artifact(revision = 1L).use { storage.publish(it) }
        val lease = storage.acquire(listOf(page()))
        assertEquals(1, storage.metrics().cpuLeaseCount)

        assertThrows<IllegalStateException> { storage.invalidateDevice() }
        lease.close()
        storage.invalidateDevice()

        val metrics = storage.metrics()
        assertEquals(0, metrics.activePages)
        assertEquals(0, metrics.retiredPages)
        assertEquals(0, metrics.vertexAllocatedBytes)
        assertEquals(0, metrics.indexAllocatedBytes)
        assertEquals(0, metrics.cpuLeaseCount)
        assertEquals(0, metrics.pendingSubmissionCount)
        assertEquals(0, metrics.failedSubmissionCount)
        assertEquals(0, metrics.invalidatedSubmissionCount)
        assertEquals(1L, metrics.deviceInvalidations)
    }

    private fun storage(device: Device, completion: Completion) = TerrainRegionStorage(
        key = TerrainRegionKey.containing(page(), 8),
        regionWidth = 8,
        device = device,
        completion = completion,
    )

    private fun page(x: Long = 0L) = TerrainPageKey(TerrainDomain.NEAR, 0, x, 0L, 0L, 7L)

    private fun artifact(
        revision: Long,
        page: TerrainPageKey = page(),
        vertexBytes: Int = 8,
        indexBytes: Int = 6,
        layoutGeneration: Long = 4L,
    ) = TerrainMeshArtifact(
        identity = TerrainBuildIdentity(
            page = page,
            requestRevision = revision,
            capturedModelRevision = revision,
            providerGeneration = 1L,
            layoutGeneration = layoutGeneration,
            materialGeneration = 5L,
            coverageGeneration = 1L,
            prioritySequence = revision,
        ),
        streams = listOf(
            TerrainArtifactStream(
                partitionId = "opaque:unassigned",
                material = material,
                vertexStrideBytes = 4,
                vertexBytes = ByteArray(vertexBytes) { it.toByte() },
                indexElementBytes = 2,
                indexBytes = ByteArray(indexBytes) { (it + 32).toByte() },
            ),
        ),
        bounds = null,
        connectivityBits = 0L,
    )

    private class Completion(override val deviceRuntime: TerrainDeviceRuntimeId) : TerrainSubmissionCompletion {
        val states = HashMap<TerrainSubmissionSerial, TerrainSubmissionState>()
        var stateObserver: (() -> Unit)? = null

        override fun state(serial: TerrainSubmissionSerial): TerrainSubmissionState {
            stateObserver?.invoke()
            return states[serial] ?: TerrainSubmissionState.PENDING
        }
    }

    private class Device(
        override val deviceRuntime: TerrainDeviceRuntimeId,
        override val vertexCapacityBytes: Int,
        override val indexCapacityBytes: Int,
        override val layoutGeneration: Long = 4L,
    ) : TerrainRegionUploadDevice {
        var failure: RuntimeException? = null
        var lastPlan: TerrainUploadPlan? = null

        override fun upload(plan: TerrainUploadPlan) {
            failure?.also {
                failure = null
                throw it
            }
            lastPlan = plan
        }
    }
}
