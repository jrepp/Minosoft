/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.registries.biomes

import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.world.positions.BlockPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VanillaBiomeTemperatureTest {

    @Test
    fun `fixed temperature noise matches the pinned vanilla sampler`() {
        assertEquals(0.0, VanillaBiomeTemperature.temperatureNoise(0.0, 0.0), 1.0E-15)
        assertEquals(-0.23526496123584156, VanillaBiomeTemperature.temperatureNoise(1.0, 2.0), 1.0E-15)
        assertEquals(0.31056928987772464, VanillaBiomeTemperature.temperatureNoise(12.5, -7.25), 1.0E-15)
        assertEquals(-4.7307189321830296E-4, VanillaBiomeTemperature.temperatureNoise(125.0, -90.0), 1.0E-15)
    }

    @Test
    fun `temperature is unchanged below the vanilla altitude boundary`() {
        val biome = Biome(minecraft("plains"), 0.8f, 0.4f, precipitation = BiomePrecipitation.RAIN)

        assertEquals(0.8f, biome.temperatureAt(BlockPosition(100, 80, -58)))
    }

    @Test
    fun `high altitude converts marginal rain to snow`() {
        val biome = Biome(minecraft("mountain"), 0.2f, 0.4f, precipitation = BiomePrecipitation.RAIN)
        val position = BlockPosition(0, 256, 0)

        assertTrue(biome.temperatureAt(position) < 0.15f)
        assertEquals(BiomePrecipitation.SNOW, biome.precipitationAt(position))
    }

    @Test
    fun `warm high altitude precipitation remains rain`() {
        val biome = Biome(minecraft("warm_mountain"), 2.0f, 0.4f, precipitation = BiomePrecipitation.RAIN)

        assertEquals(BiomePrecipitation.RAIN, biome.precipitationAt(BlockPosition(0, 256, 0)))
    }

    @Test
    fun `frozen modifier follows the pinned vanilla noise patches`() {
        val biome = Biome(
            minecraft("frozen_ocean"),
            temperature = 0.5f,
            downfall = 0.5f,
            precipitation = BiomePrecipitation.RAIN,
            temperatureModifier = BiomeTemperatureModifier.FROZEN,
        )

        assertEquals(0.2f, biome.temperatureAt(BlockPosition(0, 80, 0)))
        assertEquals(0.5f, biome.temperatureAt(BlockPosition(20, 80, 20)))
    }

    @Test
    fun `modern biome codec fields retain precipitation and temperature modifier`() {
        val biome = Biome.deserialize(
            registries = null,
            identifier = minecraft("frozen_ocean"),
            data = mapOf(
                "temperature" to 0.5f,
                "downfall" to 0.5f,
                "has_precipitation" to true,
                "temperature_modifier" to "frozen",
            ),
        )

        assertEquals(BiomePrecipitation.RAIN, biome.precipitation)
        assertEquals(BiomeTemperatureModifier.FROZEN, biome.temperatureModifier)
    }

    @Test
    fun `modern biome codec retains ambient mood settings`() {
        val biome = Biome.deserialize(
            registries = null,
            identifier = minecraft("plains"),
            data = mapOf(
                "temperature" to 0.8f,
                "downfall" to 0.4f,
                "effects" to mapOf(
                    "mood_sound" to mapOf(
                        "sound" to "minecraft:ambient.cave",
                        "tick_delay" to 6000,
                        "block_search_extent" to 8,
                        "offset" to 2.0,
                    ),
                ),
            ),
        )

        assertEquals(
            BiomeMoodSettings(
                sound = minecraft("ambient.cave"),
                tickDelay = 6000,
                blockSearchExtent = 8,
                soundPositionOffset = 2.0,
            ),
            biome.moodSettings,
        )
    }
}
