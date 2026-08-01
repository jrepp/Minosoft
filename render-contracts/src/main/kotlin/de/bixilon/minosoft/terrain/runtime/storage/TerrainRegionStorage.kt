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

package de.bixilon.minosoft.terrain.runtime.storage

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactDigest
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.model.mesh.TerrainPrimitiveTopology
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainSubmission
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionCompletion
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionState
import java.util.TreeMap

data class TerrainRegionKey(
    val domain: TerrainDomain,
    val detailLevel: Int,
    val x: Long,
    val y: Long,
    val z: Long,
    val worldEpoch: Long,
) {
    init {
        require(detailLevel >= 0) { "Terrain region detail level must not be negative" }
        require(worldEpoch >= 0L) { "Terrain region world epoch must not be negative" }
    }

    companion object {
        fun containing(page: TerrainPageKey, width: Int): TerrainRegionKey =
            containing(page, TerrainRegionExtent(width, width, width))

        fun containing(page: TerrainPageKey, extent: TerrainRegionExtent): TerrainRegionKey {
            return TerrainRegionKey(
                domain = page.domain,
                detailLevel = page.detailLevel,
                x = Math.floorDiv(page.x, extent.x.toLong()),
                y = Math.floorDiv(page.y, extent.y.toLong()),
                z = Math.floorDiv(page.z, extent.z.toLong()),
                worldEpoch = page.worldEpoch,
            )
        }
    }
}

data class TerrainRegionExtent(val x: Int, val y: Int, val z: Int) {
    init {
        require(x > 0 && y > 0 && z > 0) { "Terrain region extents must be positive" }
    }
}

data class TerrainBufferRange(val offset: Int, val length: Int) {
    init {
        require(offset >= 0) { "Terrain buffer offset must not be negative" }
        require(length >= 0) { "Terrain buffer length must not be negative" }
        Math.addExact(offset, length)
    }
}

enum class TerrainBufferArena {
    VERTEX,
    INDEX,
}

/** An immutable upload operation; source ownership never crosses the render-thread plan. */
class TerrainUploadOperation(
    val arena: TerrainBufferArena,
    val range: TerrainBufferRange,
    source: ByteArray,
) {
    private val source = validateSource(range, source).copyOf()

    fun copyBytes(): ByteArray = source.copyOf()

    private companion object {
        fun validateSource(range: TerrainBufferRange, source: ByteArray): ByteArray {
            require(range.length == source.size) { "Terrain upload source and range sizes differ" }
            return source
        }
    }
}

class TerrainUploadPlan(
    val region: TerrainRegionKey,
    val layoutGeneration: Long,
    operations: Collection<TerrainUploadOperation>,
    val uploadBytes: Long,
) {
    val operations: List<TerrainUploadOperation> = java.util.List.copyOf(operations)

    init {
        require(layoutGeneration >= 0L) { "Terrain layout generation must not be negative" }
        require(uploadBytes >= 0L) { "Terrain upload bytes must not be negative" }
        val actual = this.operations.fold(0L) { total, operation ->
            Math.addExact(total, operation.range.length.toLong())
        }
        require(actual == uploadBytes) { "Terrain upload byte estimate is not exact" }
    }
}

/**
 * Device boundary for ordinary buffer updates or persistent-mapped staging.
 * Implementations must return only after every operation has been accepted.
 * Candidate ranges are not visible through the page table until this succeeds.
 */
interface TerrainRegionUploadDevice {
    val deviceRuntime: TerrainDeviceRuntimeId
    val layoutGeneration: Long
    val vertexCapacityBytes: Int
    val indexCapacityBytes: Int

    fun upload(plan: TerrainUploadPlan)
}

