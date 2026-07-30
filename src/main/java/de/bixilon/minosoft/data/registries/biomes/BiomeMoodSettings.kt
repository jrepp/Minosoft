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

package de.bixilon.minosoft.data.registries.biomes

import de.bixilon.kutil.json.JsonUtil.toJsonObject
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * The client-side biome mood sound contract serialized by the vanilla biome
 * codec. Iris exposes both vanilla's sampled mood and its own constant variant.
 */
data class BiomeMoodSettings(
    val sound: ResourceLocation,
    val tickDelay: Int,
    val blockSearchExtent: Int,
    val soundPositionOffset: Double,
) {
    init {
        require(tickDelay > 0) { "Biome mood tick delay must be positive" }
        require(blockSearchExtent in 0..MAX_SEARCH_EXTENT) {
            "Biome mood search extent must be in 0..$MAX_SEARCH_EXTENT"
        }
        require(soundPositionOffset.isFinite()) { "Biome mood sound offset must be finite" }
    }

    companion object {
        const val MAX_SEARCH_EXTENT = 1_000_000

        fun deserialize(value: Any?): BiomeMoodSettings? {
            val data = value.toJsonObject() ?: return null
            val sound = soundIdentifier(data["sound"]) ?: return null
            val tickDelay = (data["tick_delay"] as? Number)?.toInt() ?: return null
            val blockSearchExtent = (data["block_search_extent"] as? Number)?.toInt() ?: return null
            val offset = (data["offset"] as? Number)?.toDouble() ?: return null
            return BiomeMoodSettings(sound, tickDelay, blockSearchExtent, offset)
        }

        private fun soundIdentifier(value: Any?): ResourceLocation? {
            val identifier = when (value) {
                is String -> value
                else -> {
                    val data = value.toJsonObject() ?: return null
                    (data["sound_id"] ?: data["id"] ?: data["value"])?.toString()
                }
            } ?: return null
            return ResourceLocation.of(identifier)
        }
    }
}
