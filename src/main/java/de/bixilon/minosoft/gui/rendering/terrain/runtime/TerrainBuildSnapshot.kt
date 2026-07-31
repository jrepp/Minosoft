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

import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.types.entity.BlockWithEntity
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.biome.source.BiomeSourceFlags
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.Collections

data class TerrainBlockEntityMetadata(
    val position: InSectionPosition,
    val blockIdentifier: String,
    val nbt: Map<String, Any>,
)

class TerrainSnapshotCaptureException(message: String) : IllegalStateException(message)

/**
 * Detached, bounded and version-normalized input captured under a stable chunk
 * lock order. Host registry objects referenced here are immutable model inputs;
 * mutable providers, chunks, sections, palettes and block entities are not
 * retained.
 */
class TerrainBuildSnapshot private constructor(
    val position: SectionPosition,
    val halo: Int,
    val centerRevision: Long,
    val modelRevision: Long,
    val completeHorizontalNeighbours: Boolean,
    val horizontalBiomeSampling: Boolean,
    val verticalBiomeSampling: Boolean,
    states: Array<BlockState?>,
    light: ByteArray,
    biomes: Array<Biome?>,
    opaque: BooleanArray,
    entities: List<TerrainBlockEntityMetadata>,
) {
    private val width = ChunkSize.SECTION_LENGTH + halo * 2
    private val origin = BlockPosition.of(position)
    // The constructor is private and receives fresh capture-owned arrays.
    private val states = states
    private val light = light
    private val biomes = biomes
    private val opaque = opaque
    val entities: List<TerrainBlockEntityMetadata> = java.util.List.copyOf(entities)
    private val entitiesByPosition = this.entities.associateBy { it.position }
    val blockCount = opaque.indices.count { states[centerIndex(InSectionPosition(it))] != null }
    val fluidCount = opaque.indices.count {
        states[centerIndex(InSectionPosition(it))]?.flags?.contains(BlockStateFlags.FLUID) == true
    }
    val isEmpty: Boolean get() = blockCount == 0 && entities.isEmpty()

    init {
        val volume = Math.multiplyExact(Math.multiplyExact(width, width), width)
        require(this.states.size == volume)
        require(this.light.size == volume)
        require(this.biomes.size == volume)
        require(this.opaque.size == ChunkSize.BLOCKS_PER_SECTION)
    }

    fun state(x: Int, y: Int, z: Int): BlockState? = states[index(x, y, z)]

    fun light(x: Int, y: Int, z: Int): Int = light[index(x, y, z)].toInt() and 0xFF

    fun biome(x: Int, y: Int, z: Int): Biome? = biomes[index(x, y, z)]

    fun isOpaque(position: InSectionPosition): Boolean = opaque[position.index]

    fun isOpaque(x: Int, y: Int, z: Int): Boolean =
        stateOrNull(x, y, z)?.flags?.contains(BlockStateFlags.FULL_OPAQUE) == true

    fun hasBlockEntity(position: InSectionPosition): Boolean = position in entitiesByPosition

    fun blockEntityMetadata(position: InSectionPosition): TerrainBlockEntityMetadata? = entitiesByPosition[position]

    /** Creates a candidate-local entity from detached state for entity-dependent block models. */
    fun createBlockEntity(session: PlaySession, position: InSectionPosition): de.bixilon.minosoft.data.entities.block.BlockEntity? {
        val metadata = entitiesByPosition[position] ?: return null
        val state = state(position.x, position.y, position.z) ?: return null
        if (state.block.identifier.toString() != metadata.blockIdentifier) return null
        val block = state.block as? BlockWithEntity<*> ?: return null
        val entity = block.createBlockEntity(session, origin + position, state) ?: return null
        entity.updateNBT(metadata.nbt)
        return entity
    }

    fun state(position: BlockPosition): BlockState? {
        return stateOrNull(position.x - origin.x, position.y - origin.y, position.z - origin.z)
    }

    fun light(position: BlockPosition): Int {
        return lightOrZero(position.x - origin.x, position.y - origin.y, position.z - origin.z)
    }

    fun biome(position: BlockPosition): Biome? {
        return biomeOrNull(position.x - origin.x, position.y - origin.y, position.z - origin.z)
    }

    fun stateOrNull(x: Int, y: Int, z: Int): BlockState? =
        indexOrNull(x, y, z)?.let(states::get)

    fun lightOrZero(x: Int, y: Int, z: Int): Int =
        indexOrNull(x, y, z)?.let { light[it].toInt() and 0xFF } ?: 0

    fun lightOrNull(position: BlockPosition): Int? {
        return indexOrNull(position.x - origin.x, position.y - origin.y, position.z - origin.z)
            ?.let { light[it].toInt() and 0xFF }
    }

    fun biomeOrNull(x: Int, y: Int, z: Int): Biome? =
        indexOrNull(x, y, z)?.let(biomes::get)

    fun connectivity(): TerrainDirectionalVisibility = TerrainConnectivityBuilder.build(opaque)

    private fun index(x: Int, y: Int, z: Int): Int {
        require(x in -halo until ChunkSize.SECTION_LENGTH + halo)
        require(y in -halo until ChunkSize.SECTION_LENGTH + halo)
        require(z in -halo until ChunkSize.SECTION_LENGTH + halo)
        val translatedX = x + halo
        val translatedY = y + halo
        val translatedZ = z + halo
        return (translatedY * width + translatedZ) * width + translatedX
    }

    private fun indexOrNull(x: Int, y: Int, z: Int): Int? {
        if (x !in -halo until ChunkSize.SECTION_LENGTH + halo) return null
        if (y !in -halo until ChunkSize.SECTION_LENGTH + halo) return null
        if (z !in -halo until ChunkSize.SECTION_LENGTH + halo) return null
        return index(x, y, z)
    }

    private fun centerIndex(position: InSectionPosition): Int = index(position.x, position.y, position.z)

    companion object {
        private const val MAX_CAPTURE_ATTEMPTS = 3

        fun capture(section: ChunkSection, halo: Int = 1): TerrainBuildSnapshot {
            require(halo in 1..ChunkSize.SECTION_LENGTH) {
                "Terrain snapshot halo must be between 1 and ${ChunkSize.SECTION_LENGTH}"
            }
            repeat(MAX_CAPTURE_ATTEMPTS) {
                captureOnce(section, halo)?.let { return it }
            }
            throw TerrainSnapshotCaptureException("Terrain model changed repeatedly during snapshot capture")
        }

        private fun captureOnce(section: ChunkSection, halo: Int): TerrainBuildSnapshot? {
            val chunks = neighbourChunks(section)
            chunks.forEach { it.lock.lock() }
            try {
                val activeChunks = currentNeighbourChunks(section)
                if (activeChunks.size != chunks.size || activeChunks.indices.any { activeChunks[it] !== chunks[it] }) {
                    return null
                }
                val sections = relevantSections(section, chunks, halo)
                val before = sections.associateWith { it.terrainRevision.get() }
                val width = ChunkSize.SECTION_LENGTH + halo * 2
                val volume = Math.multiplyExact(Math.multiplyExact(width, width), width)
                val states = arrayOfNulls<BlockState>(volume)
                val light = ByteArray(volume)
                val biomes = arrayOfNulls<Biome>(volume)
                val chunksByPosition = chunks.associateBy { it.position }
                val accessor = section.chunk.world.biomes.accessor

                var index = 0
                for (y in -halo until ChunkSize.SECTION_LENGTH + halo) {
                    for (z in -halo until ChunkSize.SECTION_LENGTH + halo) {
                        for (x in -halo until ChunkSize.SECTION_LENGTH + halo) {
                            val chunkX = Math.floorDiv(x, ChunkSize.SECTION_LENGTH)
                            val chunkZ = Math.floorDiv(z, ChunkSize.SECTION_LENGTH)
                            val targetChunk = chunksByPosition[
                                ChunkPosition(
                                    section.chunk.position.x + chunkX,
                                    section.chunk.position.z + chunkZ,
                                ),
                            ]
                            if (targetChunk != null) {
                                val absoluteY = section.height * ChunkSize.SECTION_LENGTH + y
                                val inChunk = InChunkPosition(
                                    Math.floorMod(x, ChunkSize.SECTION_LENGTH),
                                    absoluteY,
                                    Math.floorMod(z, ChunkSize.SECTION_LENGTH),
                                )
                                val targetSection = targetChunk[inChunk.sectionHeight]
                                states[index] = targetSection?.blocks?.get(inChunk.inSectionPosition)
                                light[index] = targetChunk.light[inChunk].raw
                                biomes[index] = accessor[targetChunk, inChunk]
                            }
                            index++
                        }
                    }
                }

                val after = sections.associateWith { it.terrainRevision.get() }
                if (before != after) return null

                val opaque = BooleanArray(ChunkSize.BLOCKS_PER_SECTION)
                for (cell in opaque.indices) {
                    val position = InSectionPosition(cell)
                    val state = states[
                        ((position.y + halo) * width + position.z + halo) * width + position.x + halo
                    ]
                    opaque[cell] = state != null && BlockStateFlags.FULL_OPAQUE in state.flags
                }
                val entities = ArrayList<TerrainBlockEntityMetadata>(section.entities.count)
                for (cell in 0 until ChunkSize.BLOCKS_PER_SECTION) {
                    val position = InSectionPosition(cell)
                    val entity = section.entities[position] ?: continue
                    entities += TerrainBlockEntityMetadata(
                        position,
                        entity.state.block.identifier.toString(),
                        detachedNbt(entity.toNbt()),
                    )
                }
                return TerrainBuildSnapshot(
                    SectionPosition.of(section),
                    halo,
                    before[section] ?: section.terrainRevision.get(),
                    fingerprint(before),
                    section.chunk.neighbours.complete,
                    section.chunk.biomeSource?.flags?.contains(BiomeSourceFlags.HORIZONTAL) == true,
                    section.chunk.biomeSource?.flags?.contains(BiomeSourceFlags.VERTICAL) == true,
                    states,
                    light,
                    biomes,
                    opaque,
                    entities,
                )
            } finally {
                chunks.asReversed().forEach { it.lock.unlock() }
            }
        }

        private fun detachedNbt(source: Map<String, Any>): Map<String, Any> {
            val copy = LinkedHashMap<String, Any>(source.size)
            for ((key, value) in source) copy[key] = detachedValue(value)
            return Collections.unmodifiableMap(copy)
        }

        private fun detachedValue(value: Any): Any = when (value) {
            is Map<*, *> -> {
                val copy = LinkedHashMap<String, Any>(value.size)
                for ((key, entry) in value) {
                    if (entry != null) copy[key.toString()] = detachedValue(entry)
                }
                Collections.unmodifiableMap(copy)
            }
            is List<*> -> Collections.unmodifiableList(value.mapNotNull { it?.let(::detachedValue) })
            is ByteArray -> value.copyOf()
            is ShortArray -> value.copyOf()
            is IntArray -> value.copyOf()
            is LongArray -> value.copyOf()
            is FloatArray -> value.copyOf()
            is DoubleArray -> value.copyOf()
            is BooleanArray -> value.copyOf()
            is CharArray -> value.copyOf()
            is Array<*> -> Collections.unmodifiableList(value.mapNotNull { it?.let(::detachedValue) })
            else -> value
        }

        private fun neighbourChunks(section: ChunkSection): List<Chunk> {
            section.chunk.lock.lock()
            return try {
                currentNeighbourChunks(section)
            } finally {
                section.chunk.lock.unlock()
            }
        }

        private fun currentNeighbourChunks(section: ChunkSection): List<Chunk> =
            (listOf(section.chunk) + section.chunk.neighbours.array.filterNotNull())
                .distinctBy { it.position }
                .sortedWith(compareBy<Chunk> { it.position.x }.thenBy { it.position.z })

        private fun relevantSections(
            center: ChunkSection,
            chunks: List<Chunk>,
            halo: Int,
        ): List<ChunkSection> {
            val minHeight = Math.floorDiv(
                center.height * ChunkSize.SECTION_LENGTH - halo,
                ChunkSize.SECTION_LENGTH,
            )
            val maxHeight = Math.floorDiv(
                center.height * ChunkSize.SECTION_LENGTH + ChunkSize.SECTION_MAX_Y + halo,
                ChunkSize.SECTION_LENGTH,
            )
            return chunks.flatMap { chunk ->
                (minHeight..maxHeight).mapNotNull(chunk::get)
            }.sortedWith(
                compareBy<ChunkSection> { it.chunk.position.x }
                    .thenBy { it.chunk.position.z }
                    .thenBy { it.height },
            )
        }

        private fun fingerprint(revisions: Map<ChunkSection, Long>): Long {
            var hash = -0x340d631b7bdddcdbL
            for ((section, revision) in revisions.entries.sortedWith(
                compareBy<Map.Entry<ChunkSection, Long>> { it.key.chunk.position.x }
                    .thenBy { it.key.chunk.position.z }
                    .thenBy { it.key.height },
            )) {
                hash = (hash xor SectionPosition.of(section).raw) * 0x100000001b3L
                hash = (hash xor revision) * 0x100000001b3L
            }
            return hash
        }
    }
}
