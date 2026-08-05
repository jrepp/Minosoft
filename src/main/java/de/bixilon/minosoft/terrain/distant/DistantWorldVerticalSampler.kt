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

package de.bixilon.minosoft.terrain.distant

import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.types.fluid.FluidBlock
import de.bixilon.minosoft.data.registries.blocks.types.fluid.FluidHolder
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.local.generator.ChunkBuilder
import de.bixilon.minosoft.terrain.distant.lighting.DistantDetachedLighting
import de.bixilon.minosoft.terrain.distant.lighting.DistantLightDirection
import de.bixilon.minosoft.terrain.distant.lighting.DistantLightVoxel
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantTintSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalSampler
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVoxelSample
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId

/** Minecraft-facing adapters for the single dependency-clean vertical sampler. */
object DistantWorldVerticalSampler {
    fun captureObserved(chunk: Chunk, sourceRevision: Long = 0L): DistantVerticalPage = chunk.lock.locked {
        captureObservedLocked(chunk, sourceRevision)
    }

    fun captureObservedLocked(chunk: Chunk, sourceRevision: Long = 0L): DistantVerticalPage {
        val world = chunk.world
        return capture(
            position = chunk.position,
            worldEpoch = world.terrainEpoch,
            minimumY = world.dimension.minY,
            maximumYExclusive = Math.addExact(world.dimension.maxY, 1),
            sourceRevision = sourceRevision,
            completeness = DistantSourceCompleteness.PARTIAL,
        ) { x, y, z ->
            val position = InChunkPosition(x, y, z)
            val light = chunk.light[position]
            val biome = world.biomes.accessor[chunk, position]?.identifier?.toString()
            normalize(chunk[position], light.block, light.sky, biome, generated = false)
        }
    }

    fun updateObservedLocked(
        chunk: Chunk,
        previous: DistantVerticalPage,
        columnIndices: Set<Int>,
        sourceRevision: Long = previous.sourceRevision,
    ): DistantVerticalPage {
        if (columnIndices.isEmpty()) return previous
        val world = chunk.world
        require(previous.key.x == chunk.position.x.toLong() && previous.key.z == chunk.position.z.toLong()) {
            "Distant observed page does not match its chunk"
        }
        require(previous.width == PAGE_WIDTH && previous.columns.size == PAGE_WIDTH * PAGE_WIDTH) {
            "Distant observed page has an incompatible column layout"
        }
        columnIndices.forEach { index ->
            require(index in 0 until PAGE_WIDTH * PAGE_WIDTH) { "Distant column index is out of bounds: $index" }
        }
        val columns = previous.columns.toMutableList()
        for (index in columnIndices) {
            val x = index and (PAGE_WIDTH - 1)
            val z = index / PAGE_WIDTH
            columns[index] = DistantVerticalSampler.captureColumn(
                x,
                z,
                world.dimension.minY,
                Math.addExact(world.dimension.maxY, 1),
            ) { sampleX, y, sampleZ ->
                val position = InChunkPosition(sampleX, y, sampleZ)
                val light = chunk.light[position]
                val biome = world.biomes.accessor[chunk, position]?.identifier?.toString()
                normalize(chunk[position], light.block, light.sky, biome, generated = false)
            }
        }
        return DistantVerticalPage(
            key = previous.key.copy(worldEpoch = world.terrainEpoch),
            width = previous.width,
            originY = previous.originY,
            sourceRevision = sourceRevision,
            completeness = previous.completeness,
            columns = columns,
        )
    }

    fun captureGenerated(
        builder: ChunkBuilder,
        neighbourhood: Map<ChunkPosition, ChunkBuilder>,
        sourceRevision: Long = 0L,
    ): DistantVerticalPage {
        val world = builder.world
        val minimumY = world.dimension.minY
        val maximumYExclusive = Math.addExact(world.dimension.maxY, 1)
        val expected = generatedNeighbourhood(builder.position)
        require(neighbourhood.keys == expected) {
            "Detached generated lighting requires the complete 3x3 chunk neighbourhood"
        }
        require(neighbourhood.values.all { it.world === world }) {
            "Detached generated lighting cannot cross world owners"
        }
        val light = DistantDetachedLighting.calculate(
            widthX = LIGHT_VOLUME_WIDTH,
            minimumY = minimumY,
            maximumYExclusive = maximumYExclusive,
            widthZ = LIGHT_VOLUME_WIDTH,
            hasSkyLight = world.dimension.light && world.dimension.skyLight,
        ) { volumeX, y, volumeZ ->
            val chunkX = volumeX / ChunkSize.SECTION_WIDTH_X
            val chunkZ = volumeZ / ChunkSize.SECTION_WIDTH_Z
            val chunkPosition = ChunkPosition(
                builder.position.x + chunkX - LIGHT_HALO_CHUNKS,
                builder.position.z + chunkZ - LIGHT_HALO_CHUNKS,
            )
            val source = checkNotNull(neighbourhood[chunkPosition])
            val state = source[
                volumeX % ChunkSize.SECTION_WIDTH_X,
                y,
                volumeZ % ChunkSize.SECTION_WIDTH_Z,
            ]
            state.detachedLightVoxel()
        }
        return capture(
            position = builder.position,
            worldEpoch = world.terrainEpoch,
            minimumY = minimumY,
            maximumYExclusive = maximumYExclusive,
            sourceRevision = sourceRevision,
            completeness = DistantSourceCompleteness.COMPLETE,
        ) { x, y, z ->
            val position = InChunkPosition(x, y, z)
            val biome = builder.biomes?.get(position)?.identifier?.toString()
            val sample = light[
                x + LIGHT_HALO_BLOCKS,
                y,
                z + LIGHT_HALO_BLOCKS,
            ]
            normalize(
                state = builder[x, y, z],
                blockLight = sample.block,
                skyLight = sample.sky,
                biomeInput = biome,
                generated = true,
            )
        }
    }

