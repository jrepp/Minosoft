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

import de.bixilon.minosoft.data.entities.entities.AgeableMob
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.LivingEntity

object EntityTextureContextFactory {
    fun create(entity: Entity): EntityTextureContext {
        val uuid = entity.uuid
        val seed = uuid?.let { it.mostSignificantBits xor it.leastSignificantBits }
            ?: entity.id?.toLong()
            ?: entity.type.identifier.hashCode().toLong()
        val position = entity.physics.positionInfo
        val world = entity.session.world
        val biome = position.biome?.identifier
        val weather = when {
            world.weather.thunder > 0.0f -> "thunder"
            world.weather.raining -> "rain"
            else -> "clear"
        }
        val name = entity.name?.message.orEmpty()

        return EntityTextureContext(
            seed = seed,
            strings = buildMap {
                put("entity", listOf(entity.type.identifier.toString(), entity.type.identifier.path))
                if (name.isNotEmpty()) {
                    put("name", listOf(name))
                    put("names", listOf(name))
                }
                if (biome != null) {
                    put("biome", listOf(biome.toString(), biome.path))
                    put("biomes", listOf(biome.toString(), biome.path))
                }
                put("weather", listOf(weather))
            },
            numbers = buildMap {
                put("height", position.position.y.toDouble())
                put("heights", position.position.y.toDouble())
                put("day_time", world.time.time.toDouble())
                put("dayTime", world.time.time.toDouble())
                put("day", world.time.day.toDouble())
                put("moon_phase", world.time.moonPhase.ordinal.toDouble())
                put("moonPhase", world.time.moonPhase.ordinal.toDouble())
                put("id", (entity.id ?: 0).toDouble())
                if (entity is LivingEntity) put("health", entity.health)
            },
            booleans = buildMap {
                put("baby", entity is AgeableMob && entity.isBaby)
                put("is_baby", entity is AgeableMob && entity.isBaby)
                put("on_fire", entity.isOnFire)
                put("invisible", entity.isInvisible)
                put("sneaking", entity.isSneaking)
                put("sprinting", entity.isSprinting)
                put("swimming", entity.isSwimming)
            },
        )
    }

    fun key(entity: Entity): String {
        return entity.uuid?.toString()
            ?: entity.id?.let { "${entity.type.identifier}#$it" }
            ?: "${entity.type.identifier}@${System.identityHashCode(entity)}"
    }
}
