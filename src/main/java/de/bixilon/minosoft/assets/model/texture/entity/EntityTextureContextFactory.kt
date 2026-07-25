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

import de.bixilon.minosoft.data.abilities.Gamemodes
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.entities.entities.AgeableMob
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.TamableAnimal
import de.bixilon.minosoft.data.entities.entities.animal.Axolotl
import de.bixilon.minosoft.data.entities.entities.animal.Bee
import de.bixilon.minosoft.data.entities.entities.animal.Cat
import de.bixilon.minosoft.data.entities.entities.animal.Fox
import de.bixilon.minosoft.data.entities.entities.animal.Frog
import de.bixilon.minosoft.data.entities.entities.animal.Goat
import de.bixilon.minosoft.data.entities.entities.animal.IronGolem
import de.bixilon.minosoft.data.entities.entities.animal.Mooshroom
import de.bixilon.minosoft.data.entities.entities.animal.Panda
import de.bixilon.minosoft.data.entities.entities.animal.Parrot
import de.bixilon.minosoft.data.entities.entities.animal.Rabbit
import de.bixilon.minosoft.data.entities.entities.animal.Sheep
import de.bixilon.minosoft.data.entities.entities.animal.Wolf
import de.bixilon.minosoft.data.entities.entities.animal.horse.AbstractHorse
import de.bixilon.minosoft.data.entities.entities.animal.horse.Llama
import de.bixilon.minosoft.data.entities.entities.animal.water.TropicalFish
import de.bixilon.minosoft.data.entities.entities.monster.Creeper
import de.bixilon.minosoft.data.entities.entities.monster.Shulker
import de.bixilon.minosoft.data.entities.entities.npc.villager.Villager
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.effects.attributes.MinecraftAttributes
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil
import de.bixilon.minosoft.tags.MinecraftTagTypes.BIOME
import java.util.Calendar
import kotlin.math.sqrt

