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

import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainPrimitiveTopology
import de.bixilon.minosoft.terrain.runtime.TerrainSubmission
import java.util.LinkedHashMap

@JvmInline
value class TerrainViewKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Terrain view key must not be blank" }
    }
}

data class TerrainDrawCommand(
    val page: TerrainPageKey,
    val publicationId: Long,
    val partitionId: String,
    val vertexStrideBytes: Int,
    val indexElementBytes: Int,
    val topology: TerrainPrimitiveTopology,
    val vertexRange: TerrainBufferRange,
    val indexRange: TerrainBufferRange,
) {
    init {
        require(publicationId > 0L) { "Terrain draw publication ID must be positive" }
        require(partitionId.isNotBlank()) { "Terrain draw partition ID must not be blank" }
        require(vertexStrideBytes > 0 && vertexRange.length % vertexStrideBytes == 0) {
            "Terrain draw vertex range is not stride aligned"
        }
        require(indexElementBytes in setOf(1, 2, 4) && indexRange.length % indexElementBytes == 0) {
            "Terrain draw index range is not element aligned"
        }
        require(indexCount % topology.indicesPerPrimitive == 0) {
            "Terrain draw index range does not contain complete primitives"
        }
    }

    val vertexCount: Int get() = vertexRange.length / vertexStrideBytes
    val indexCount: Int get() = indexRange.length / indexElementBytes
}

class TerrainDrawBatch internal constructor(
    val region: TerrainRegionKey,
    val material: TerrainSemanticMaterialId,
    val view: TerrainViewKey,
    val layoutGeneration: Long,
    commands: Collection<TerrainDrawCommand>,
    val packet: TerrainDrawPacket,
    private val lease: TerrainRegionLease,
) : AutoCloseable {
    val commands: List<TerrainDrawCommand> = java.util.List.copyOf(commands)

    fun submit(submission: TerrainSubmission) = lease.submit(submission)

    override fun close() = lease.close()
}

/** Immutable physical command arrays compiled with a cached draw template. */
class TerrainDrawPacket(groups: Collection<TerrainDrawPacketGroup>) {
    val groups: List<TerrainDrawPacketGroup> = java.util.List.copyOf(groups)
    val totalIndices: Int = this.groups.fold(0) { total, group -> Math.addExact(total, group.totalIndices) }

    companion object {
        fun compile(commands: List<TerrainDrawCommand>): TerrainDrawPacket {
            val groups = commands.groupBy(TerrainDrawCommand::topology).map { (topology, commands) ->
                TerrainDrawPacketGroup(
                    topology = topology,
                    counts = IntArray(commands.size) { commands[it].indexCount },
                    indexByteOffsets = LongArray(commands.size) { commands[it].indexRange.offset.toLong() },
                    baseVertices = IntArray(commands.size) {
                        commands[it].vertexRange.offset / commands[it].vertexStrideBytes
                    },
                )
            }
            return TerrainDrawPacket(groups)
        }
    }
}

class TerrainDrawPacketGroup(
    val topology: TerrainPrimitiveTopology,
    counts: IntArray,
    indexByteOffsets: LongArray,
    baseVertices: IntArray,
) {
    private val counts: IntArray
    private val indexByteOffsets: LongArray
    private val baseVertices: IntArray
    val commandCount: Int get() = counts.size
    val totalIndices: Int

    fun indexCount(index: Int): Int = counts[index]
    fun indexByteOffset(index: Int): Long = indexByteOffsets[index]
    fun baseVertex(index: Int): Int = baseVertices[index]

    init {
        require(counts.isNotEmpty()) { "Terrain packet group must not be empty" }
        require(counts.size == indexByteOffsets.size && counts.size == baseVertices.size) {
            "Terrain packet arrays must have equal lengths"
        }
        require(counts.all { it >= 0 }) { "Terrain packet index counts must not be negative" }
        require(indexByteOffsets.all { it >= 0L }) { "Terrain packet offsets must not be negative" }
        require(baseVertices.all { it >= 0 }) { "Terrain packet base vertices must not be negative" }
        this.counts = counts.copyOf()
        this.indexByteOffsets = indexByteOffsets.copyOf()
        this.baseVertices = baseVertices.copyOf()
        this.totalIndices = counts.fold(0, Math::addExact)
    }
}

fun interface TerrainConventionalDrawBackend {
    fun draw(command: TerrainDrawCommand)
}

/** Capability-independent fallback used when a multi-draw route is unavailable. */
object TerrainConventionalDrawLoop {
    fun submit(batch: TerrainDrawBatch, backend: TerrainConventionalDrawBackend): Int {
        batch.commands.forEach(backend::draw)
        return batch.commands.size
    }
}

data class TerrainBatchCacheMetrics(
    val entries: Int,
    val builds: Long,
    val hits: Long,
    val evictions: Long,
)

/**
 * Caches immutable command templates. Every returned batch acquires a fresh
 * layout/range lease, so cached membership never pins retired GPU ranges.
 */
class TerrainBatchCache(private val maximumEntries: Int = 256) {
    private data class Key(
        val storage: TerrainRegionStorage,
        val material: TerrainSemanticMaterialId,
        val view: TerrainViewKey,
        val layoutGeneration: Long,
        val members: List<Pair<TerrainPageKey, Long>>,
    )

