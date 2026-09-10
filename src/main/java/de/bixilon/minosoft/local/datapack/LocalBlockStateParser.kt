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

package de.bixilon.minosoft.local.datapack

import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperty
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

/**
 * Resolves a block state from either a plain identifier string ("minecraft:stone")
 * or an SNBT/JSON compound carrying a "Name" and optional "Properties". Shared by
 * the local display factory and the bounded content.place-blocks debug operation.
 * Returns null for unknown blocks; throws for invalid property values.
 */
internal fun PlaySession.parseLocalBlockState(raw: Any?): BlockState? {
    val name = when (raw) {
        is String -> raw
        is Map<*, *> -> raw["Name"]?.toString() ?: return null
        else -> return null
    }
    val block = registries.block[ResourceLocation.of(name)] ?: return null
    val properties = (raw as? Map<*, *>)?.get("Properties")?.let { properties ->
        require(properties is Map<*, *>) { "Block state Properties must be a compound" }
        properties.entries.associate { entry -> entry.key.toString() to requireNotNull(entry.value) }
            .map { (propertyName, value) ->
                val property = block.properties[propertyName]
                    ?: throw IllegalArgumentException("Unknown property $propertyName for ${block.identifier}")
                property to requireNotNull(property.parse(value))
            }
            .toMap()
    } ?: emptyMap()
    return if (properties.isEmpty()) block.states.default else block.states.withProperties(properties)
}
