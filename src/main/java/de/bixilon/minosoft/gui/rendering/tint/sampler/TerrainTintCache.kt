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

package de.bixilon.minosoft.gui.rendering.tint.sampler

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.fluid.Fluid
import de.bixilon.minosoft.data.text.formatting.color.ColorInterpolation.interpolateLinear
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainSnapshotTintSampler
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.IdentityHashMap
import kotlin.math.floor

/**
 * Section-local tint cache with Sodium's half-block-offset, bilinear X/Z
 * sampling. The underlying radius sampler remains responsible for biome blur.
 */
class TerrainTintCache(
    private val blockSampler: (BlockState, BlockPosition) -> RGBArray?,
    private val fluidSampler: (Fluid, BlockPosition) -> RGBColor,
) {
    constructor(chunk: Chunk, sampler: TintSampler) : this(
        blockSampler = { state, position ->
            sampler.getBlockTint(chunk, state, position, null)?.let { RGBArray(it.array.copyOf()) }
        },
        fluidSampler = { fluid, position -> sampler.getFluidTint(chunk, fluid, position) },
    )

    constructor(snapshot: TerrainBuildSnapshot, sampler: TerrainSnapshotTintSampler) : this(
        blockSampler = { state, position ->
            sampler.getBlockTint(state, position, null)?.let { RGBArray(it.array.copyOf()) }
        },
        fluidSampler = sampler::getFluidTint,
    )

    private class BlockEntries {
        val colors = Long2ObjectOpenHashMap<RGBArray>()
        val missing = LongOpenHashSet()
    }

    private val blocks = IdentityHashMap<BlockState, BlockEntries>()
    private val fluids = IdentityHashMap<Fluid, Long2IntOpenHashMap>()

    private fun block(state: BlockState, position: BlockPosition, tintIndex: Int, fallback: RGBColor): RGBColor {
        val entries = blocks.getOrPut(state, ::BlockEntries)
        val key = position.raw
        var colors = entries.colors[key]
        if (colors == null && key !in entries.missing) {
            colors = blockSampler(state, position)
            if (colors == null) {
                entries.missing += key
            } else {
                entries.colors[key] = colors
            }
        }
        return colors?.getOrNull(tintIndex) ?: fallback
    }

    private fun fluid(fluid: Fluid, position: BlockPosition): RGBColor {
        val entries = fluids.getOrPut(fluid) {
            Long2IntOpenHashMap()
        }
        val key = position.raw
        if (entries.containsKey(key)) return RGBColor(entries.get(key))
        return fluidSampler(fluid, position).also { entries.put(key, it.rgb) }
    }

    fun block(
        state: BlockState,
        position: BlockPosition,
        vertices: FaceVertexData,
        tintIndex: Int,
        fallback: RGBColor,
    ): Array<RGBColor> = Array(4) { vertex ->
        val offset = vertex * 3
        val x = position.x + vertices[offset] - 0.5
        val z = position.z + vertices[offset + 2] - 0.5
        bilinear(x, z) { sampleX, sampleZ ->
            block(state, position.with(x = sampleX, z = sampleZ), tintIndex, fallback)
        }
    }

    fun fluid(fluid: Fluid, position: BlockPosition, vertices: FaceVertexData): Array<RGBColor> = Array(4) { vertex ->
        val offset = vertex * 3
        val x = position.x + vertices[offset] - 0.5
        val z = position.z + vertices[offset + 2] - 0.5
        bilinear(x, z) { sampleX, sampleZ -> fluid(fluid, position.with(x = sampleX, z = sampleZ)) }
    }

    companion object {
        internal inline fun bilinear(x: Double, z: Double, sample: (Int, Int) -> RGBColor): RGBColor {
            val x0 = floor(x).toInt()
            val z0 = floor(z).toInt()
            val dx = (x - x0).toFloat()
            val dz = (z - z0).toFloat()
            val north = interpolateLinear(dx, sample(x0, z0), sample(x0 + 1, z0))
            val south = interpolateLinear(dx, sample(x0, z0 + 1), sample(x0 + 1, z0 + 1))
            return interpolateLinear(dz, north, south)
        }
    }
}
