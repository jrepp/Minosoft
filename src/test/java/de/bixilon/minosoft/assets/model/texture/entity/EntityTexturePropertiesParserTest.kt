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
    fun `NBT predicates support existence ranges wildcard paths and inversion`() {
        val context = EntityTextureContext(
            seed = 0,
            strings = mapOf(
                "nbt.Items.0.id" to listOf("minecraft:diamond"),
                "nbt.Items.1.id" to listOf("minecraft:stick"),
            ),
            numbers = mapOf("nbt.Health" to 18.0),
        )

        assertTrue(EntityTextureConditions.matchesNbt("Items.*.id", "ipattern:minecraft:d*", context))
        assertTrue(EntityTextureConditions.matchesNbt("Health", "range:10-20", context))
        assertTrue(EntityTextureConditions.matchesNbt("Health", "exists:true", context))
        assertTrue(EntityTextureConditions.matchesNbt("Missing", "exists:false", context))
        assertTrue(EntityTextureConditions.matchesNbt("Missing", "!exists:true", context))
        assertFalse(EntityTextureConditions.matchesNbt("Items.*.id", "raw:minecraft:apple", context))
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
    fun `selection cache exposes prior rule and suffix to dependent feature textures`() {
        val bodySource = ResourceLocation.of("test:textures/entity/horse.properties")
        val armorSource = ResourceLocation.of("test:textures/entity/horse_armor.properties")
        val bodyRules = EntityTextureRuleSet(bodySource, listOf(EntityTextureRule(4, listOf(2))))
        val armorRules = EntityTextureRuleSet(
            armorSource,
            listOf(
                EntityTextureRule(
                    7,
                    listOf(3),
                    conditions = listOf(
                        EntityTextureCondition("textureRule", "4"),
                        EntityTextureCondition("textureSuffix", "2"),
                    ),
                ),
            ),
        )
        val bodyKey = EntityTextureCacheKey("horse-1", ResourceLocation.of("minecraft:textures/entity/horse/horse.png"))
        val armorKey = EntityTextureCacheKey("horse-1", ResourceLocation.of("minecraft:textures/entity/horse/armor.png"))
        val cache = EntityTextureSelectionCache()
        try {
            assertEquals(2, cache.select(bodyKey, bodyRules, EntityTextureContext(7)))
            assertEquals(3, cache.select(armorKey, armorRules, EntityTextureContext(7)))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `selection cache evicts least recently used entities per texture`() {
        val source = ResourceLocation.of("test:textures/entity/cow.properties")
        val texture = ResourceLocation.of("minecraft:textures/entity/cow/cow.png")
        val rules = EntityTextureRuleSet(source, listOf(EntityTextureRule(1, listOf(2))))
        val replacementRules = EntityTextureRuleSet(source, listOf(EntityTextureRule(1, listOf(9))))
        val cache = EntityTextureSelectionCache(capacity = 2)
        try {
            cache.select(EntityTextureCacheKey("cow-1", texture), rules, EntityTextureContext(1))
            cache.select(EntityTextureCacheKey("cow-2", texture), rules, EntityTextureContext(2))
            cache.select(EntityTextureCacheKey("cow-1", texture), rules, EntityTextureContext(3))
            cache.select(EntityTextureCacheKey("cow-3", texture), rules, EntityTextureContext(4))

            assertEquals(2, cache.size)
            assertEquals(9, cache.select(EntityTextureCacheKey("cow-2", texture), replacementRules, EntityTextureContext(5)))
            cache.invalidate(EntityTextureCacheKey("cow-3", texture))
            assertEquals(1, cache.size)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `material selects deterministic blink and emissive frames`() {
        val base = ResourceLocation.of("test:base")
        val emissive = ResourceLocation.of("test:base_e")
        val blink = ResourceLocation.of("test:base_blink")
        val blinkEmissive = ResourceLocation.of("test:base_blink_e")
        val blink2 = ResourceLocation.of("test:base_blink2")
        val blink2Emissive = ResourceLocation.of("test:base_blink2_e")
        val material = EntityTextureMaterial(
            base,
            emissive,
            blink,
            blinkEmissive,
            blink2,
            blink2Emissive,
            blinkFrequencyTicks = 1,
            blinkLengthTicks = 1,
        )

        val frames = (0L until 4L).map { material.at(it, 7) }
        assertEquals(EntityTextureBlinkState.HALF, frames[0].blinkState)
        assertEquals(blink2, frames[0].base)
        assertEquals(blink2Emissive, frames[0].emissive)
        assertEquals(EntityTextureBlinkState.CLOSED, frames[1].blinkState)
        assertEquals(blink, frames[1].base)
        assertEquals(blinkEmissive, frames[1].emissive)
        assertEquals(EntityTextureBlinkState.OPEN, frames[3].blinkState)
        assertEquals(base, frames[3].base)
        assertEquals(emissive, frames[3].emissive)
        assertFalse(EntityTextureMaterial(base).at(0, 0).blinking)
    }

    @Test
    fun `material properties parse bounded timing and configured emissive suffixes`() {
        val source = ResourceLocation.of("test:textures/entity/cow/cow_blink.properties")
        val timing = EntityTextureMaterialPropertiesParser.parseBlink(
            source,
            ByteArrayInputStream("blinkFrequency=37\nblinkLength=4".toByteArray()),
        )
        assertEquals(EntityTextureBlinkSettings(37, 4), timing)
        assertEquals(
            setOf("_glow"),
            EntityTextureMaterialPropertiesParser.parseEmissiveSuffixes(
                ResourceLocation.of("test:optifine/emissive.properties"),
                ByteArrayInputStream("suffix.emissive=_glow".toByteArray()),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            EntityTextureMaterialPropertiesParser.parseBlink(
                source,
                ByteArrayInputStream("blinkLength=999".toByteArray()),
            )
        }
    }

    @Test
    fun `catalog discovers standalone feature material with custom emissive and half blink frames`() {
        val base = ResourceLocation.of("test:textures/models/armor/coat.png")
        val emissive = ResourceLocation.of("test:textures/models/armor/coat_glow.png")
        val blink = ResourceLocation.of("test:textures/models/armor/coat_blink.png")
        val blink2 = ResourceLocation.of("test:textures/models/armor/coat_blink2.png")
        val blinkEmissive = ResourceLocation.of("test:textures/models/armor/coat_blink_glow.png")
        val blink2Emissive = ResourceLocation.of("test:textures/models/armor/coat_blink2_glow.png")
        val settingsLocation = EntityTextureCatalog.blinkPropertiesLocation(base)
        val catalog = EntityTextureCatalog.build(
            emptyMap(),
            setOf(base, emissive, blink, blink2, blinkEmissive, blink2Emissive),
            emissiveSuffixes = setOf("_glow"),
            blinkSettings = mapOf(settingsLocation to EntityTextureBlinkSettings(20, 3)),
        )

        val material = catalog[base]!!.materials.getValue(1)
        assertEquals(emissive, material.emissive)
        assertEquals(blink, material.blink)
        assertEquals(blink2, material.blink2)
        assertEquals(blinkEmissive, material.blinkEmissive)
        assertEquals(blink2Emissive, material.blink2Emissive)
        assertEquals(20, material.blinkFrequencyTicks)
        assertEquals(3, material.blinkLengthTicks)
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

    @Test
    fun `catalog requests only predicates used by the selected base textures`() {
        val cowSource = ResourceLocation.of("test:optifine/random/entity/cow/cow.properties")
        val pigSource = ResourceLocation.of("test:optifine/random/entity/pig/pig.properties")
        val cowBase = ResourceLocation.of("test:textures/entity/cow/cow.png")
        val pigBase = ResourceLocation.of("test:textures/entity/pig/pig.png")
        val catalog = EntityTextureCatalog.build(
            mapOf(
                cowSource to EntityTextureRuleSet(
                    cowSource,
                    listOf(
                        EntityTextureRule(
                            1,
                            listOf(1),
                            conditions = listOf(
                                EntityTextureCondition("blockAbove", "minecraft:stone"),
                                EntityTextureCondition("biomeTag", "minecraft:is_forest"),
                            ),
                        ),
                    ),
                ),
                pigSource to EntityTextureRuleSet(
                    pigSource,
                    listOf(
                        EntityTextureRule(
                            1,
                            listOf(1),
                            conditions = listOf(EntityTextureCondition("blockBelowSolid", "minecraft:dirt")),
                        ),
                    ),
                ),
            ),
            setOf(cowBase, pigBase),
        )

        assertEquals(setOf("blockAbove", "biomeTag"), catalog.contextKeys(setOf(cowBase)))
        assertEquals(setOf("blockBelowSolid"), catalog.contextKeys(setOf(pigBase)))
        assertTrue(catalog.contextKeys(setOf(ResourceLocation.of("test:textures/entity/sheep/sheep.png"))).isEmpty())
    }

    private fun parse(content: String): EntityTextureRuleSet {
        return OptifineEntityTexturePropertiesParser.parse(
            ResourceLocation.of("test:textures/entity/cow.properties"),
            ByteArrayInputStream(content.toByteArray()),
        )
    }
}
