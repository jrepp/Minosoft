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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.monster

import de.bixilon.minosoft.util.json.Jackson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ZombieModelResourceTest {

    @Test
    fun `zombie model contains every limb`() {
        val elements = model().path("elements")
        for (name in listOf("head", "body", "left_arm", "right_arm", "left_leg", "right_leg")) {
            assertNotNull(elements.get(name), "Missing zombie element $name")
        }
    }

    @Test
    fun `left limbs reuse populated zombie texture regions`() {
        val elements = model().path("elements")
        assertEquals(elements.path("right_arm").path("uv"), elements.path("left_arm").path("uv"))
        assertEquals(elements.path("right_leg").path("uv"), elements.path("left_leg").path("uv"))
        assertEquals(
            "minecraft:entity/zombie/zombie",
            elements.path("left_arm").path("texture").asText(),
        )
        assertEquals(
            "minecraft:entity/zombie/zombie",
            elements.path("left_leg").path("texture").asText(),
        )
    }

    private fun model() = ZombieModelResourceTest::class.java
        .getResourceAsStream("/assets/minecraft/models/entities/zombie/zombie.smodel")
        .use { Jackson.MAPPER.readTree(requireNotNull(it)) }
}
