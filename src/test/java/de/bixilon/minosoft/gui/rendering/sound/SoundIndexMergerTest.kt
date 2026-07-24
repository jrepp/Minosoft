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

import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.sound.sounds.Sound
import de.bixilon.minosoft.gui.rendering.sound.sounds.SoundType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class SoundIndexMergerTest {

    @Test
    fun `pack index preserves unrelated vanilla events`() {
        val merged = SoundIndexMerger.mergeLowToHigh(
            listOf(
                mapOf(
                    "ui.button.click" to event("ui/button/click"),
                    "entity.experience_orb.pickup" to event("random/orb"),
                ),
                mapOf("block.stone.break" to event("block/stone/break1", replace = true)),
            ),
        )

        assertTrue(minecraft("ui.button.click") in merged)
        assertTrue(minecraft("entity.experience_orb.pickup") in merged)
        assertTrue(minecraft("block.stone.break") in merged)
    }

    @Test
    fun `pack sounds append when replace is absent`() {
        val merged = SoundIndexMerger.mergeLowToHigh(
            listOf(
                mapOf("test.event" to event("base")),
                mapOf("test.event" to event("pack")),
            ),
        )

        assertEquals(
            setOf(minecraft("sounds/base.ogg"), minecraft("sounds/pack.ogg")),
            merged.getValue(minecraft("test.event")).sounds.mapTo(mutableSetOf()) { it.path },
        )
    }

    @Test
    fun `pack sounds replace when requested`() {
        val merged = SoundIndexMerger.mergeLowToHigh(
            listOf(
                mapOf("test.event" to event("base")),
                mapOf("test.event" to event("pack", replace = true)),
            ),
        )

        assertEquals(
            setOf(minecraft("sounds/pack.ogg")),
            merged.getValue(minecraft("test.event")).sounds.mapTo(mutableSetOf()) { it.path },
        )
    }

    @Test
    fun `invalid and overflowing sound weights cannot break selection`() {
        val event = minecraft("test.event")
        val first = Sound(event, minecraft("sounds/first.ogg"), weight = Int.MAX_VALUE)
        val second = Sound(event, minecraft("sounds/second.ogg"), weight = Int.MAX_VALUE)
        val invalid = Sound(event, minecraft("sounds/invalid.ogg"), weight = -1)
        val type = SoundType(event, linkedSetOf(first, second, invalid), null)

        assertEquals(Int.MAX_VALUE.toLong() * 2L, type.totalWeight)
        repeat(100) {
            assertTrue(type.getSound(Random(it.toLong())) in setOf(first, second))
        }
        assertNull(SoundType(event, setOf(invalid), null).getSound(Random(0L)))
    }

    private fun event(sound: String, replace: Boolean? = null): Map<String, Any> {
        val data: MutableMap<String, Any> = mutableMapOf("sounds" to listOf(sound))
        replace?.let { data["replace"] = it }
        return data
    }
}