data class TerrainPublishedStream(
    val partitionId: String,
    val material: TerrainSemanticMaterialId,
    val vertexStrideBytes: Int,
    val indexElementBytes: Int,
    val topology: TerrainPrimitiveTopology,
    val vertexRange: TerrainBufferRange,
    val indexRange: TerrainBufferRange,
) {
    init {
        require(partitionId.isNotBlank()) { "Terrain published partition ID must not be blank" }
        require(vertexStrideBytes > 0 && vertexRange.length % vertexStrideBytes == 0) {
            "Terrain published vertex range is not stride aligned"
        }
        require(indexElementBytes in setOf(1, 2, 4) && indexRange.length % indexElementBytes == 0) {
            "Terrain published index range is not element aligned"
        }
        require(indexCount % topology.indicesPerPrimitive == 0) {
            "Terrain published index range does not contain complete primitives"
        }
    }

    val vertexCount: Int get() = vertexRange.length / vertexStrideBytes
    val indexCount: Int get() = indexRange.length / indexElementBytes
}

class TerrainPublishedPage(
    val publicationId: Long,
    val key: TerrainPageKey,
    val layoutGeneration: Long,
    val materialGeneration: Long,
    val connectivityBits: Long,
    val digest: TerrainArtifactDigest,
    streams: Collection<TerrainPublishedStream>,
) {
    val streams: List<TerrainPublishedStream> = java.util.List.copyOf(streams)
    val residentBytes: Long = this.streams.fold(0L) { total, stream ->
        Math.addExact(total, Math.addExact(stream.vertexRange.length, stream.indexRange.length).toLong())
    }

    init {
        require(publicationId > 0L) { "Terrain publication ID must be positive" }
        require(layoutGeneration >= 0L) { "Terrain publication layout generation must not be negative" }
        require(materialGeneration >= 0L) { "Terrain publication material generation must not be negative" }
        require(this.streams.map(TerrainPublishedStream::partitionId).toSet().size == this.streams.size) {
            "Terrain publication contains duplicate stream partitions"
        }
    }
}

data class TerrainRegionStorageMetrics(
    val publicationGeneration: Long,
    val activePages: Int,
    val retiredPages: Int,
    val residentBytes: Long,
    val retiredBytes: Long,
    val vertexAllocatedBytes: Int,
    val indexAllocatedBytes: Int,
    val vertexHighWaterBytes: Int,
    val indexHighWaterBytes: Int,
    val vertexFragmentation: Double,
    val indexFragmentation: Double,
    val uploadedBytes: Long,
    val uploadNanos: Long,
    val publications: Long,
    val allocationFailures: Long,
    val uploadFailures: Long,
    val cpuLeaseCount: Int,
    val pendingSubmissionCount: Int,
    val failedSubmissionCount: Int,
    val invalidatedSubmissionCount: Int,
    val retiredCpuLeasedPages: Int,
    val retiredPendingSubmissionPages: Int,
    val retiredFailedSubmissionPages: Int,
    val retiredDeviceInvalidatedPages: Int,
    val deviceInvalidations: Long,
)

/**
 * Bounded region storage with transactional publication and device-complete
 * retirement. The caller owns the artifact and may close it after [publish].
 */
