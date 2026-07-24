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

data class EntityTextureMaterial(
    val base: ResourceLocation,
    val emissive: ResourceLocation? = null,
    val blink: ResourceLocation? = null,
    val blinkEmissive: ResourceLocation? = null,
    val blinkIntervalTicks: Int = 100,
    val blinkLengthTicks: Int = 2,
) {
    init {
        require(blinkIntervalTicks > 0) { "Blink interval must be positive." }
        require(blinkLengthTicks >= 0 && blinkLengthTicks <= blinkIntervalTicks) { "Blink length must be within the interval." }
    }

    fun at(tick: Long, seed: Long): EntityTextureMaterialFrame {
        val offset = floorMod(mix(seed), blinkIntervalTicks.toLong())
        val blinking = blink != null && floorMod(tick + offset, blinkIntervalTicks.toLong()) < blinkLengthTicks
        return if (blinking) {
            EntityTextureMaterialFrame(blink, blinkEmissive ?: emissive, true)
        } else {
            EntityTextureMaterialFrame(base, emissive, false)
        }
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

data class EntityTextureMaterialFrame(
    val base: ResourceLocation,
    val emissive: ResourceLocation?,
    val blinking: Boolean,
)
