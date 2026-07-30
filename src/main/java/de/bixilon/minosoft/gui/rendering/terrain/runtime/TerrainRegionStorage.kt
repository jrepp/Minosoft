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
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import java.util.TreeMap

data class TerrainRegionKey(val x: Int, val y: Int, val z: Int) {
    companion object {
        const val WIDTH = 8

        fun of(position: SectionPosition) = TerrainRegionKey(
            Math.floorDiv(position.x, WIDTH),
            Math.floorDiv(position.y, WIDTH),
            Math.floorDiv(position.z, WIDTH),
        )
    }
}

enum class TerrainFaceSegment {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST,
    UNASSIGNED,
    ;

    companion object {
        fun of(direction: Directions) = entries[direction.ordinal]
    }
}

class TerrainMaterialStream(
    val material: TerrainMaterialClass,
    val face: TerrainFaceSegment,
    vertices: ByteArray,
    indices: ByteArray = ByteArray(0),
) {
    val vertices = vertices.copyOf()
    val indices = indices.copyOf()
    val byteCount: Int = Math.addExact(this.vertices.size, this.indices.size)
}

data class TerrainBuildProduct(
    val position: SectionPosition,
    val modelRevision: Long,
    val backendGeneration: Long,
    val materialGeneration: String?,
    val connectivity: TerrainDirectionalVisibility?,
    val streams: List<TerrainMaterialStream>,
) {
    val byteCount: Long = streams.fold(0L) { total, stream -> Math.addExact(total, stream.byteCount.toLong()) }
}

data class TerrainRange(val offset: Int, val length: Int) {
    init {
        require(offset >= 0) { "Negative range offset" }
        require(length >= 0) { "Negative range length" }
        Math.addExact(offset, length)
    }
}

data class TerrainPublishedStream(
    val material: TerrainMaterialClass,
    val face: TerrainFaceSegment,
    val vertexRange: TerrainRange,
    val indexRange: TerrainRange?,
)

data class TerrainPublishedSection(
    val position: SectionPosition,
    val modelRevision: Long,
    val backendGeneration: Long,
    val materialGeneration: String?,
    val connectivity: TerrainDirectionalVisibility?,
    val streams: List<TerrainPublishedStream>,
)

/**
 * Deterministic bounded first-fit allocator. It intentionally does not compact;
 * allocation traces can justify that complexity later.
 */
class TerrainRangeAllocator(val capacity: Int) {
    private val free = TreeMap<Int, Int>().apply { put(0, capacity) }
    var allocatedBytes: Int = 0
        private set

    init {
        require(capacity > 0) { "Allocator capacity must be positive" }
    }

    fun allocate(length: Int, alignment: Int = 4): TerrainRange? {
        require(length >= 0) { "Negative allocation length" }
        require(alignment > 0 && alignment and (alignment - 1) == 0) {
            "Alignment must be a positive power of two"
        }
        if (length == 0) return TerrainRange(0, 0)

        for ((offset, available) in free) {
            val aligned = align(offset, alignment)
            val padding = aligned - offset
            val required = try {
                Math.addExact(padding, length)
            } catch (_: ArithmeticException) {
                continue
            }
            if (required > available) continue

            free.remove(offset)
            if (padding > 0) free[offset] = padding
            val suffix = available - required
            if (suffix > 0) free[Math.addExact(aligned, length)] = suffix
            allocatedBytes = Math.addExact(allocatedBytes, length)
            return TerrainRange(aligned, length)
        }
        return null
    }

    fun release(range: TerrainRange) {
        if (range.length == 0) return
        require(range.offset + range.length <= capacity) { "Range is outside allocator" }
        var offset = range.offset
        var length = range.length

        val lower = free.floorEntry(offset)
        if (lower != null) {
            require(lower.key + lower.value <= offset) { "Range overlaps free storage" }
            if (lower.key + lower.value == offset) {
                offset = lower.key
                length = Math.addExact(length, lower.value)
                free.remove(lower.key)
            }
        }
        val higher = free.ceilingEntry(offset)
        if (higher != null) {
            require(offset + length <= higher.key) { "Range overlaps free storage" }
            if (offset + length == higher.key) {
                length = Math.addExact(length, higher.value)
                free.remove(higher.key)
            }
        }
        free[offset] = length
        allocatedBytes = Math.subtractExact(allocatedBytes, range.length)
    }

    val largestFreeRange: Int get() = free.values.maxOrNull() ?: 0
    val freeBytes: Int get() = capacity - allocatedBytes
    val fragmentation: Double
        get() = if (freeBytes == 0) 0.0 else 1.0 - largestFreeRange.toDouble() / freeBytes

