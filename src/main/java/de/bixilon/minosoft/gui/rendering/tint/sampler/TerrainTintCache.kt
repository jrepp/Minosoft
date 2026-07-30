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
import kotlin.math.floor

/**
 * Section-local tint cache with Sodium's half-block-offset, bilinear X/Z
 * sampling. The underlying radius sampler remains responsible for biome blur.
 */
class TerrainTintCache(
    private val chunk: Chunk,
    private val sampler: TintSampler,
) {
    private data class BlockKey(val state: BlockState, val position: BlockPosition)
    private data class FluidKey(val fluid: Fluid, val position: BlockPosition)

    private val blocks = HashMap<BlockKey, RGBArray?>()
    private val fluids = HashMap<FluidKey, RGBColor>()

    private fun block(state: BlockState, position: BlockPosition, tintIndex: Int, fallback: RGBColor): RGBColor {
        val colors = blocks.getOrPut(BlockKey(state, position)) {
            sampler.getBlockTint(chunk, state, position, null)?.let { RGBArray(it.array.copyOf()) }
        }
        return colors?.getOrNull(tintIndex) ?: fallback
    }

    private fun fluid(fluid: Fluid, position: BlockPosition): RGBColor {
        return fluids.getOrPut(FluidKey(fluid, position)) { sampler.getFluidTint(chunk, fluid, position) }
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
        internal fun bilinear(x: Double, z: Double, sample: (Int, Int) -> RGBColor): RGBColor {
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
