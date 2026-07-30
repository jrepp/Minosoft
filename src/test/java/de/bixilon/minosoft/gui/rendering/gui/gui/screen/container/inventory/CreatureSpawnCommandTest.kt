/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.commands.nodes.LiteralNode
import de.bixilon.minosoft.commands.nodes.RootNode
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreatureSpawnCommandTest {
    private val creature = ResourceLocation.of("naturalist:rattlesnake")

    @Test
    fun `spawn target is three blocks ahead at player elevation`() {
        assertEquals(
            "/summon naturalist:rattlesnake 10.000 64.000 23.000",
            CreatureSpawnCommand.create(
                creature,
                Vec3d(10.0, 64.0, 20.0),
                EntityRotation(yaw = 0.0f, pitch = 65.0f),
            ),
        )
        assertEquals(
            "/summon naturalist:rattlesnake 7.000 64.000 20.000",
            CreatureSpawnCommand.create(
                creature,
                Vec3d(10.0, 64.0, 20.0),
                EntityRotation(yaw = 90.0f, pitch = -45.0f),
            ),
        )
    }

    @Test
    fun `spawn target rejects invalid distance`() {
        assertThrows<IllegalArgumentException> {
            CreatureSpawnCommand.create(creature, Vec3d.EMPTY, EntityRotation.EMPTY, Double.NaN)
        }
        assertThrows<IllegalArgumentException> {
            CreatureSpawnCommand.create(creature, Vec3d.EMPTY, EntityRotation.EMPTY, -1.0)
        }
    }

    @Test
    fun `sheep variants append only fixed summon state`() {
        val sheep = ResourceLocation.of("minecraft:sheep")
        assertEquals(
            "/summon minecraft:sheep 0.000 0.000 3.000 {Sheared:0b}",
            CreatureSpawnCommand.create(
                sheep,
                Vec3d.EMPTY,
                EntityRotation.EMPTY,
                variant = CreatureSpawnVariant.SHEEP_WOOLLY,
            ),
        )
        assertEquals(
            "/summon minecraft:sheep 0.000 0.000 3.000 {Sheared:1b}",
            CreatureSpawnCommand.create(
                sheep,
                Vec3d.EMPTY,
                EntityRotation.EMPTY,
                variant = CreatureSpawnVariant.SHEEP_SHEARED,
            ),
        )
    }

    @Test
    fun `direct command lookup reflects server authority and aliases`() {
        val root = RootNode()
            .addChild(LiteralNode("help"))
            .addChild(LiteralNode("summon", aliases = setOf("spawn")))

        assertTrue(root.hasDirectChild("summon"))
        assertTrue(root.hasDirectChild("spawn"))
        assertFalse(root.hasDirectChild("give"))
        assertFalse(root.hasDirectChild(""))
    }
}
