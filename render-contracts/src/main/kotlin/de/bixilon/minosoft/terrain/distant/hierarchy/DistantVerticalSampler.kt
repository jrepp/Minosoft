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

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId

/** One normalized voxel detached from any live world or renderer object. */
data class DistantVoxelSample(
    val material: TerrainSemanticMaterialId?,
    val fluid: DistantFluidSample? = null,
    val blockLight: Int = 0,
    val skyLight: Int = 0,
    val tint: DistantTintSample? = null,
    val opaque: Boolean = false,
    val emissive: Boolean = false,
    val generated: Boolean = false,
    val confidence: Int = 100,
) {
    init {
        require(material != null || fluid == null) { "A distant fluid voxel requires a material" }
        require(blockLight in 0..15 && skyLight in 0..15) { "Distant voxel light must be within 0..15" }
        require(confidence in 0..100) { "Distant voxel confidence must be within 0..100" }
    }

    val occupied: Boolean get() = material != null || fluid != null
}

fun interface DistantVoxelAccessor {
    fun sample(x: Int, y: Int, z: Int): DistantVoxelSample
}

/**
 * The shared normalization boundary for native, generated, server, persisted,
 * and fixture data. It records occupied intervals and enclosed empty intervals,
 * then applies the same deterministic bounded reduction to every source.
 */
object DistantVerticalSampler {
    fun capture(
        key: TerrainPageKey,
        width: Int,
        minimumY: Int,
        maximumYExclusive: Int,
        sourceRevision: Long,
        completeness: DistantSourceCompleteness,
        accessor: DistantVoxelAccessor,
    ): DistantVerticalPage {
        DistantPageHierarchy.requireDistant(key)
        require(width in 1..MAXIMUM_WIDTH && width.countOneBits() == 1) {
            "Distant sampler width must be a power of two within 1..$MAXIMUM_WIDTH"
        }
        requireVerticalRange(minimumY, maximumYExclusive)
        val columns = ArrayList<DistantVerticalColumn>(Math.multiplyExact(width, width))
        for (z in 0 until width) {
            for (x in 0 until width) columns += captureColumn(x, z, minimumY, maximumYExclusive, accessor)
        }
        return DistantVerticalPage(key, width, minimumY, sourceRevision, completeness, columns)
    }

    fun captureColumn(
        x: Int,
        z: Int,
        minimumY: Int,
        maximumYExclusive: Int,
        accessor: DistantVoxelAccessor,
    ): DistantVerticalColumn {
        requireVerticalRange(minimumY, maximumYExclusive)
        val intervals = ArrayList<SampleInterval>()
        var upper = maximumYExclusive
        var sampleY = maximumYExclusive - 1
        var current = accessor.sample(x, sampleY, z)
        while (sampleY > minimumY) {
            val y = sampleY - 1
            val next = accessor.sample(x, y, z)
            if (next != current) {
                intervals += SampleInterval(y + 1, upper, current)
                upper = y + 1
                current = next
            }
            sampleY = y
        }
        intervals += SampleInterval(minimumY, upper, current)

        val firstOccupied = intervals.indexOfFirst { it.sample.occupied }
        val lastOccupied = intervals.indexOfLast { it.sample.occupied }
        if (firstOccupied < 0) return DistantVerticalColumn(emptyList())
        val runs = ArrayList<DistantColumnRun>(lastOccupied - firstOccupied + 1)
        for (index in firstOccupied..lastOccupied) {
            val interval = intervals[index]
            runs += interval.toRun(enclosedEmpty = !interval.sample.occupied)
        }
        return DistantColumnReducer.reduce(runs, DistantVerticalColumn.MAXIMUM_RUNS)
    }

    private data class SampleInterval(
        val minimumY: Int,
        val maximumYExclusive: Int,
        val sample: DistantVoxelSample,
    ) {
        fun toRun(enclosedEmpty: Boolean): DistantColumnRun = DistantColumnRun(
            minimumY = minimumY,
            height = Math.subtractExact(maximumYExclusive, minimumY),
            material = sample.material,
            fluid = sample.fluid,
            blockLight = sample.blockLight,
            skyLight = sample.skyLight,
            tint = sample.tint,
            flags = buildSet {
                if (sample.opaque) add(DistantRunFlag.OPAQUE)
                if (sample.emissive) add(DistantRunFlag.EMISSIVE)
                if (sample.generated) add(DistantRunFlag.GENERATED)
                if (enclosedEmpty) add(DistantRunFlag.CAVE)
            },
            confidence = sample.confidence,
        )
    }

    private fun requireVerticalRange(minimumY: Int, maximumYExclusive: Int) {
        val height = maximumYExclusive.toLong() - minimumY.toLong()
        require(height in 1..MAXIMUM_VERTICAL_SAMPLES.toLong()) {
            "Distant sampler vertical span must be within 1..$MAXIMUM_VERTICAL_SAMPLES"
        }
    }

    private const val MAXIMUM_WIDTH = 64
    const val MAXIMUM_VERTICAL_SAMPLES = 4_096
}