object EntityTextureContextFactory {
    fun create(entity: Entity, requestedKeys: Set<String> = emptySet()): EntityTextureContext {
        val uuid = entity.uuid
        val seed = uuid?.let { it.mostSignificantBits xor it.leastSignificantBits }
            ?: entity.id?.toLong()
            ?: entity.type.identifier.hashCode().toLong()
        val position = entity.physics.positionInfo
        val world = entity.session.world
        val localPlayer: PlayerEntity? = entity.session.player
        val difficulty = world.difficulty?.difficulty
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
            val biomeTags = linkedSetOf(
                *entity.session.tags[BIOME]?.matching(position.biome).orEmpty().toTypedArray(),
                *entity.session.legacyTags[BIOME]?.matching(position.biome).orEmpty().toTypedArray(),
            )
            for (tag in biomeTags) {
                strings("biome_tag", tag.toString(), tag.path, "#$tag")
            }
        }
        strings("weather", weather)
        world.name?.let {
            strings("dimension", it.toString(), it.path)
        }
        difficulty?.name?.lowercase()?.let { strings("difficulty", it) }
        strings("minecraft_version", entity.session.version.name)
        strings("minecraftVersion", entity.session.version.name)
        strings(
            "language",
            entity.session.profiles.session.language ?: entity.session.profiles.eros.general.language,
        )
        EntityTextureRuntimeEnvironment.loadedMods().forEach { strings("mod_loaded", it) }
        localPlayer?.gamemode?.name?.lowercase()?.let {
            strings("client_game_mode", it)
            strings("clientGameMode", it)
        }
        strings("variant", variant(entity) ?: entity.type.identifier.path)
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
            is Creeper -> boolean("charged", entity.isCharged)
            is Goat -> boolean("screaming_goat", entity.isScreaming)
            is IronGolem -> boolean("player_created", entity.isPlayerCreated)
            is Panda -> {
                strings("hidden_gene", entity.hiddenGene.name.lowercase())
                strings("main_gene", entity.mainGene.name.lowercase())
            }
            is Llama -> number("llama_inventory", entity.strength)
        }

        val calendar = Calendar.getInstance()
        number("height", position.position.y)
        number("heights", position.position.y)
        number("day_time", world.time.time)
        number("dayTime", world.time.time)
        number("day", world.time.day)
        number("moon_phase", world.time.moonPhase.ordinal)
        number("moonPhase", world.time.moonPhase.ordinal)
        difficulty?.let {
            number(
                "regional_difficulty",
                EntityTextureRegionalDifficulty.calculate(
                    it,
                    world.time.age,
                    inhabitedTime = 0L,
                    moonSize = if (position.chunk != null) world.time.moonPhase.light else 0.0f,
                ),
            )
        }
        number("hour", calendar.get(Calendar.HOUR_OF_DAY))
        number("minute", calendar.get(Calendar.MINUTE))
        number("second", calendar.get(Calendar.SECOND))
        number("month", calendar.get(Calendar.MONTH))
        number("month_day", calendar.get(Calendar.DAY_OF_MONTH))
        number("week_day", calendar.get(Calendar.DAY_OF_WEEK))
        number("year_day", calendar.get(Calendar.DAY_OF_YEAR))
        number("year", calendar.get(Calendar.YEAR))
        number("id", entity.id ?: 0)
        number("size", maxOf(entity.dimensions.x, entity.dimensions.y))
        number("light", world.getLight(position.position).let { maxOf(it.block, it.sky) })
        position.biome?.let { number("temperature", it.temperature) }
        val velocity = sqrt(entity.physics.velocity.length2()).takeIf(Double::isFinite) ?: 0.0
        if (entity is LivingEntity) {
            val maxHealth = entity.attributes[MinecraftAttributes.MAX_HEALTH]
            number("speed", entity.attributes[MinecraftAttributes.MOVEMENT_SPEED])
            number("health", entity.health)
            number("max_health", maxHealth)
            if (maxHealth > 0.0) number("health_percent", (entity.health / maxHealth * 100.0).toInt())
            populateEquipment(entity, strings, booleans)
        }
        if (entity is AbstractHorse) {
            number("jump_strength", entity.attributes[MinecraftAttributes.HORSE_JUMP_STRENGTH])
        }
        localPlayer?.let {
            number("distance", sqrt(Vec3dUtil.distance2(entity.physics.position, it.physics.position)))
        }

        boolean("baby", entity is AgeableMob && entity.isBaby)
        boolean("is_baby", entity is AgeableMob && entity.isBaby)
        boolean("on_fire", entity.isOnFire)
        boolean("invisible", entity.isInvisible)
        boolean("sneaking", entity.isSneaking)
        boolean("sprinting", entity.isSprinting)
        boolean("swimming", entity.isSwimming)
        boolean("moving", velocity > MOVING_EPSILON)
        boolean("hardcore", world.hardcore)
        boolean("spawner", false)
        boolean("client_player", entity === localPlayer)
        boolean("creative", entity is PlayerEntity && entity.gamemode == Gamemodes.CREATIVE)
        boolean(
            "teammate",
            entity is PlayerEntity &&
                localPlayer?.additional?.team != null &&
                entity.additional.team?.name == localPlayer.additional.team?.name,
        )
        if (entity is TamableAnimal) {
            boolean("tamed", entity.isTamed)
            boolean("sitting", entity.isSitting)
        }

        appendEntityNbt("nbt", entity, strings, numbers, booleans)
        localPlayer?.let { appendEntityNbt("nbt_client", it, strings, numbers, booleans) }
        entity.attachment.vehicle?.let { appendEntityNbt("nbt_vehicle", it, strings, numbers, booleans) }

        if (requestedKeys.any(CURRENT_BLOCK_KEYS::contains) && position.chunk != null) {
            val below = if (position.position.y > world.dimension.minY) {
                world[BlockPosition(position.position.x, position.position.y - 1, position.position.z)]
            } else {
                null
            }
            populateBlocks("blocks", listOf(position.state, below), strings, includeAir = true)
            populateBlocks("block_spawned", listOf(position.state, below), strings, includeAir = true)
        }
        if ("blockAbove" in requestedKeys || "block_above" in requestedKeys) {
            populateBlocks("block_above", listOf(firstVerticalBlock(entity, 1, solid = false)), strings)
        }
        if ("blockAboveSolid" in requestedKeys || "block_above_solid" in requestedKeys) {
            populateBlocks("block_above_solid", listOf(firstVerticalBlock(entity, 1, solid = true)), strings)
        }
        if ("blockBelow" in requestedKeys || "block_below" in requestedKeys) {
            populateBlocks("block_below", listOf(firstVerticalBlock(entity, -1, solid = false)), strings)
        }
        if ("blockBelowSolid" in requestedKeys || "block_below_solid" in requestedKeys) {
            populateBlocks("block_below_solid", listOf(firstVerticalBlock(entity, -1, solid = true)), strings)
        }

        return EntityTextureContext(seed, strings.mapValues { it.value.toList() }, numbers, booleans)
    }

    fun key(entity: Entity): String {
        return entity.uuid?.toString()
            ?: entity.id?.let { "${entity.type.identifier}#$it" }
            ?: "${entity.type.identifier}@${System.identityHashCode(entity)}"
    }

    private fun populateEquipment(
        entity: LivingEntity,
        strings: MutableMap<String, MutableList<String>>,
        booleans: MutableMap<String, Boolean>,
    ) {
        val hands = listOfNotNull(
            entity.equipment[EquipmentSlots.MAIN_HAND],
            entity.equipment[EquipmentSlots.OFF_HAND],
        )
        val armor = EquipmentSlots.ARMOR_SLOTS.mapNotNull(entity.equipment::get)
        val equipped = hands + armor
        booleans["items_none"] = equipped.isEmpty()
        booleans["items_any"] = equipped.isNotEmpty()
        booleans["items_holding"] = hands.isNotEmpty()
        booleans["items_wearing"] = armor.isNotEmpty()
        for (stack in equipped) {
            val identifier = stack.item.identifier
            strings.getOrPut("items", ::mutableListOf).apply {
                add(identifier.toString())
                add(identifier.path)
            }
        }
    }

    private fun appendEntityNbt(
        prefix: String,
        entity: Entity,
        strings: MutableMap<String, MutableList<String>>,
        numbers: MutableMap<String, Double>,
        booleans: MutableMap<String, Boolean>,
    ) {
        val position = entity.physics.positionInfo.position
        val syntheticNbt = linkedMapOf<String, Any>(
            "id" to entity.type.identifier.toString(),
            "Pos" to listOf(position.x, position.y, position.z),
            "CustomName" to entity.name?.message.orEmpty(),
        )
        if (entity is LivingEntity) syntheticNbt["Health"] = entity.health
        if (entity is AgeableMob) syntheticNbt["Age"] = if (entity.isBaby) -1 else 0
        flattenNbt(prefix, syntheticNbt, strings, numbers, booleans)
        synchronized(entity.commandNbt) {
            flattenNbt(prefix, entity.commandNbt, strings, numbers, booleans)
        }
    }

    private fun firstVerticalBlock(entity: Entity, direction: Int, solid: Boolean): BlockState? {
        val position = entity.physics.positionInfo
        if (position.chunk == null) return null
        val world = entity.session.world
        var y = position.position.y
        val limit = if (direction > 0) world.dimension.maxY else world.dimension.minY
        while (if (direction > 0) y <= limit else y >= limit) {
            val state = world[BlockPosition(position.position.x, y, position.position.z)]
            if (state != null && (!solid || BlockStateFlags.FULL_OPAQUE in state.flags)) {
                return state
            }
            y += direction
        }
        return null
    }

    private fun populateBlocks(
        key: String,
        states: Collection<BlockState?>,
        strings: MutableMap<String, MutableList<String>>,
        includeAir: Boolean = false,
    ) {
        for (state in states) {
            if (state == null) {
                if (includeAir) strings.getOrPut(key, ::mutableListOf).apply {
                    add("minecraft:air")
                    add("air")
                }
                continue
            }
            val identifier = state.block.identifier
            strings.getOrPut(key, ::mutableListOf).apply {
                add(identifier.toString())
                add(identifier.path)
                if (state.properties.isNotEmpty()) {
                    val properties = state.properties.entries
                        .sortedBy { it.key.name }
                        .joinToString(":") { "${it.key.name}=${blockPropertyValue(it.value)}" }
                    add("$identifier:$properties")
                    if (identifier.namespace == "minecraft") {
                        add("${identifier.path}:$properties")
                    }
                }
            }
        }
    }

    private fun blockPropertyValue(value: Any): String = when (value) {
        is Enum<*> -> value.name.lowercase()
        else -> value.toString().lowercase()
    }

    private fun variant(entity: Entity): String? {
        return when (entity) {
            is Cat -> entity.variant?.identifier?.path
            is Frog -> entity.variant?.identifier?.path
            is Axolotl -> entity.variant.name.lowercase()
            is Fox -> entity.variant.name.lowercase()
            is Llama -> entity.variant.name.lowercase()
            is Mooshroom -> entity.variant.name.lowercase()
            is Parrot -> entity.variant.name.lowercase()
            is Rabbit -> entity.variant.name.lowercase()
            is TropicalFish -> entity.variant.name.lowercase()
            else -> null
        }
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
    private val CURRENT_BLOCK_KEYS = setOf("block", "blocks", "blockSpawned", "block_spawned")
}
