/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.registries.biomes

import de.bixilon.minosoft.data.world.positions.BlockPosition
import java.util.Random
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Reflection-free port of the fixed-seed two-dimensional simplex samplers used
 * by Minecraft 1.20.4's biome temperature calculation.
 */
internal object VanillaBiomeTemperature {
    private val temperature = VanillaSimplexNoise(Random(1234L))
    private val frozenOcean = VanillaOctaveSimplexNoise(Random(3456L), 3)
    private val foliage = VanillaSimplexNoise(Random(2345L))

    fun at(biome: Biome, position: BlockPosition): Float {
        var temperature = when (biome.temperatureModifier) {
            BiomeTemperatureModifier.NONE -> biome.temperature
            BiomeTemperatureModifier.FROZEN -> frozenTemperature(position, biome.temperature)
        }
        if (position.y > ALTITUDE_START) {
            val noise = this.temperature.sample(position.x / 8.0, position.z / 8.0) * 8.0
            temperature -= ((noise + position.y - ALTITUDE_START) * ALTITUDE_SCALE / ALTITUDE_DIVISOR).toFloat()
        }
        return temperature
    }

    private fun frozenTemperature(position: BlockPosition, fallback: Float): Float {
        val combined = frozenOcean.sample(position.x * 0.05, position.z * 0.05) * 7.0 +
            foliage.sample(position.x * 0.2, position.z * 0.2)
        if (combined >= FROZEN_COMBINED_THRESHOLD) return fallback
        if (foliage.sample(position.x * 0.09, position.z * 0.09) >= FROZEN_DETAIL_THRESHOLD) return fallback
        return FROZEN_PATCH_TEMPERATURE
    }

    internal fun temperatureNoise(x: Double, z: Double): Double = temperature.sample(x, z)

    private const val ALTITUDE_START = 80
    private const val ALTITUDE_SCALE = 0.05
    private const val ALTITUDE_DIVISOR = 40.0
    private const val FROZEN_COMBINED_THRESHOLD = 0.3
    private const val FROZEN_DETAIL_THRESHOLD = 0.8
    private const val FROZEN_PATCH_TEMPERATURE = 0.2f
}

/**
 * The octave configuration used above has a highest octave of zero. Minecraft
 * constructs one sampler per requested descending octave, then evaluates it
 * with frequency 1, 1/2, ... and normalized weights 1/(2^n-1), 2/(2^n-1), ...
 */
private class VanillaOctaveSimplexNoise(random: Random, count: Int) {
    private val samplers = List(count) { VanillaSimplexNoise(random) }
    private val divisor = (1 shl count) - 1

    fun sample(x: Double, z: Double): Double {
        var result = 0.0
        var frequency = 1.0
        var weight = 1.0 / divisor
        for (sampler in samplers) {
            result += sampler.sample(x * frequency, z * frequency) * weight
            frequency *= 0.5
            weight *= 2.0
        }
        return result
    }
}

private class VanillaSimplexNoise(random: Random) {
    private val permutation = IntArray(PERMUTATION_SIZE) { it }

    init {
        // Vanilla consumes three origin doubles even when sample(..., false)
        // omits the origins from the two-dimensional lookup.
        repeat(3) { random.nextDouble() }
        for (index in permutation.indices) {
            val swap = index + random.nextInt(PERMUTATION_SIZE - index)
            val value = permutation[index]
            permutation[index] = permutation[swap]
            permutation[swap] = value
        }
    }

    fun sample(x: Double, z: Double): Double {
        val skew = (x + z) * SKEW_FACTOR
        val cellX = floor(x + skew).toInt()
        val cellZ = floor(z + skew).toInt()
        val unskew = (cellX + cellZ) * UNSKEW_FACTOR
        val originX = cellX - unskew
        val originZ = cellZ - unskew
        val localX = x - originX
        val localZ = z - originZ
        val stepX: Int
        val stepZ: Int
        if (localX > localZ) {
            stepX = 1
            stepZ = 0
        } else {
            stepX = 0
            stepZ = 1
        }
        val middleX = localX - stepX + UNSKEW_FACTOR
        val middleZ = localZ - stepZ + UNSKEW_FACTOR
        val endX = localX - 1.0 + 2.0 * UNSKEW_FACTOR
        val endZ = localZ - 1.0 + 2.0 * UNSKEW_FACTOR
        val maskedX = cellX and PERMUTATION_MASK
        val maskedZ = cellZ and PERMUTATION_MASK
        val first = map(maskedX + map(maskedZ)) % GRADIENT_COUNT
        val middle = map(maskedX + stepX + map(maskedZ + stepZ)) % GRADIENT_COUNT
        val end = map(maskedX + 1 + map(maskedZ + 1)) % GRADIENT_COUNT
        return SIMPLEX_SCALE * (
            gradient(first, localX, localZ) +
                gradient(middle, middleX, middleZ) +
                gradient(end, endX, endZ)
            )
    }

    private fun map(value: Int): Int = permutation[value and PERMUTATION_MASK]

    private fun gradient(index: Int, x: Double, z: Double): Double {
        var falloff = GRADIENT_RADIUS - x * x - z * z
        if (falloff < 0.0) return 0.0
        falloff *= falloff
        val gradient = GRADIENTS[index]
        return falloff * falloff * (gradient[0] * x + gradient[1] * z)
    }

    private companion object {
        const val PERMUTATION_SIZE = 256
        const val PERMUTATION_MASK = PERMUTATION_SIZE - 1
        const val GRADIENT_COUNT = 12
        const val GRADIENT_RADIUS = 0.5
        const val SIMPLEX_SCALE = 70.0
        val SQRT_3 = sqrt(3.0)
        val SKEW_FACTOR = (SQRT_3 - 1.0) / 2.0
        val UNSKEW_FACTOR = (3.0 - SQRT_3) / 6.0
        val GRADIENTS = arrayOf(
            intArrayOf(1, 1),
            intArrayOf(-1, 1),
            intArrayOf(1, -1),
            intArrayOf(-1, -1),
            intArrayOf(1, 0),
            intArrayOf(-1, 0),
            intArrayOf(1, 0),
            intArrayOf(-1, 0),
            intArrayOf(0, 1),
            intArrayOf(0, -1),
            intArrayOf(0, 1),
            intArrayOf(0, -1),
        )
    }
}
