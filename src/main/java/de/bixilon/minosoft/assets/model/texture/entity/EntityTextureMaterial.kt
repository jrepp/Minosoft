/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.io.InputStream
import java.util.Properties

data class EntityTextureMaterial(
    val base: ResourceLocation,
    val emissive: ResourceLocation? = null,
    val blink: ResourceLocation? = null,
    val blinkEmissive: ResourceLocation? = null,
    val blink2: ResourceLocation? = null,
    val blink2Emissive: ResourceLocation? = null,
    val blinkFrequencyTicks: Int = DEFAULT_BLINK_FREQUENCY,
    val blinkLengthTicks: Int = DEFAULT_BLINK_LENGTH,
) {
    init {
        require(blinkFrequencyTicks > 0) { "Blink frequency must be positive." }
        require(blinkLengthTicks >= 0 && blinkLengthTicks <= MAX_BLINK_LENGTH) {
            "Blink length must be between 0 and $MAX_BLINK_LENGTH ticks."
        }
        require(blink2 == null || blink != null) { "A second blink frame requires the closed-eye blink frame." }
        require(blink2Emissive == null || blink2 != null) { "A second blink emissive requires the second blink frame." }
    }

    fun at(tick: Long, seed: Long): EntityTextureMaterialFrame {
        if (blink == null || blinkLengthTicks == 0) {
            return EntityTextureMaterialFrame(base, emissive, EntityTextureBlinkState.OPEN)
        }
        return when (EntityTextureBlinkTimeline.stateAt(
            tick,
            seed,
            blinkFrequencyTicks,
            blinkLengthTicks,
            hasHalfFrame = blink2 != null,
        )) {
            EntityTextureBlinkState.OPEN -> EntityTextureMaterialFrame(base, emissive, EntityTextureBlinkState.OPEN)
            EntityTextureBlinkState.HALF -> EntityTextureMaterialFrame(
                blink2 ?: blink,
                blink2Emissive ?: blinkEmissive ?: emissive,
                EntityTextureBlinkState.HALF,
            )
            EntityTextureBlinkState.CLOSED ->
                EntityTextureMaterialFrame(blink, blinkEmissive ?: emissive, EntityTextureBlinkState.CLOSED)
        }
    }

    companion object {
        const val DEFAULT_BLINK_FREQUENCY = 150
        const val DEFAULT_BLINK_LENGTH = 1
        const val MAX_BLINK_LENGTH = 20
    }
}

data class EntityTextureMaterialFrame(
    val base: ResourceLocation,
    val emissive: ResourceLocation?,
    val blinkState: EntityTextureBlinkState,
    /** ETF properties rule that selected this material; zero is the base fallback. */
    val ruleIndex: Int = 0,
    /** OptiFine/ETF texture suffix selected by that rule. */
    val textureSuffix: Int = 1,
) {
    val blinking get() = blinkState != EntityTextureBlinkState.OPEN
}

enum class EntityTextureBlinkState {
    OPEN,
    HALF,
    CLOSED,
}

object EntityTextureBlinkTimeline {
    fun stateAt(
        tick: Long,
        seed: Long,
        frequencyTicks: Int = EntityTextureMaterial.DEFAULT_BLINK_FREQUENCY,
        lengthTicks: Int = EntityTextureMaterial.DEFAULT_BLINK_LENGTH,
        hasHalfFrame: Boolean,
    ): EntityTextureBlinkState {
        if (lengthTicks <= 0) return EntityTextureBlinkState.OPEN
        val spread = frequencyTicks.toLong() * 2L
        val interval = frequencyTicks + 20L + floorMod(mix(seed), spread)
        val phase = floorMod(tick, interval)
        if (phase > lengthTicks.toLong() * 2L) return EntityTextureBlinkState.OPEN
        if (hasHalfFrame) {
            val closedStart = lengthTicks / 1.5
            val closedEnd = lengthTicks + 1.0 + lengthTicks / 3.0
            if (phase.toDouble() < closedStart || phase.toDouble() > closedEnd) {
                return EntityTextureBlinkState.HALF
            }
        }
        return EntityTextureBlinkState.CLOSED
    }

    private fun mix(input: Long): Long {
        var value = input
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return value xor (value ushr 33)
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val remainder = value % divisor
        return if (remainder < 0) remainder + divisor else remainder
    }
}

data class EntityTextureBlinkSettings(
    val frequencyTicks: Int = EntityTextureMaterial.DEFAULT_BLINK_FREQUENCY,
    val lengthTicks: Int = EntityTextureMaterial.DEFAULT_BLINK_LENGTH,
) {
    init {
        require(frequencyTicks > 0) { "Blink frequency must be positive." }
        require(lengthTicks in 0..EntityTextureMaterial.MAX_BLINK_LENGTH) {
            "Blink length must be between 0 and ${EntityTextureMaterial.MAX_BLINK_LENGTH} ticks."
        }
    }
}

object EntityTextureMaterialPropertiesParser {
    fun parseBlink(source: ResourceLocation, input: InputStream): EntityTextureBlinkSettings {
        val properties = read(source, input)
        fun positive(key: String, fallback: Int): Int {
            val raw = properties.getProperty(key) ?: return fallback
            return raw.filter(Char::isDigit).toIntOrNull()
                ?: throw IllegalArgumentException("$source: $key must contain a positive integer.")
        }
        return EntityTextureBlinkSettings(
            frequencyTicks = positive("blinkFrequency", EntityTextureMaterial.DEFAULT_BLINK_FREQUENCY),
            lengthTicks = positive("blinkLength", EntityTextureMaterial.DEFAULT_BLINK_LENGTH),
        )
    }

    fun parseEmissiveSuffixes(source: ResourceLocation, input: InputStream): Set<String> {
        val properties = read(source, input)
        return listOf("entities.suffix.emissive", "suffix.emissive")
            .mapNotNull(properties::getProperty)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .onEach {
                require(it.length <= MAX_SUFFIX_LENGTH && it.matches(SUFFIX)) {
                    "$source: invalid emissive suffix '$it'."
                }
            }
            .toSet()
    }

    private fun read(source: ResourceLocation, input: InputStream): Properties {
        val bytes = input.use { it.readNBytes(MAX_PROPERTIES_BYTES + 1) }
        require(bytes.size <= MAX_PROPERTIES_BYTES) {
            "$source exceeds the $MAX_PROPERTIES_BYTES byte material properties limit."
        }
        return Properties().also {
            bytes.inputStream().use(it::load)
            require(it.size <= MAX_PROPERTIES) {
                "$source exceeds the $MAX_PROPERTIES material property limit."
            }
        }
    }

    private val SUFFIX = Regex("_[a-zA-Z0-9_.-]+")
    private const val MAX_SUFFIX_LENGTH = 64
    private const val MAX_PROPERTIES_BYTES = 64 * 1024
    private const val MAX_PROPERTIES = 256
}
