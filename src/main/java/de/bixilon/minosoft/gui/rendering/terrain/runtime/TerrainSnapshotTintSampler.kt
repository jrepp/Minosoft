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
 */

package de.bixilon.minosoft.gui.rendering.terrain.runtime

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.fluid.Fluid
import de.bixilon.minosoft.data.text.formatting.color.Colors
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.tint.TintProvider
import de.bixilon.minosoft.gui.rendering.tint.TintProviderFlags
import de.bixilon.minosoft.gui.rendering.tint.TintedBlock
import de.bixilon.minosoft.gui.rendering.tint.sampler.SampledColor
import de.bixilon.minosoft.gui.rendering.tint.sampler.SamplingAlgorithms
import de.bixilon.minosoft.gui.rendering.tint.sampler.gaussian.GaussianKernel
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Biome tint sampling over a detached terrain snapshot. The weighting matches
 * the production simple and Gaussian samplers, but every biome lookup is
 * resolved from immutable captured input rather than a live chunk graph.
 */
class TerrainSnapshotTintSampler(
    private val snapshot: TerrainBuildSnapshot,
    enabled: Boolean,
    private val algorithm: SamplingAlgorithms,
    radius: Int,
) {
    private val radius = validateRadius(snapshot, enabled, radius)
    private val origin = BlockPosition.of(snapshot.position)
    private val offsets = buildOffsets()
    private var blockSamples = emptyArray<SampledColor>()
    private val fluidSample = SampledColor()

    fun getBlockTint(
        state: BlockState,
        position: BlockPosition,
        cache: RGBArray?,
    ): RGBArray? {
        if (BlockStateFlags.TINTED !in state.flags) return null
        val provider = (state.block as? TintedBlock)?.tintProvider ?: return null
        val result = cache?.takeIf { it.size >= provider.count } ?: RGBArray(provider.count)
        if (TintProviderFlags.BIOME !in provider.flags) {
            for (index in 0 until provider.count) {
                result[index] = provider.getBlockTint(state, null, position, index)
            }
            return result
        }

        if (blockSamples.size < provider.count) {
            blockSamples = Array(provider.count) { SampledColor() }
        }
        for (index in 0 until provider.count) blockSamples[index].clear()
        samples(position) { samplePosition, biome, weight ->
            for (index in 0 until provider.count) {
                val color = provider.getBlockTint(state, biome, samplePosition, index)
                if (color != Colors.TRUE_BLACK) blockSamples[index].add(color, weight)
            }
        }
        for (index in 0 until provider.count) result[index] = blockSamples[index].toColor()
        return result
    }

    fun getFluidTint(fluid: Fluid, position: BlockPosition): RGBColor {
        val provider = fluid.model?.tint ?: return Colors.WHITE_RGB
        if (TintProviderFlags.BIOME !in provider.flags) return provider.getFluidTint(null, position)

        fluidSample.clear()
        samples(position) { samplePosition, biome, weight ->
            val color = provider.getFluidTint(biome, samplePosition)
            if (color != Colors.TRUE_BLACK) fluidSample.add(color, weight)
        }
        return fluidSample.toColor()
    }

    private inline fun samples(
        position: BlockPosition,
        consumer: (BlockPosition, de.bixilon.minosoft.data.registries.biomes.Biome, Int) -> Unit,
    ) {
        for (index in offsets.weights.indices) {
            val x = position.x + offsets.x[index]
            val y = position.y + offsets.y[index]
            val z = position.z + offsets.z[index]
            val biome = snapshot.biomeOrNull(x - origin.x, y - origin.y, z - origin.z) ?: continue
            consumer(BlockPosition(x, y, z), biome, offsets.weights[index])
        }
    }

    private fun buildOffsets(): SampleOffsets {
        val x = IntArrayList()
        val y = IntArrayList()
        val z = IntArrayList()
        val weights = IntArrayList()
        fun add(offsetX: Int, offsetY: Int, offsetZ: Int, weight: Int) {
            x.add(offsetX)
            y.add(offsetY)
            z.add(offsetZ)
            weights.add(weight)
        }
        if (radius == 0) {
            add(0, 0, 0, 1)
            return SampleOffsets(x.toIntArray(), y.toIntArray(), z.toIntArray(), weights.toIntArray())
        }
        if (algorithm == SamplingAlgorithms.GAUSSIAN) {
            val kernel = if (snapshot.verticalBiomeSampling) {
                GaussianKernel.get3D(radius)
            } else {
                GaussianKernel.get2D(radius)
            }
            kernel.iterate(::add)
            return SampleOffsets(x.toIntArray(), y.toIntArray(), z.toIntArray(), weights.toIntArray())
        }

        add(0, 0, 0, 100)
        val diameter = Math.multiplyExact(radius, radius)
        var offset = 1
        while (offset < radius) {
            val distance = Math.multiplyExact(offset, offset)
            var weight = (diameter * 100) / (diameter + distance * 3)
            add(-offset, 0, 0, weight)
            add(+offset, 0, 0, weight)
            add(0, 0, -offset, weight)
            add(0, 0, +offset, weight)

            weight = (diameter * 100) / (diameter + distance * 6)
            add(-offset, 0, -offset, weight)
            add(-offset, 0, +offset, weight)
            add(+offset, 0, -offset, weight)
            add(+offset, 0, +offset, weight)
            offset += maxOf(1, radius / 5)
        }
        return SampleOffsets(x.toIntArray(), y.toIntArray(), z.toIntArray(), weights.toIntArray())
    }

    private data class SampleOffsets(
        val x: IntArray,
        val y: IntArray,
        val z: IntArray,
        val weights: IntArray,
    )

    companion object {
        const val MAX_RADIUS = 15

        fun requiredHalo(enabled: Boolean, radius: Int): Int {
            require(radius in 0..MAX_RADIUS) { "Terrain tint radius must be between 0 and $MAX_RADIUS" }
            return if (enabled && radius > 0) Math.addExact(radius, 1) else 1
        }

        private fun validateRadius(snapshot: TerrainBuildSnapshot, enabled: Boolean, radius: Int): Int {
            require(radius in 0..MAX_RADIUS) { "Terrain tint radius must be between 0 and $MAX_RADIUS" }
            require(snapshot.halo >= requiredHalo(enabled, radius)) {
                "Terrain snapshot halo ${snapshot.halo} does not cover tint radius $radius"
            }
            return if (enabled && snapshot.horizontalBiomeSampling) radius else 0
        }
    }
}
