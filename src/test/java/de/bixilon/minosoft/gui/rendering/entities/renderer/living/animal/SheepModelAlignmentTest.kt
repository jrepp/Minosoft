/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.animal

import de.bixilon.minosoft.util.json.Jackson
import kotlin.test.Test
import kotlin.test.assertEquals

class SheepModelAlignmentTest {
    @Test
    fun `base and wool bodies span the front and hind leg pairs`() {
        val models = mapOf(
            "/assets/minecraft/models/entities/sheep/sheep.smodel" to listOf("body"),
            "/assets/minecraft/models/entities/sheep/sheep_wool.smodel" to listOf("wool_body"),
            "/assets/minecraft/models/entities/sheep/sheep_woolly_preview.smodel" to
                listOf("body", "wool_body"),
        )

        for ((resource, bodies) in models) {
            val root = requireNotNull(javaClass.getResourceAsStream(resource)).use(Jackson.MAPPER::readTree)
            for (body in bodies) {
                val element = root.path("elements").path(body)
                assertEquals(listOf(-4.0, 0.0, -4.0), element.path("from").map { it.asDouble() })
                assertEquals(listOf(4.0, 16.0, 2.0), element.path("to").map { it.asDouble() })
                assertEquals(
                    listOf(0.0, 9.0, -1.0),
                    element.path("rotation").path("origin").map { it.asDouble() },
                )
            }
        }
    }
}
