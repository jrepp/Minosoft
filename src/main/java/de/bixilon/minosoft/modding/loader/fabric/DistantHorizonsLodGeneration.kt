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
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.local.LocalConnection
import de.bixilon.minosoft.local.generator.ChunkBuilder
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

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
    private val contains: (ChunkPosition) -> Boolean,
    private val publish: (DistantLodTile) -> Unit,
) : Runnable {
    private val connection = session.connection as? LocalConnection
    private val spiral = DistantChunkSpiral()
    private var center: ChunkPosition? = null
    private var innerRadius = -1
    private var outerRadius = -1

    override fun run() {
        val local = connection ?: return
        if (!options.enabled || !options.unexploredGenerationEnabled) return
        val nextCenter = session.player.physics.positionInfo.chunkPosition
        val nextInner = session.world.view.viewDistance + 1
        val nextOuter = minOf(
            options.generationRadiusChunks,
            options.renderDistanceChunks,
            maximumContiguousDistantRadius(options.maximumTiles),
        )
        if (nextOuter <= nextInner) return
        if (nextCenter != center || nextInner != innerRadius || nextOuter != outerRadius) {
            center = nextCenter
            innerRadius = nextInner
            outerRadius = nextOuter
            spiral.reset(nextCenter, nextInner, nextOuter)
        }
        repeat(options.generationBudgetPerTick) {
            var position = spiral.next() ?: return
            while (contains(position)) {
                position = spiral.next() ?: return
            }
            val builder = ChunkBuilder(session.world, position)
            local.chunks.generator.generate(builder)
            publish(capture(builder))
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
            if (!material.isDistantWaterMaterial()) {
                return@capture DistantLodColumn(surfaceY, material)
            }
            var solidY = surfaceY - 1
            var solidMaterial: ResourceLocation? = null
            while (solidY >= dimension.minY) {
                val candidate = builder[x, solidY, z]?.block?.identifier
                if (candidate != null && !candidate.isDistantWaterMaterial()) {
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

internal fun maximumContiguousDistantRadius(maximumTiles: Int): Int {
    require(maximumTiles > 0) { "Distant LOD tile capacity must be positive" }
    var diameter = kotlin.math.sqrt(maximumTiles.toDouble()).toInt()
    if (diameter % 2 == 0) diameter--
    return ((diameter - 1) / 2).coerceAtLeast(0)
}

internal fun ResourceLocation.isDistantWaterMaterial(): Boolean =
    path == "water" || path.endsWith("_water")