class TerrainRegionStorage(
    val key: TerrainRegionKey,
    val extent: TerrainRegionExtent,
    private val device: TerrainRegionUploadDevice,
    private val completion: TerrainSubmissionCompletion,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Allocation(
        val page: TerrainPublishedPage,
        val vertexRanges: List<TerrainBufferRange>,
        val indexRanges: List<TerrainBufferRange>,
    ) {
        var cpuLeases = 0
        val pendingSubmissions = linkedSetOf<TerrainSubmissionSerial>()
        val failedSubmissions = linkedSetOf<TerrainSubmissionSerial>()
        val invalidatedSubmissions = linkedSetOf<TerrainSubmissionSerial>()
    }

    private val vertexAllocator = TerrainRangeAllocator(device.vertexCapacityBytes)
    private val indexAllocator = TerrainRangeAllocator(device.indexCapacityBytes)
    private val active = LinkedHashMap<TerrainPageKey, Allocation>()
    private val retired = ArrayList<Allocation>()
    private var nextPublicationId = 1L
    private var publicationGeneration = 0L
    private var uploadedBytes = 0L
    private var uploadNanos = 0L
    private var publications = 0L
    private var allocationFailures = 0L
    private var uploadFailures = 0L
    private var deviceInvalidations = 0L

    init {
        require(device.deviceRuntime == completion.deviceRuntime) {
            "Terrain upload and completion devices differ"
        }
        require(device.vertexCapacityBytes > 0) { "Terrain vertex capacity must be positive" }
        require(device.indexCapacityBytes > 0) { "Terrain index capacity must be positive" }
    }

    constructor(
        key: TerrainRegionKey,
        regionWidth: Int,
        device: TerrainRegionUploadDevice,
        completion: TerrainSubmissionCompletion,
        clock: () -> Long = System::nanoTime,
    ) : this(key, TerrainRegionExtent(regionWidth, regionWidth, regionWidth), device, completion, clock)

    @Synchronized
    fun get(page: TerrainPageKey): TerrainPublishedPage? = active[page]?.page

    @Synchronized
    fun snapshot(): Map<TerrainPageKey, TerrainPublishedPage> =
        java.util.Collections.unmodifiableMap(active.mapValuesTo(LinkedHashMap()) { it.value.page })

    /** Exact non-mutating allocator admission used before selecting a region shard. */
    @Synchronized
    fun canPublish(artifact: TerrainMeshArtifact): Boolean {
        require(TerrainRegionKey.containing(artifact.identity.page, extent) == key) {
            "Terrain artifact belongs to another region"
        }
        require(artifact.identity.layoutGeneration == device.layoutGeneration) {
            "Terrain artifact layout generation does not match the device"
        }
        check(!artifact.isClosed) { "Terrain artifact is closed" }
        val vertexPreview = vertexAllocator.copyState()
        val indexPreview = indexAllocator.copyState()
        for (stream in artifact.streams) {
            if (vertexPreview.allocate(stream.vertices.size, stream.vertexStrideBytes) == null) return false
            if (indexPreview.allocate(stream.indices.size, stream.indexElementBytes) == null) return false
        }
        return true
    }

    @Synchronized
    fun publish(artifact: TerrainMeshArtifact): TerrainPublishedPage? {
        require(TerrainRegionKey.containing(artifact.identity.page, extent) == key) {
            "Terrain artifact belongs to another region"
        }
        require(artifact.identity.layoutGeneration == device.layoutGeneration) {
            "Terrain artifact layout generation does not match the device"
        }
        check(!artifact.isClosed) { "Terrain artifact is closed" }

        val start = clock()
        val vertexRanges = ArrayList<TerrainBufferRange>(artifact.streams.size)
        val indexRanges = ArrayList<TerrainBufferRange>(artifact.streams.size)
        try {
            val streams = ArrayList<TerrainPublishedStream>(artifact.streams.size)
            val operations = ArrayList<TerrainUploadOperation>(artifact.streams.size * 2)
            for (stream in artifact.streams) {
                val vertexRange = vertexAllocator.allocate(stream.vertices.size, stream.vertexStrideBytes)
                    ?: return allocationFailed(vertexRanges, indexRanges)
                vertexRanges += vertexRange
                val indexRange = indexAllocator.allocate(stream.indices.size, stream.indexElementBytes)
                    ?: return allocationFailed(vertexRanges, indexRanges)
                indexRanges += indexRange
                operations += TerrainUploadOperation(
                    TerrainBufferArena.VERTEX,
                    vertexRange,
                    stream.vertices.copyBytes(),
                )
                operations += TerrainUploadOperation(
                    TerrainBufferArena.INDEX,
                    indexRange,
                    stream.indices.copyBytes(),
                )
                streams += TerrainPublishedStream(
                    partitionId = stream.partitionId,
                    material = stream.material,
                    vertexStrideBytes = stream.vertexStrideBytes,
                    indexElementBytes = stream.indexElementBytes,
                    topology = stream.topology,
                    vertexRange = vertexRange,
                    indexRange = indexRange,
                )
            }

            val plan = TerrainUploadPlan(key, device.layoutGeneration, operations, artifact.byteCount)
            val publicationId = nextPublicationId
            val nextPublicationId = Math.addExact(publicationId, 1L)
            val nextPublicationGeneration = Math.addExact(publicationGeneration, 1L)
            val nextPublications = Math.addExact(publications, 1L)
            val nextUploadedBytes = Math.addExact(uploadedBytes, artifact.byteCount)
            val page = TerrainPublishedPage(
                publicationId = publicationId,
                key = artifact.identity.page,
                layoutGeneration = artifact.identity.layoutGeneration,
                materialGeneration = artifact.identity.materialGeneration,
                connectivityBits = artifact.connectivityBits,
                digest = artifact.digest,
                streams = java.util.List.copyOf(streams),
            )
            val allocation = Allocation(
                page,
                java.util.List.copyOf(vertexRanges),
                java.util.List.copyOf(indexRanges),
            )
            try {
                device.upload(plan)
            } catch (failure: Throwable) {
                releaseCandidate(vertexRanges, indexRanges)
                uploadFailures = saturatingIncrement(uploadFailures)
                throw failure
            }

            this.nextPublicationId = nextPublicationId
            active.put(page.key, allocation)?.let(retired::add)
            publicationGeneration = nextPublicationGeneration
            publications = nextPublications
            uploadedBytes = nextUploadedBytes
            collectRetiredLocked(emptyMap())
            return page
        } finally {
            val elapsed = try {
                Math.subtractExact(clock(), start).coerceAtLeast(0L)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            uploadNanos = saturatingAdd(uploadNanos, elapsed)
        }
    }

    @Synchronized
    fun remove(page: TerrainPageKey): Boolean {
        val allocation = active[page] ?: return false
        val nextPublicationGeneration = Math.addExact(publicationGeneration, 1L)
        active.remove(page)
        retired += allocation
        publicationGeneration = nextPublicationGeneration
        collectRetiredLocked(emptyMap())
        return true
    }

    @Synchronized
    fun acquire(pages: Collection<TerrainPageKey>): TerrainRegionLease {
        val allocations = pages.distinct().mapNotNull(active::get)
        val nextLeaseCounts = allocations.map { Math.addExact(it.cpuLeases, 1) }
        allocations.forEachIndexed { index, allocation -> allocation.cpuLeases = nextLeaseCounts[index] }
        return TerrainRegionLease(this, allocations, allocations.map { it.page })
    }

    fun collectRetired(): Int {
        val pending = synchronized(this) {
            (active.values + retired).flatMapTo(linkedSetOf()) { it.pendingSubmissions }
        }
        val states = pending.associateWithTo(linkedMapOf()) {
            completion.state(it)
        }
        return synchronized(this) {
            (active.values + retired).forEach { collectSubmissionStatesLocked(it, states) }
            collectRetiredLocked(emptyMap())
        }
    }

    /** Context loss discards every logical device allocation and page entry at once. */
    @Synchronized
    fun invalidateDevice() {
        check(active.values.none { it.cpuLeases != 0 } && retired.none { it.cpuLeases != 0 }) {
            "Cannot invalidate terrain storage while CPU leases are active"
        }
        val nextPublicationGeneration = Math.addExact(publicationGeneration, 1L)
        active.clear()
        retired.clear()
        vertexAllocator.reset()
        indexAllocator.reset()
        publicationGeneration = nextPublicationGeneration
        deviceInvalidations = saturatingIncrement(deviceInvalidations)
    }

    @Synchronized
    fun metrics(): TerrainRegionStorageMetrics {
        val activeBytes = active.values.fold(0L) { total, allocation ->
            Math.addExact(total, allocation.page.residentBytes)
        }
        val retiredBytes = retired.fold(0L) { total, allocation ->
            Math.addExact(total, allocation.page.residentBytes)
        }
        val allocations = active.values + retired
        return TerrainRegionStorageMetrics(
            publicationGeneration = publicationGeneration,
            activePages = active.size,
            retiredPages = retired.size,
            residentBytes = activeBytes,
            retiredBytes = retiredBytes,
            vertexAllocatedBytes = vertexAllocator.allocatedBytes,
            indexAllocatedBytes = indexAllocator.allocatedBytes,
            vertexHighWaterBytes = vertexAllocator.highWaterBytes,
            indexHighWaterBytes = indexAllocator.highWaterBytes,
            vertexFragmentation = vertexAllocator.fragmentation,
            indexFragmentation = indexAllocator.fragmentation,
            uploadedBytes = uploadedBytes,
            uploadNanos = uploadNanos,
            publications = publications,
            allocationFailures = allocationFailures,
            uploadFailures = uploadFailures,
            cpuLeaseCount = checkedCount(allocations.map { it.cpuLeases }),
            pendingSubmissionCount = checkedCount(allocations.map { it.pendingSubmissions.size }),
            failedSubmissionCount = checkedCount(allocations.map { it.failedSubmissions.size }),
            invalidatedSubmissionCount = checkedCount(allocations.map { it.invalidatedSubmissions.size }),
            retiredCpuLeasedPages = retired.count { it.cpuLeases != 0 },
            retiredPendingSubmissionPages = retired.count { it.pendingSubmissions.isNotEmpty() },
            retiredFailedSubmissionPages = retired.count { it.failedSubmissions.isNotEmpty() },
            retiredDeviceInvalidatedPages = retired.count { it.invalidatedSubmissions.isNotEmpty() },
            deviceInvalidations = deviceInvalidations,
        )
    }

    @Synchronized
    internal fun submit(allocations: List<Any>, submission: TerrainSubmission) {
        require(submission.deviceRuntime == device.deviceRuntime) { "Terrain submission uses another device" }
        @Suppress("UNCHECKED_CAST")
        (allocations as List<Allocation>).forEach { allocation ->
            check(submission.serial !in allocation.failedSubmissions) {
                "Terrain submission serial was already recorded as failed"
            }
            check(submission.serial !in allocation.invalidatedSubmissions) {
                "Terrain submission serial belongs to an invalidated device"
            }
            allocation.pendingSubmissions += submission.serial
        }
    }

    @Synchronized
    internal fun releaseLease(allocations: List<Any>) {
        @Suppress("UNCHECKED_CAST")
        (allocations as List<Allocation>).forEach {
            check(it.cpuLeases > 0) { "Terrain region lease released too many times" }
            it.cpuLeases--
        }
        collectRetiredLocked(emptyMap())
    }

    private fun allocationFailed(
        vertexRanges: List<TerrainBufferRange>,
        indexRanges: List<TerrainBufferRange>,
    ): Nothing? {
        releaseCandidate(vertexRanges, indexRanges)
        allocationFailures = saturatingIncrement(allocationFailures)
        return null
    }

    private fun releaseCandidate(
        vertexRanges: List<TerrainBufferRange>,
        indexRanges: List<TerrainBufferRange>,
    ) {
        indexRanges.asReversed().forEach(indexAllocator::release)
        vertexRanges.asReversed().forEach(vertexAllocator::release)
    }

    private fun collectRetiredLocked(states: Map<TerrainSubmissionSerial, TerrainSubmissionState>): Int {
        var released = 0
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val allocation = iterator.next()
            collectSubmissionStatesLocked(allocation, states)
            if (
                allocation.cpuLeases != 0 ||
                allocation.pendingSubmissions.isNotEmpty() ||
                allocation.failedSubmissions.isNotEmpty() ||
                allocation.invalidatedSubmissions.isNotEmpty()
            ) continue
            releaseCandidate(allocation.vertexRanges, allocation.indexRanges)
            iterator.remove()
            released++
        }
        return released
    }

    private fun collectSubmissionStatesLocked(
        allocation: Allocation,
        states: Map<TerrainSubmissionSerial, TerrainSubmissionState>,
    ) {
        val submissions = allocation.pendingSubmissions.iterator()
        while (submissions.hasNext()) {
            val serial = submissions.next()
            when (states[serial]) {
                TerrainSubmissionState.COMPLETE -> submissions.remove()
                TerrainSubmissionState.FAILED -> {
                    submissions.remove()
                    allocation.failedSubmissions += serial
                }
                TerrainSubmissionState.DEVICE_INVALIDATED -> {
                    submissions.remove()
                    allocation.invalidatedSubmissions += serial
                }
                TerrainSubmissionState.PENDING,
                null -> Unit
            }
        }
    }

    private fun saturatingIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) value else value + 1L

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun checkedCount(counts: Collection<Int>): Int {
        var total = 0
        for (count in counts) total = Math.addExact(total, count)
        return total
    }
}

