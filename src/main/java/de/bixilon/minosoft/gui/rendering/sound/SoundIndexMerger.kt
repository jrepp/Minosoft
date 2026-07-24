/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.sound

import de.bixilon.kutil.json.JsonUtil.asJsonObject
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.sound.sounds.SoundType
import de.bixilon.minosoft.util.KUtil.toResourceLocation

object SoundIndexMerger {

    /**
     * Merges sound indices in Minecraft resource-pack order: base assets first,
     * then progressively higher-priority packs.
     */
    fun mergeLowToHigh(indices: Iterable<Map<String, Any>>): Map<ResourceLocation, SoundType> {
        val merged: MutableMap<ResourceLocation, SoundType> = mutableMapOf()

        for (index in indices) {
            for ((name, rawData) in index) {
                val identifier = name.toResourceLocation()
                val data = rawData.asJsonObject()
                val current = SoundType(identifier, data)
                val previous = merged[identifier]

                if (previous == null || data["replace"] == true) {
                    merged[identifier] = current
                    continue
                }

                merged[identifier] = SoundType(
                    soundEvent = identifier,
                    sounds = previous.sounds + current.sounds,
                    subtitle = current.subtitle ?: previous.subtitle,
                )
            }
        }
        return merged
    }
}
