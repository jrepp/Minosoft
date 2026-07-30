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
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition

data class TerrainBlockEntityMetadata(
    val position: InSectionPosition,
    val blockIdentifier: String,
)

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
    states: Array<BlockState?>,
    light: ByteArray,
    biomes: Array<Biome?>,
    opaque: BooleanArray,
    entities: List<TerrainBlockEntityMetadata>,
) {
    private val width = ChunkSize.SECTION_LENGTH + halo * 2
    private val states = states.copyOf()
    private val light = light.copyOf()
    private val biomes = biomes.copyOf()
    private val opaque = opaque.copyOf()
    val entities = entities.toList()

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

    companion object {
        private const val MAX_CAPTURE_ATTEMPTS = 3

        fun capture(section: ChunkSection, halo: Int = 1): TerrainBuildSnapshot {
            require(halo in 1 until ChunkSize.SECTION_LENGTH) {
                "Terrain snapshot halo must be between 1 and ${ChunkSize.SECTION_LENGTH - 1}"
            }
            repeat(MAX_CAPTURE_ATTEMPTS) {
                captureOnce(section, halo)?.let { return it }
            }
            throw IllegalStateException("Terrain model changed repeatedly during snapshot capture")
        }

        private fun captureOnce(section: ChunkSection, halo: Int): TerrainBuildSnapshot? {
            val chunks = neighbourChunks(section)
            chunks.forEach { it.lock.lock() }
            try {
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
                                light[index] = targetSection?.light?.get(inChunk.inSectionPosition)?.raw ?: 0
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
                section.entities.forEach { position, entity ->
                    entities += TerrainBlockEntityMetadata(position, entity.state.block.identifier.toString())
                }
                return TerrainBuildSnapshot(
                    SectionPosition.of(section),
                    halo,
                    before[section] ?: section.terrainRevision.get(),
                    fingerprint(before),
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

        private fun neighbourChunks(section: ChunkSection): List<Chunk> {
            section.chunk.lock.lock()
            return try {
                (listOf(section.chunk) + section.chunk.neighbours.array.filterNotNull())
                    .distinctBy { it.position }
                    .sortedWith(compareBy<Chunk> { it.position.x }.thenBy { it.position.z })
            } finally {
                section.chunk.lock.unlock()
            }
        }

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
