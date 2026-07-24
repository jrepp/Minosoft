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
import de.bixilon.minosoft.data.entities.entities.TamableAnimal
import de.bixilon.minosoft.data.entities.entities.animal.Bee
import de.bixilon.minosoft.data.entities.entities.animal.Cat
import de.bixilon.minosoft.data.entities.entities.animal.Sheep
import de.bixilon.minosoft.data.entities.entities.animal.Wolf
import de.bixilon.minosoft.data.entities.entities.monster.Shulker
import de.bixilon.minosoft.data.entities.entities.npc.villager.Villager
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import kotlin.math.sqrt

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
        val strings = linkedMapOf<String, MutableList<String>>()
        val numbers = linkedMapOf<String, Double>()
        val booleans = linkedMapOf<String, Boolean>()

        fun strings(key: String, vararg value: String?) {
            val present = value.filterNotNull().filter(String::isNotEmpty)
            if (present.isNotEmpty()) strings.getOrPut(key, ::mutableListOf).addAll(present)
        }
        fun number(key: String, value: Number) {
            val finite = value.toDouble()
            if (finite.isFinite()) numbers[key] = finite
        }
        fun boolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        strings("entity", entity.type.identifier.toString(), entity.type.identifier.path)
        strings("name", name)
        strings("names", name)
        biome?.let {
            strings("biome", it.toString(), it.path)
            strings("biomes", it.toString(), it.path)
        }
        strings("weather", weather)
        (entity as? PlayerEntity)?.additional?.team?.name?.let {
            strings("team", it)
            strings("teams", it)
        }
        (entity as? Villager)?.villagerData?.let {
            strings("profession", it.profession.identifier.toString(), it.profession.identifier.path)
            strings("professions", it.profession.identifier.toString(), it.profession.identifier.path)
            strings("villager_type", it.type.toString(), it.type.path)
            number("career", it.level)
        }
        when (entity) {
            is Cat -> {
                entity.variant?.identifier?.let { strings("variant", it.toString(), it.path) }
                strings("collar_color", colorName(entity.collarColor))
                strings("collarColors", colorName(entity.collarColor))
            }
            is Wolf -> {
                strings("collar_color", colorName(entity.collarColor))
                strings("collarColors", colorName(entity.collarColor))
                boolean("angry", entity.angerTime > 0)
            }
            is Sheep -> {
                strings("color", colorName(entity.color))
                strings("colors", colorName(entity.color))
                boolean("sheared", entity.isSheared)
            }
            is Shulker -> {
                strings("color", colorName(entity.color))
                strings("colors", colorName(entity.color))
            }
            is Bee -> boolean("angry", entity.isAngry)
        }

        number("height", position.position.y)
        number("heights", position.position.y)
        number("day_time", world.time.time)
        number("dayTime", world.time.time)
        number("day", world.time.day)
        number("moon_phase", world.time.moonPhase.ordinal)
        number("moonPhase", world.time.moonPhase.ordinal)
        number("id", entity.id ?: 0)
        number("size", maxOf(entity.dimensions.x, entity.dimensions.y))
        val speed = sqrt(entity.physics.velocity.length2()).takeIf(Double::isFinite) ?: 0.0
        number("speed", speed)
        if (entity is LivingEntity) number("health", entity.health)

        boolean("baby", entity is AgeableMob && entity.isBaby)
        boolean("is_baby", entity is AgeableMob && entity.isBaby)
        boolean("on_fire", entity.isOnFire)
        boolean("invisible", entity.isInvisible)
        boolean("sneaking", entity.isSneaking)
        boolean("sprinting", entity.isSprinting)
        boolean("swimming", entity.isSwimming)
        boolean("moving", speed > MOVING_EPSILON)
        if (entity is TamableAnimal) {
            boolean("tamed", entity.isTamed)
            boolean("sitting", entity.isSitting)
        }

        val syntheticNbt = linkedMapOf<String, Any>(
            "id" to entity.type.identifier.toString(),
            "Pos" to listOf(position.position.x, position.position.y, position.position.z),
            "CustomName" to name,
        )
        if (entity is LivingEntity) syntheticNbt["Health"] = entity.health
        if (entity is AgeableMob) syntheticNbt["Age"] = if (entity.isBaby) -1 else 0
        flattenNbt("nbt", syntheticNbt, strings, numbers, booleans)
        synchronized(entity.commandNbt) {
            flattenNbt("nbt", entity.commandNbt, strings, numbers, booleans)
        }

        return EntityTextureContext(seed, strings.mapValues { it.value.toList() }, numbers, booleans)
    }

    fun key(entity: Entity): String {
        return entity.uuid?.toString()
            ?: entity.id?.let { "${entity.type.identifier}#$it" }
            ?: "${entity.type.identifier}@${System.identityHashCode(entity)}"
    }

    internal fun flattenNbt(
        prefix: String,
        value: Any?,
        strings: MutableMap<String, MutableList<String>>,
        numbers: MutableMap<String, Double>,
        booleans: MutableMap<String, Boolean>,
        budget: IntArray = intArrayOf(MAX_NBT_VALUES),
        depth: Int = 0,
    ) {
        if (budget[0]-- <= 0 || depth > MAX_NBT_DEPTH) return
        when (value) {
            is Map<*, *> -> value.entries.forEach { (key, child) ->
                flattenNbt("$prefix.${key.toString()}", child, strings, numbers, booleans, budget, depth + 1)
            }
            is List<*> -> value.forEachIndexed { index, child ->
                flattenNbt("$prefix.$index", child, strings, numbers, booleans, budget, depth + 1)
            }
            is Boolean -> {
                booleans[prefix] = value
                strings.getOrPut(prefix, ::mutableListOf) += value.toString()
            }
            is Number -> {
                val finite = value.toDouble()
                if (finite.isFinite()) numbers[prefix] = finite
                strings.getOrPut(prefix, ::mutableListOf) += value.toString()
            }
            null -> Unit
            else -> strings.getOrPut(prefix, ::mutableListOf) += value.toString()
        }
    }

    private fun colorName(color: RGBColor): String {
        return ChatColors.NAME_MAP.entries.firstOrNull { it.value.rgb == color.rgb }?.key
            ?: color.toString()
    }

    private const val MOVING_EPSILON = 1.0E-8
    private const val MAX_NBT_DEPTH = 32
    private const val MAX_NBT_VALUES = 4096
}