    private data class CachedBatch(
        val commands: List<TerrainDrawCommand>,
        val packet: TerrainDrawPacket,
    )

    private val cache = object : LinkedHashMap<Key, CachedBatch>(16, 0.75f, true) {}
    private var builds = 0L
    private var hits = 0L
    private var evictions = 0L

    init {
        require(maximumEntries > 0) { "Terrain batch cache capacity must be positive" }
    }

    @Synchronized
    fun batch(
        storage: TerrainRegionStorage,
        material: TerrainSemanticMaterialId,
        view: TerrainViewKey,
        orderedPages: Collection<TerrainPageKey>,
        layoutGeneration: Long,
        materialGeneration: Long,
    ): TerrainDrawBatch? {
        val lease = storage.acquire(orderedPages)
        val pages = try {
            lease.pages.filter {
                it.layoutGeneration == layoutGeneration && it.materialGeneration == materialGeneration
            }
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
        if (pages.isEmpty()) {
            lease.close()
            return null
        }
        val key = Key(
            storage,
            material,
            view,
            layoutGeneration,
            java.util.List.copyOf(pages.map { it.key to it.publicationId }),
        )
        val cached = try {
            cache[key]?.also {
                hits = saturatingIncrement(hits)
            } ?: pages.flatMap { page ->
                page.streams.asSequence()
                    .filter { it.material == material }
                    .map { stream ->
                        TerrainDrawCommand(
                            page = page.key,
                            publicationId = page.publicationId,
                            partitionId = stream.partitionId,
                            vertexStrideBytes = stream.vertexStrideBytes,
                            indexElementBytes = stream.indexElementBytes,
                            topology = stream.topology,
                            vertexRange = stream.vertexRange,
                            indexRange = stream.indexRange,
                        )
                    }
                    .toList()
            }.let { commands ->
                CachedBatch(java.util.List.copyOf(commands), TerrainDrawPacket.compile(commands))
            }.also { built ->
                if (key !in cache) {
                    cache[key] = built
                    builds = saturatingIncrement(builds)
                    while (cache.size > maximumEntries) {
                        val eldest = cache.entries.iterator()
                        eldest.next()
                        eldest.remove()
                        evictions = saturatingIncrement(evictions)
                    }
                }
            }
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
        if (cached.commands.isEmpty()) {
            lease.close()
            return null
        }
        return TerrainDrawBatch(
            region = storage.key,
            material = material,
            view = view,
            layoutGeneration = layoutGeneration,
            commands = cached.commands,
            packet = cached.packet,
            lease = lease,
        )
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun metrics() = TerrainBatchCacheMetrics(cache.size, builds, hits, evictions)

    private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L
}

enum class TerrainUploadUrgency {
    SAME_FRAME,
    NEXT_FRAME,
    DEFERRED,
}

data class TerrainUploadBudgetItem<T>(
    val value: T,
    val urgency: TerrainUploadUrgency,
    val sequence: Long,
    val estimatedCpuNanos: Long,
    val estimatedUploadBytes: Long,
) {
    init {
        require(sequence >= 0L) { "Terrain upload sequence must not be negative" }
        require(estimatedCpuNanos >= 0L) { "Terrain upload CPU estimate must not be negative" }
        require(estimatedUploadBytes >= 0L) { "Terrain upload byte estimate must not be negative" }
    }
}

class TerrainUploadBudgetSelection<T>(
    selected: Collection<TerrainUploadBudgetItem<T>>,
    deferred: Collection<TerrainUploadBudgetItem<T>>,
    val selectedCpuNanos: Long,
    val selectedUploadBytes: Long,
) {
    val selected: List<TerrainUploadBudgetItem<T>> = java.util.List.copyOf(selected)
    val deferred: List<TerrainUploadBudgetItem<T>> = java.util.List.copyOf(deferred)
}

object TerrainUploadBudgetScheduler {
    fun <T> select(
        items: Collection<TerrainUploadBudgetItem<T>>,
        cpuBudgetNanos: Long,
        uploadBudgetBytes: Long,
    ): TerrainUploadBudgetSelection<T> {
        require(cpuBudgetNanos >= 0L) { "Terrain upload CPU budget must not be negative" }
        require(uploadBudgetBytes >= 0L) { "Terrain upload byte budget must not be negative" }
        val selected = ArrayList<TerrainUploadBudgetItem<T>>()
        val deferred = ArrayList<TerrainUploadBudgetItem<T>>()
        var cpu = 0L
        var bytes = 0L
        for (item in items.sortedWith(compareBy<TerrainUploadBudgetItem<T>> { it.urgency.ordinal }.thenBy { it.sequence })) {
            val nextCpu = saturatingAdd(cpu, item.estimatedCpuNanos)
            val nextBytes = saturatingAdd(bytes, item.estimatedUploadBytes)
            val fits = nextCpu <= cpuBudgetNanos && nextBytes <= uploadBudgetBytes
            if (fits || item.urgency == TerrainUploadUrgency.SAME_FRAME && selected.isEmpty()) {
                selected += item
                cpu = nextCpu
                bytes = nextBytes
            } else {
                deferred += item
            }
        }
        return TerrainUploadBudgetSelection(selected, deferred, cpu, bytes)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