    private fun capture(
        position: ChunkPosition,
        worldEpoch: Long,
        minimumY: Int,
        maximumYExclusive: Int,
        sourceRevision: Long,
        completeness: DistantSourceCompleteness,
        sample: (x: Int, y: Int, z: Int) -> DistantVoxelSample,
    ): DistantVerticalPage = DistantVerticalSampler.capture(
        // The dependency-clean sampler retains one non-renderable exterior-light run above
        // occupied terrain so distant top, cliff, and elevated relief faces use adjacent light.
        key = TerrainPageKey(TerrainDomain.DISTANT, 0, position.x.toLong(), 0L, position.z.toLong(), worldEpoch),
        width = PAGE_WIDTH,
        minimumY = minimumY,
        maximumYExclusive = maximumYExclusive,
        sourceRevision = sourceRevision,
        completeness = completeness,
        accessor = sample::invoke,
    )

    internal fun normalize(
        state: BlockState?,
        blockLight: Int,
        skyLight: Int,
        biomeInput: String?,
        generated: Boolean,
    ): DistantVoxelSample {
        if (state == null || state.block.identifier.toString() in AIR_IDENTIFIERS) {
            return DistantVoxelSample(null, blockLight = blockLight, skyLight = skyLight, generated = generated)
        }
        val blockIdentifier = state.block.identifier.toString()
        val holderFluid = (state.block as? FluidHolder)?.fluid
        val fluidIdentifier = holderFluid?.identifier?.toString()
            ?: if (BlockStateFlags.WATERLOGGED in state.flags) WATER_IDENTIFIER else null
        val fluid = fluidIdentifier?.let {
            DistantFluidSample(
                material = TerrainSemanticMaterialId(it),
                level = state.getOrNull(FluidBlock.LEVEL) ?: 0,
                classification = it,
            )
        }
        // Preserve the normalized biome input for authority parity. Whether a
        // material consumes it is resolved by the render material generation.
        val tint = biomeInput?.let { DistantTintSample(resolvedRgb = null, biomeInput = it, generation = 0L) }
        return DistantVoxelSample(
            material = TerrainSemanticMaterialId(blockIdentifier),
            fluid = fluid,
            blockLight = blockLight,
            skyLight = skyLight,
            tint = tint,
            opaque = BlockStateFlags.FULL_OPAQUE in state.flags,
            emissive = state.luminance > 0,
            generated = generated,
            confidence = if (generated) GENERATED_CONFIDENCE else OBSERVED_CONFIDENCE,
        )
    }

    private const val PAGE_WIDTH = 16
    private const val LIGHT_HALO_CHUNKS = 1
    private const val LIGHT_HALO_BLOCKS = ChunkSize.SECTION_WIDTH_X
    private const val LIGHT_VOLUME_WIDTH = ChunkSize.SECTION_WIDTH_X * 3
    private const val WATER_IDENTIFIER = "minecraft:water"
    private const val OBSERVED_CONFIDENCE = 100
    private const val GENERATED_CONFIDENCE = 75
    private val AIR_IDENTIFIERS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

    private fun generatedNeighbourhood(center: ChunkPosition): Set<ChunkPosition> = buildSet {
        for (z in -LIGHT_HALO_CHUNKS..LIGHT_HALO_CHUNKS) {
            for (x in -LIGHT_HALO_CHUNKS..LIGHT_HALO_CHUNKS) {
                add(ChunkPosition(center.x + x, center.z + z))
            }
        }
    }

    private fun BlockState?.detachedLightVoxel(): DistantLightVoxel {
        val properties = this?.block?.getLightProperties(this)
        var propagationMask = 0
        if (properties == null) {
            propagationMask = DistantLightDirection.ALL_MASK
        } else {
            for (direction in Directions.VALUES) {
                if (!properties.propagatesLight(direction)) continue
                propagationMask = propagationMask or when (direction) {
                    Directions.DOWN -> DistantLightDirection.DOWN.mask
                    Directions.UP -> DistantLightDirection.UP.mask
                    Directions.NORTH -> DistantLightDirection.NORTH.mask
                    Directions.SOUTH -> DistantLightDirection.SOUTH.mask
                    Directions.WEST -> DistantLightDirection.WEST.mask
                    Directions.EAST -> DistantLightDirection.EAST.mask
                }
            }
        }
        return DistantLightVoxel(
            emission = this?.luminance ?: 0,
            propagationMask = propagationMask,
            skylightEnters = properties?.skylightEnters ?: true,
            filtersSkylight = properties?.filtersSkylight ?: false,
        )
    }
}