class TerrainRegionLease internal constructor(
    private val owner: TerrainRegionStorage,
    private val allocations: List<Any>,
    pages: List<TerrainPublishedPage>,
) : AutoCloseable {
    val pages: List<TerrainPublishedPage> = java.util.List.copyOf(pages)
    private var closed = false

    @Synchronized
    fun submit(submission: TerrainSubmission) {
        check(!closed) { "Terrain region lease is closed" }
        owner.submit(allocations, submission)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        owner.releaseLease(allocations)
    }
}

class TerrainRangeAllocator(val capacity: Int) {
    private val free = TreeMap<Int, Int>().apply { put(0, capacity) }
    var allocatedBytes: Int = 0
        private set
    var highWaterBytes: Int = 0
        private set

    init {
        require(capacity > 0) { "Terrain allocator capacity must be positive" }
    }

    fun allocate(length: Int, alignment: Int): TerrainBufferRange? {
        require(length >= 0) { "Terrain allocation length must not be negative" }
        require(alignment > 0) { "Terrain alignment must be positive" }
        if (length == 0) return TerrainBufferRange(0, 0)

        var selectedOffset: Int? = null
        var selectedAvailable = 0
        var selectedAligned = 0
        for ((offset, available) in free) {
            val aligned = try {
                Math.multiplyExact(
                    Math.floorDiv(Math.addExact(offset, alignment - 1), alignment),
                    alignment,
                )
            } catch (_: ArithmeticException) {
                continue
            }
            val required = try {
                Math.addExact(aligned - offset, length)
            } catch (_: ArithmeticException) {
                continue
            }
            if (required <= available) {
                selectedOffset = offset
                selectedAvailable = available
                selectedAligned = aligned
                break
            }
        }
        val offset = selectedOffset ?: return null
        free.remove(offset)
        val padding = selectedAligned - offset
        if (padding > 0) free[offset] = padding
        val consumed = Math.addExact(padding, length)
        val suffix = selectedAvailable - consumed
        if (suffix > 0) free[Math.addExact(selectedAligned, length)] = suffix
        allocatedBytes = Math.addExact(allocatedBytes, length)
        highWaterBytes = maxOf(highWaterBytes, allocatedBytes)
        return TerrainBufferRange(selectedAligned, length)
    }