    private fun align(value: Int, alignment: Int): Int =
        Math.addExact(value, alignment - 1) and -alignment
}

interface TerrainStagingBuffer {
    val capacity: Int
    fun write(range: TerrainRange, source: ByteArray)
}

class ByteArrayTerrainStagingBuffer(override val capacity: Int) : TerrainStagingBuffer {
    private val data = ByteArray(capacity)

    override fun write(range: TerrainRange, source: ByteArray) {
        require(range.length == source.size) { "Source length does not match destination range" }
        require(range.offset + range.length <= capacity) { "Write exceeds staging capacity" }
        source.copyInto(data, range.offset)
    }

    fun read(range: TerrainRange) = data.copyOfRange(range.offset, range.offset + range.length)
}

data class TerrainUploadMetrics(
    val uploadedBytes: Long,
    val durationNanos: Long,
    val replacements: Long,
    val failures: Long,
    val allocatedBytes: Long,
    val fragmentation: Double,
)

/**
 * Region-owned storage with atomic section-table replacement.
 *
 * New ranges are allocated and completely staged before the table changes.
 * Any allocation or write failure releases only the candidate ranges and
 * leaves the previous section entry and bytes valid.
 */
class TerrainRegionStorage(
    val key: TerrainRegionKey,
    capacityBytes: Int,
    private val staging: TerrainStagingBuffer = ByteArrayTerrainStagingBuffer(capacityBytes),
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Candidate(
        val published: TerrainPublishedStream,
        val vertexBytes: ByteArray,
        val indexBytes: ByteArray,
    )

    private val allocator = TerrainRangeAllocator(capacityBytes)
    private val sections = LinkedHashMap<SectionPosition, TerrainPublishedSection>()
    private var uploadedBytes = 0L
    private var uploadDurationNanos = 0L
    private var replacements = 0L
    private var failures = 0L
    var generation: Long = 0L
        private set

    init {
        require(staging.capacity >= capacityBytes) { "Staging buffer is smaller than region storage" }
    }

    fun get(position: SectionPosition): TerrainPublishedSection? = sections[position]

    fun snapshot(): Map<SectionPosition, TerrainPublishedSection> = LinkedHashMap(sections)

    @Synchronized
    fun publish(product: TerrainBuildProduct): TerrainPublishedSection? {
        require(TerrainRegionKey.of(product.position) == key) { "Product belongs to another region" }
        val start = clock()
        val candidates = ArrayList<Candidate>(product.streams.size)
        val allocated = ArrayList<TerrainRange>(product.streams.size * 2)

        try {
            for (stream in product.streams) {
                val vertex = allocator.allocate(stream.vertices.size) ?: failAllocation()
                allocated += vertex
                val index = if (stream.indices.isNotEmpty()) {
                    (allocator.allocate(stream.indices.size) ?: failAllocation()).also { allocated += it }
                } else {
                    null
                }
                candidates += Candidate(
                    TerrainPublishedStream(stream.material, stream.face, vertex, index),
                    stream.vertices,
                    stream.indices,
                )
            }

            for (candidate in candidates) {
                staging.write(candidate.published.vertexRange, candidate.vertexBytes)
                candidate.published.indexRange?.let { staging.write(it, candidate.indexBytes) }
            }

            val replacement = TerrainPublishedSection(
                product.position,
                product.modelRevision,
                product.backendGeneration,
                product.materialGeneration,
                product.connectivity,
                candidates.map { it.published },
            )
            val previous = sections.put(product.position, replacement)
            generation++
            replacements++
            uploadedBytes = Math.addExact(uploadedBytes, product.byteCount)
            previous?.streams?.forEach(::release)
            return replacement
        } catch (failure: Throwable) {
            allocated.asReversed().forEach(allocator::release)
            failures++
            if (failure is TerrainAllocationFailure) return null
            throw failure
        } finally {
            uploadDurationNanos = Math.addExact(uploadDurationNanos, clock() - start)
        }
    }

    @Synchronized
    fun remove(position: SectionPosition): Boolean {
        val removed = sections.remove(position) ?: return false
        removed.streams.forEach(::release)
        generation++
        return true
    }

    fun metrics() = TerrainUploadMetrics(
        uploadedBytes,
        uploadDurationNanos,
        replacements,
        failures,
        allocator.allocatedBytes.toLong(),
        allocator.fragmentation,
    )

    private fun release(stream: TerrainPublishedStream) {
        stream.indexRange?.let(allocator::release)
        allocator.release(stream.vertexRange)
    }

    private fun failAllocation(): Nothing = throw TerrainAllocationFailure()

    private class TerrainAllocationFailure : RuntimeException()
}
