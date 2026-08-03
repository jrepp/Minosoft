/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.local.LocalConnection
import de.bixilon.minosoft.local.generator.ChunkBuilder
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.DistantWorldVerticalSampler
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.types.fluid.FluidHolder
import de.bixilon.minosoft.terrain.distant.maximumContiguousDistantRadius

/** Lazily enumerates square rings without allocating a radius-sized queue. */
internal class DistantChunkSpiral {
    private var center = ChunkPosition()
    private var innerRadius = 0
    private var outerRadius = -1
    private var ring = 1
    private var index = 0

    fun reset(center: ChunkPosition, innerRadius: Int, outerRadius: Int) {
        require(innerRadius >= 0) { "Distant LOD inner radius must not be negative" }
        require(outerRadius >= innerRadius) { "Distant LOD outer radius must cover the inner radius" }
        this.center = center
        this.innerRadius = innerRadius
        this.outerRadius = outerRadius
        ring = innerRadius + 1
        index = 0
    }

    fun next(): ChunkPosition? {
        while (ring <= outerRadius) {
            val edge = ring * 2
            val perimeter = edge * 4
            if (index >= perimeter) {
                ring++
                index = 0
                continue
            }
            val offset = index++
            val relative = when {
                offset < edge -> ChunkPosition(-ring + offset, -ring)
                offset < edge * 2 -> ChunkPosition(ring, -ring + offset - edge)
                offset < edge * 3 -> ChunkPosition(ring - (offset - edge * 2), ring)
                else -> ChunkPosition(-ring, ring - (offset - edge * 3))
            }
            return ChunkPosition(center.x + relative.x, center.z + relative.z)
        }
        return null
    }
}

/**
 * Bounded local-authority generator. It never publishes a generated native
 * chunk; only a detached surface/material tile crosses into the LOD store.
 */
internal class DistantUnexploredGenerator(
    private val session: PlaySession,
    private val options: DistantHorizonsOptions,
    private val maximumResidentTiles: Int = options.maximumTiles,
    private val contains: (ChunkPosition) -> Boolean,
    private val publish: (DistantLodTile, DistantVerticalPage) -> Unit,
) : Runnable {
    private val connection = session.connection as? LocalConnection
    private val spiral = DistantChunkSpiral()
    private val generatedChunks = DistantGeneratedChunkCache<ChunkBuilder>()
    private var center: ChunkPosition? = null
    private var innerRadius = -1
    private var outerRadius = -1
    private var worldOwner: World? = null
    private var worldEpoch = -1L
    private var pending: ChunkPosition? = null

    override fun run() {
        val local = connection ?: return
        if (!options.enabled || !options.unexploredGenerationEnabled) return
        if (worldOwner !== session.world || worldEpoch != session.world.terrainEpoch) {
            worldOwner = session.world
            worldEpoch = session.world.terrainEpoch
            generatedChunks.clear()
            pending = null
            center = null
        }
        val nextCenter = session.player.physics.positionInfo.chunkPosition
        val nextInner = session.world.view.viewDistance + 1
        val nextOuter = minOf(
            options.generationRadiusChunks,
            options.renderDistanceChunks,
            maximumContiguousDistantRadius(maximumResidentTiles),
        )
        if (nextOuter <= nextInner) return
        if (nextCenter != center || nextInner != innerRadius || nextOuter != outerRadius) {
            center = nextCenter
            innerRadius = nextInner
            outerRadius = nextOuter
            spiral.reset(nextCenter, nextInner, nextOuter)
            pending = null
        }
        var generated = 0
        var published = 0
        while (published < options.generationBudgetPerTick) {
            val position = pending ?: run {
                var candidate = spiral.next() ?: return
                while (contains(candidate)) {
                    candidate = spiral.next() ?: return
                }
                pending = candidate
                candidate
            }
            val preparation = generatedChunks.prepare(
                center = position,
                maximumGenerated = options.generationBudgetPerTick - generated,
            ) { chunkPosition ->
                ChunkBuilder(session.world, chunkPosition).also(local.chunks.generator::generate)
            }
            generated = Math.addExact(generated, preparation.generatedCount)
            val neighbourhood = preparation.chunks ?: return
            val builder = checkNotNull(neighbourhood[position])
            publish(
                capture(builder),
                DistantWorldVerticalSampler.captureGenerated(builder, neighbourhood),
            )
            pending = null
            published++
        }
    }

    private fun capture(builder: ChunkBuilder): DistantLodTile {
        val dimension = builder.world.dimension
        return DistantLodTile.capture(builder.position) { x, z ->
            var surfaceY = dimension.maxY
            var material: ResourceLocation? = null
            while (surfaceY >= dimension.minY) {
                material = builder[x, surfaceY, z]?.block?.identifier
                if (material != null) break
                surfaceY--
            }
            if (material == null) {
                return@capture DistantLodColumn(Int.MIN_VALUE, null)
            }
            if (!builder[x, surfaceY, z].isDistantFluidState()) {
                return@capture DistantLodColumn(surfaceY, material)
            }
            var solidY = surfaceY - 1
            var solidMaterial: ResourceLocation? = null
            while (solidY >= dimension.minY) {
                val state = builder[x, solidY, z]
                val candidate = state?.block?.identifier
                if (candidate != null && !state.isDistantFluidState()) {
                    solidMaterial = candidate
                    break
                }
                solidY--
            }
            if (solidMaterial == null) solidY = Int.MIN_VALUE
            DistantLodColumn(surfaceY, material, solidY, solidMaterial)
        }
    }
}

/** Bounded LRU used to amortize the one-chunk light halo across adjacent pages. */
internal class DistantGeneratedChunkCache<T : Any>(
    private val maximumEntries: Int = MAXIMUM_ENTRIES,
) {
    data class Preparation<T>(
        val generatedCount: Int,
        val chunks: Map<ChunkPosition, T>?,
    )

    private val chunks = object : LinkedHashMap<ChunkPosition, T>(maximumEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkPosition, T>?): Boolean =
            size > maximumEntries
    }

    init {
        require(maximumEntries >= NEIGHBOURHOOD_SIZE) {
            "Generated chunk cache must retain at least one complete neighbourhood"
        }
    }

    fun prepare(
        center: ChunkPosition,
        maximumGenerated: Int,
        generate: (ChunkPosition) -> T,
    ): Preparation<T> {
        require(maximumGenerated >= 0) { "Generated chunk budget must not be negative" }
        val positions = neighbourhood(center)
        positions.forEach(chunks::get)
        var generated = 0
        for (position in positions) {
            if (position in chunks) continue
            if (generated >= maximumGenerated) return Preparation(generated, null)
            chunks[position] = generate(position)
            generated++
        }
        return Preparation(
            generated,
            java.util.Collections.unmodifiableMap(
                positions.associateWithTo(LinkedHashMap()) { checkNotNull(chunks[it]) },
            ),
        )
    }

    fun clear() = chunks.clear()

    private fun neighbourhood(center: ChunkPosition): List<ChunkPosition> = buildList(NEIGHBOURHOOD_SIZE) {
        add(center)
        for (z in -1..1) {
            for (x in -1..1) {
                if (x == 0 && z == 0) continue
                add(ChunkPosition(center.x + x, center.z + z))
            }
        }
    }

    private companion object {
        const val NEIGHBOURHOOD_SIZE = 9
        const val MAXIMUM_ENTRIES = 25
    }
}

private fun BlockState?.isDistantFluidState(): Boolean =
    this != null && (block is FluidHolder || BlockStateFlags.WATERLOGGED in flags)
