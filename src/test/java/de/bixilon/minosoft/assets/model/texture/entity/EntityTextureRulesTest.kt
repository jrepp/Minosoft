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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityTextureRulesTest {

    @Test
    fun `weight totals do not overflow`() {
        val rule = EntityTextureRule(
            index = 1,
            suffixes = listOf(1, 2),
            weights = listOf(Int.MAX_VALUE, Int.MAX_VALUE),
        )

        assertTrue(rule.select(EntityTextureContext(seed = 1)) in rule.suffixes)
    }

    @Test
    fun `pathological regular expressions have bounded matching work`() {
        val condition = EntityTextureCondition("name", "regex:(a+)+")
        val context = EntityTextureContext(
            seed = 1,
            strings = mapOf("name" to listOf("a".repeat(512) + "!")),
        )

        assertFalse(EntityTextureConditions.matches(condition, context))
    }

    @Test
    fun `bounded NBT flattening exposes nested string numeric boolean and list paths`() {
        val strings = linkedMapOf<String, MutableList<String>>()
        val numbers = linkedMapOf<String, Double>()
        val booleans = linkedMapOf<String, Boolean>()
        EntityTextureContextFactory.flattenNbt(
            "nbt",
            mapOf(
                "CustomName" to "Alex",
                "Health" to 12.5f,
                "Silent" to true,
                "HandItems" to listOf(mapOf("id" to "minecraft:stick")),
            ),
            strings,
            numbers,
            booleans,
        )
        val context = EntityTextureContext(1, strings, numbers, booleans)

        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("nbt.CustomName", "ipattern:*alex*"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("nbt.Health", "10-15"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("nbt.Silent", "true"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("nbt.HandItems.0.id", "minecraft:stick"), context))
        assertEquals(12.5, context.number("nbt.Health"))
    }

    @Test
    fun `ETF aliases negated strings semantic versions and health percentages match`() {
        val context = EntityTextureContext(
            seed = 1,
            strings = mapOf(
                "biome" to listOf("minecraft:plains"),
                "minecraft_version" to listOf("1.20.4"),
            ),
            numbers = mapOf(
                "health" to 10.0,
                "health_percent" to 50.0,
                "max_health" to 20.0,
            ),
            booleans = mapOf("client_player" to true),
        )

        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("biomes", "!minecraft:desert"), context))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("biomes", "!minecraft:plains"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("minecraftVersion", "1.19.4-1.20.4"), context))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("minecraftVersion", "1.20.5-1.21"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("health", "45%-55%"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("maxHealth", "20"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("clientPlayer", "true"), context))
    }

    @Test
    fun `ETF equipment gene jump and alternate NBT source predicates match`() {
        val context = EntityTextureContext(
            seed = 1,
            strings = mapOf(
                "items" to listOf("minecraft:diamond_sword", "diamond_sword"),
                "hidden_gene" to listOf("brown"),
                "nbt_client.SelectedItem.id" to listOf("minecraft:carrot"),
                "nbt_vehicle.id" to listOf("minecraft:boat"),
            ),
            numbers = mapOf(
                "jump_strength" to 0.7,
                "llama_inventory" to 4.0,
                "speed" to 0.25,
            ),
            booleans = mapOf(
                "items_none" to false,
                "items_any" to true,
                "items_holding" to true,
                "items_wearing" to false,
            ),
        )

        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("items", "holding"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("item", "minecraft:diamond_sword"), context))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("items", "wearing"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("hiddenGene", "brown"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("jumpHeight", "0.6-0.8"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("llamaInventory", "4"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("maxSpeed", "0.2-0.3"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("nbtClient.SelectedItem.id", "minecraft:carrot"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("nbtVehicle.id", "minecraft:boat"), context))
    }

    @Test
    fun `ETF sole equipment keywords keep their upstream meaning`() {
        val empty = EntityTextureContext(
            seed = 1,
            booleans = mapOf(
                "items_none" to true,
                "items_any" to false,
                "items_holding" to false,
                "items_wearing" to false,
            ),
        )

        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("items", "none"), empty))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("items", "any"), empty))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("items", "none minecraft:stick"), empty))
    }

    @Test
    fun `ETF block predicates accept identifiers regex and state subsets`() {
        val context = EntityTextureContext(
            seed = 1,
            strings = mapOf(
                "blocks" to listOf(
                    "minecraft:oak_log",
                    "oak_log",
                    "minecraft:oak_log:axis=y:waterlogged=false",
                    "oak_log:axis=y:waterlogged=false",
                ),
                "block_spawned" to listOf("minecraft:oak_log", "oak_log"),
            ),
        )

        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("block", "oak_log"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("blocks", "minecraft:oak_log"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("blocks", "oak_log:axis=y"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("blocks", "minecraft:oak_log:axis=y"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("blocks", "oak_log:waterlogged=false:axis=y"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("blocks", "regex:.*oak_log.*"), context))
        assertTrue(EntityTextureConditions.matches(EntityTextureCondition("blockSpawned", "oak_log"), context))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("blocks", "oak_log:axis=x"), context))
        assertFalse(EntityTextureConditions.matches(EntityTextureCondition("blocks", "!oak_log"), context))
    }
}