    fun release(range: TerrainBufferRange) {
        if (range.length == 0) return
        require(range.offset + range.length <= capacity) { "Terrain range is outside the allocator" }
        var offset = range.offset
        var length = range.length

        free.floorEntry(offset)?.let { lower ->
            require(lower.key + lower.value <= offset) { "Terrain range overlaps free storage" }
            if (lower.key + lower.value == offset) {
                offset = lower.key
                length = Math.addExact(length, lower.value)
                free.remove(lower.key)
            }
        }
        free.ceilingEntry(offset)?.let { higher ->
            require(offset + length <= higher.key) { "Terrain range overlaps free storage" }
            if (offset + length == higher.key) {
                length = Math.addExact(length, higher.value)
                free.remove(higher.key)
            }
        }
        free[offset] = length
        allocatedBytes = Math.subtractExact(allocatedBytes, range.length)
    }

    fun reset() {
        free.clear()
        free[0] = capacity
        allocatedBytes = 0
    }

    internal fun copyState(): TerrainRangeAllocator {
        val copy = TerrainRangeAllocator(capacity)
        copy.free.clear()
        copy.free.putAll(free)
        copy.allocatedBytes = allocatedBytes
        copy.highWaterBytes = highWaterBytes
        return copy
    }

    val freeBytes: Int get() = capacity - allocatedBytes
    val largestFreeRange: Int get() = free.values.maxOrNull() ?: 0
    val fragmentation: Double
        get() = if (freeBytes == 0) 0.0 else 1.0 - largestFreeRange.toDouble() / freeBytes
}
