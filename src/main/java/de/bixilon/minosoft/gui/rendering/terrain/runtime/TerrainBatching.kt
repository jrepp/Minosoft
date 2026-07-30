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

import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass

data class TerrainDrawCommand(
    val section: SectionPosition,
    val vertexOffset: Int,
    val vertexBytes: Int,
    val indexOffset: Int?,
    val indexBytes: Int,
)

data class TerrainDrawBatch(
    val region: TerrainRegionKey,
    val material: TerrainMaterialClass,
    val commands: List<TerrainDrawCommand>,
    val storageGeneration: Long,
    val terrainGeneration: Long,
    val materialGeneration: String?,
)

class TerrainBatchCache {
    private data class Key(
        val region: TerrainRegionKey,
        val material: TerrainMaterialClass,
        val visible: List<SectionPosition>,
        val storageGeneration: Long,
        val terrainGeneration: Long,
        val materialGeneration: String?,
    )

    private val cache = HashMap<Key, TerrainDrawBatch>()
    var builds: Long = 0L
        private set
    var hits: Long = 0L
        private set

    fun batch(
        storage: TerrainRegionStorage,
        material: TerrainMaterialClass,
        visible: List<SectionPosition>,
        terrainGeneration: Long,
        materialGeneration: String?,
    ): TerrainDrawBatch {
        val members = visible.filter { TerrainRegionKey.of(it) == storage.key }
        val key = Key(
            storage.key,
            material,
            members,
            storage.generation,
            terrainGeneration,
            materialGeneration,
        )
        cache[key]?.let {
            hits++
            return it
        }

        val commands = ArrayList<TerrainDrawCommand>()
        for (position in members) {
            val section = storage.get(position) ?: continue
            if (section.backendGeneration != terrainGeneration ||
                section.materialGeneration != materialGeneration
            ) {
                continue
            }
            for (stream in section.streams) {
                if (stream.material != material) continue
                commands += TerrainDrawCommand(
                    position,
                    stream.vertexRange.offset,
                    stream.vertexRange.length,
                    stream.indexRange?.offset,
                    stream.indexRange?.length ?: 0,
                )
            }
        }

        return TerrainDrawBatch(
            storage.key,
            material,
            commands,
            storage.generation,
            terrainGeneration,
            materialGeneration,
        ).also {
            cache[key] = it
            builds++
            if (cache.size > MAX_ENTRIES) {
                val iterator = cache.keys.iterator()
                repeat(cache.size - MAX_ENTRIES) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    fun clear() = cache.clear()

    companion object {
        private const val MAX_ENTRIES = 256
    }
}

enum class TerrainUrgency {
    SAME_FRAME,
    NEXT_FRAME,
    DEFERRED,
}

data class TerrainBudgetItem<T>(
    val value: T,
    val urgency: TerrainUrgency,
    val sequence: Long,
    val estimatedCpuNanos: Long,
    val estimatedUploadBytes: Long,
)

data class TerrainBudgetSelection<T>(
    val selected: List<TerrainBudgetItem<T>>,
    val deferred: List<TerrainBudgetItem<T>>,
    val cpuNanos: Long,
    val uploadBytes: Long,
)

object TerrainBudgetScheduler {
    fun <T> select(
        items: Collection<TerrainBudgetItem<T>>,
        cpuBudgetNanos: Long,
        uploadBudgetBytes: Long,
    ): TerrainBudgetSelection<T> {
        require(cpuBudgetNanos >= 0L && uploadBudgetBytes >= 0L)
        val ordered = items.sortedWith(
            compareBy<TerrainBudgetItem<T>> { it.urgency.ordinal }.thenBy { it.sequence },
        )
        val selected = ArrayList<TerrainBudgetItem<T>>()
        val deferred = ArrayList<TerrainBudgetItem<T>>()
        var cpu = 0L
        var upload = 0L

        for (item in ordered) {
            require(item.estimatedCpuNanos >= 0L && item.estimatedUploadBytes >= 0L)
            val nextCpu = saturatingAdd(cpu, item.estimatedCpuNanos)
            val nextUpload = saturatingAdd(upload, item.estimatedUploadBytes)
            val fits = nextCpu <= cpuBudgetNanos && nextUpload <= uploadBudgetBytes
            if (fits || item.urgency == TerrainUrgency.SAME_FRAME && selected.isEmpty()) {
                selected += item
                cpu = nextCpu
                upload = nextUpload
            } else {
                deferred += item
            }
        }
        return TerrainBudgetSelection(selected, deferred, cpu, upload)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

data class TerrainRuntimeMetrics(
    val publishedSections: Int,
    val visibleSections: Int,
    val drawBatches: Int,
    val drawCommands: Int,
    val uploadBytes: Long,
    val uploadNanos: Long,
    val allocationFailures: Long,
    val allocatedBytes: Long,
    val fragmentation: Double,
    val batchBuilds: Long,
    val batchHits: Long,
)

/**
 * Headless reference orchestration for the optimized terrain data path.
 *
 * Render backends may execute [TerrainDrawBatch] through multi-draw or the
 * deterministic per-command fallback; storage and publication semantics stay
 * identical.
 */
class TerrainRuntimePipeline(
    private val regionCapacityBytes: Int,
    private val storageFactory: (TerrainRegionKey, Int) -> TerrainRegionStorage =
        { key, capacity -> TerrainRegionStorage(key, capacity) },
) {
    private val regions = LinkedHashMap<TerrainRegionKey, TerrainRegionStorage>()
    private val batches = TerrainBatchCache()
    private var lastVisible = 0
    private var lastBatches = 0
    private var lastCommands = 0

    fun publish(product: TerrainBuildProduct): Boolean {
        val key = TerrainRegionKey.of(product.position)
        val storage = regions.getOrPut(key) { storageFactory(key, regionCapacityBytes) }
        return storage.publish(product) != null
    }

    fun remove(position: SectionPosition): Boolean {
        val key = TerrainRegionKey.of(position)
        val storage = regions[key] ?: return false
        val removed = storage.remove(position)
        if (storage.snapshot().isEmpty()) regions.remove(key)
        return removed
    }

    fun buildBatches(
        camera: SectionPosition,
        candidates: Set<SectionPosition>,
        material: TerrainMaterialClass,
        terrainGeneration: Long,
        materialGeneration: String?,
    ): List<TerrainDrawBatch> {
        val nodes = LinkedHashMap<SectionPosition, TerrainVisibilityNode>()
        for (position in candidates) {
            val section = regions[TerrainRegionKey.of(position)]?.get(position) ?: continue
            if (section.backendGeneration != terrainGeneration ||
                section.materialGeneration != materialGeneration
            ) {
                continue
            }
            nodes[position] = TerrainVisibilityNode(position, section.connectivity)
        }
        val traversed = TerrainVisibilityTraversal.traverse(camera, nodes)
        val visible = TerrainVisibilityTraversal.ordered(
            camera,
            traversed,
            translucent = material == TerrainMaterialClass.TRANSLUCENT,
        )
        val result = regions.values.mapNotNull { storage ->
            batches.batch(storage, material, visible, terrainGeneration, materialGeneration)
                .takeIf { it.commands.isNotEmpty() }
        }
        lastVisible = visible.size
        lastBatches = result.size
        lastCommands = result.sumOf { it.commands.size }
        return result
    }

    fun metrics(): TerrainRuntimeMetrics {
        val upload = regions.values.map { it.metrics() }
        val allocated = upload.sumOf { it.allocatedBytes }
        val weightedFragmentation = if (allocated == 0L) {
            0.0
        } else {
            upload.sumOf { it.fragmentation * it.allocatedBytes } / allocated
        }
        return TerrainRuntimeMetrics(
            publishedSections = regions.values.sumOf { it.snapshot().size },
            visibleSections = lastVisible,
            drawBatches = lastBatches,
            drawCommands = lastCommands,
            uploadBytes = upload.sumOf { it.uploadedBytes },
            uploadNanos = upload.sumOf { it.durationNanos },
            allocationFailures = upload.sumOf { it.failures },
            allocatedBytes = allocated,
            fragmentation = weightedFragmentation,
            batchBuilds = batches.builds,
            batchHits = batches.hits,
        )
    }
}
