/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.datapack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnbtParserTest {

    @Test
    fun `parses Animated Java display passenger data`() {
        val result = SnbtParser.compound(
            """{id:"minecraft:item_display",Tags:['aj.global.entity','demo.rig.root'],item:{id:'minecraft:carrot_on_a_stick',Count:1b,tag:{CustomModelData:42}},transformation:{translation:[0f,1.5f,0f],left_rotation:[0f,0f,0f,1f]},UUID:[I;1,2,3,4]}""",
        )

        assertEquals("minecraft:item_display", result["id"])
        assertEquals(listOf("aj.global.entity", "demo.rig.root"), result["Tags"])
        val item = result["item"] as Map<*, *>
        assertEquals(1.toByte(), item["Count"])
        assertEquals(42, (item["tag"] as Map<*, *>)["CustomModelData"])
        assertEquals(listOf(1, 2, 3, 4), result["UUID"])
        assertTrue(((result["transformation"] as Map<*, *>)["translation"] as List<*>)[1] is Float)
        assertEquals(result, SnbtParser.compound(SnbtParser.stringify(result)))
    }

    @Test
    fun `accepts trailing collection commas emitted by Animated Java`() {
        assertEquals(
            mapOf("passengers" to listOf(mapOf("id" to "minecraft:item_display"))),
            SnbtParser.compound(
                """{passengers:[{id:"minecraft:item_display",},],}""",
            ),
        )
    }

    @Test
    fun `rejects trailing and unbalanced input`() {
        assertFailsWith<IllegalArgumentException> { SnbtParser.parse("{a:1} trailing") }
        assertFailsWith<IllegalArgumentException> { SnbtParser.parse("{a:[1,2}") }
        assertFailsWith<IllegalArgumentException> {
            SnbtParser.parse("[".repeat(65) + "0" + "]".repeat(65))
        }
    }
}
