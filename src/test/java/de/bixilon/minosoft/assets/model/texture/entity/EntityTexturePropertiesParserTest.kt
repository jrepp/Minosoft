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
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityTexturePropertiesParserTest {

    @Test
    fun `parses ordered ETF rules and selects a stable weighted variant`() {
        val rules = parse(
            """
            skins.2=4
            biomes.2=minecraft:desert
            skins.1=2 3
            weights.1=1 3
            biomes.1=minecraft:plains
            heights.1=60-80
            baby.1=false
            nbt.1.CustomName=ipattern:*alex*
            """.trimIndent(),
        )
        val context = EntityTextureContext(
            seed = 42L,
            strings = mapOf("biome" to listOf("minecraft:plains"), "nbt.CustomName" to listOf("Alex the Brave")),
            numbers = mapOf("height" to 72.0),
            booleans = mapOf("baby" to false),
        )

        assertEquals(listOf(1, 2), rules.rules.map(EntityTextureRule::index))
        assertEquals(rules.select(context), rules.select(context))
        assertTrue(rules.select(context) in setOf(2, 3))
        assertEquals(
            4,
            rules.select(EntityTextureContext(1L, strings = mapOf("biome" to listOf("minecraft:desert")))),
        )
    }

    @Test
    fun `first matching rule wins and unmatched rules use base texture`() {
        val rules = parse(
            """
            skins.1=2
            biomes.1=minecraft:plains
            skins.2=3
            heights.2=10-20
            """.trimIndent(),
        )

        assertEquals(2, rules.select(EntityTextureContext(0, strings = mapOf("biome" to listOf("minecraft:plains")), numbers = mapOf("height" to 15.0))))
        assertEquals(3, rules.select(EntityTextureContext(0, numbers = mapOf("height" to 15.0))))
        assertEquals(1, rules.select(EntityTextureContext(0)))
    }

    @Test
    fun `custom predicates have removable ownership`() {
        val rules = parse("skins.1=2\nteam.1=blue")
        val context = EntityTextureContext(0)
        val registration = EntityTextureConditions.register("team") { condition, _ -> condition.value == "blue" }
        try {
            assertEquals(2, rules.select(context))
            assertEquals(listOf("team"), EntityTextureConditions.snapshot())
        } finally {
            registration.close()
        }
        assertEquals(1, rules.select(context))
        assertTrue(EntityTextureConditions.snapshot().isEmpty())
    }

    @Test
    fun `selection cache is stable and rejects use after close`() {
        val source = ResourceLocation.of("test:textures/entity/cow.properties")
        val rules = EntityTextureRuleSet(source, listOf(EntityTextureRule(1, listOf(2, 3))))
        val key = EntityTextureCacheKey("cow-1", ResourceLocation.of("minecraft:textures/entity/cow/cow.png"))
        val cache = EntityTextureSelectionCache()

        val selected = cache.select(key, rules, EntityTextureContext(1))
        assertEquals(selected, cache.select(key, rules, EntityTextureContext(999)))
        assertEquals(1, cache.size)
        cache.close()
        assertEquals(0, cache.size)
        assertFailsWith<IllegalStateException> { cache.select(key, rules, EntityTextureContext(1)) }
    }

    @Test
    fun `material selects deterministic blink and emissive frames`() {
        val base = ResourceLocation.of("test:base")
        val emissive = ResourceLocation.of("test:base_e")
        val blink = ResourceLocation.of("test:base_blink")
        val blinkEmissive = ResourceLocation.of("test:base_blink_e")
        val material = EntityTextureMaterial(base, emissive, blink, blinkEmissive, blinkIntervalTicks = 4, blinkLengthTicks = 1)

        val frames = (0L until 4L).map { material.at(it, 7) }
        assertEquals(1, frames.count(EntityTextureMaterialFrame::blinking))
        assertEquals(blink, frames.single(EntityTextureMaterialFrame::blinking).base)
        assertEquals(blinkEmissive, frames.single(EntityTextureMaterialFrame::blinking).emissive)
        assertTrue(frames.filterNot(EntityTextureMaterialFrame::blinking).all { it.base == base && it.emissive == emissive })
        assertFalse(EntityTextureMaterial(base).at(0, 0).blinking)
    }

    @Test
    fun `catalog maps random entity rules to finite variant emissive and blink materials`() {
        val source = ResourceLocation.of("test:optifine/random/entity/cow/cow.properties")
        val rules = EntityTextureRuleSet(source, listOf(EntityTextureRule(1, listOf(2))))
        val base = ResourceLocation.of("test:textures/entity/cow/cow.png")
        val variant = ResourceLocation.of("test:optifine/random/entity/cow/cow2.png")
        val emissive = ResourceLocation.of("test:optifine/random/entity/cow/cow2_e.png")
        val blink = ResourceLocation.of("test:optifine/random/entity/cow/cow2_blink.png")
        val catalog = EntityTextureCatalog.build(
            mapOf(source to rules),
            setOf(base, variant, emissive, blink),
        )

        val entry = catalog[base]!!
        assertEquals(setOf(1, 2), entry.materials.keys)
        assertEquals(variant, entry.materials.getValue(2).base)
        assertEquals(emissive, entry.materials.getValue(2).emissive)
        assertEquals(blink, entry.materials.getValue(2).blink)
        val cache = EntityTextureSelectionCache()
        try {
            val frames = (0L until 100L).map {
                catalog.select(base, "cow-1", EntityTextureContext(7), it, cache)!!
            }
            assertTrue(frames.any(EntityTextureMaterialFrame::blinking))
            assertTrue(frames.any { !it.blinking })
        } finally {
            cache.close()
        }
    }

    private fun parse(content: String): EntityTextureRuleSet {
        return OptifineEntityTexturePropertiesParser.parse(
            ResourceLocation.of("test:textures/entity/cow.properties"),
            ByteArrayInputStream(content.toByteArray()),
        )
    }
}
