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

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.container.stack.properties.NbtProperty
import de.bixilon.minosoft.data.entities.GlobalPosition
import de.bixilon.minosoft.data.entities.entities.player.compass.CompassPosition
import de.bixilon.minosoft.data.registries.dimension.Dimension
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.time.WorldTime
import de.bixilon.minosoft.gui.rendering.RenderingOptions
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ItemPredicateIntegrationTest {

    @Test
    fun `1_20_4 dynamic providers use registry world and entity state`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val overworld = ResourceLocation.of("minecraft:overworld")
            session.world.name = overworld
            session.world.time = WorldTime(time = 12_000, age = 1L)
            session.player.compass = CompassPosition(BlockPosition(10, 0, 0))

            val bundle = ItemStack(
                requireNotNull(session.registries.item[ResourceLocation.of("minecraft:bundle")]),
                nbt = NbtProperty(mapOf(
                    "Items" to listOf(
                        mapOf("id" to "minecraft:ender_pearl", "Count" to 8),
                    ),
                )),
            )
            val runtime = ItemPredicateRuntime()
            val bundleContext = ItemPredicateContext.of(session.player, bundle, runtime)
            assertTrue(predicate("filled", 0.5f).matches(bundle, bundleContext))
            assertFalse(predicate("filled", 0.51f).matches(bundle, bundleContext))

            val armor = ItemStack(
                requireNotNull(session.registries.item[ResourceLocation.of("minecraft:diamond_helmet")]),
                nbt = NbtProperty(mapOf(
                    "Trim" to mapOf(
                        "material" to "minecraft:diamond",
                        "pattern" to "minecraft:sentry",
                    ),
                )),
            )
            val armorContext = ItemPredicateContext.of(session.player, armor, runtime)
            assertTrue(predicate("trim_type", 0.8f).matches(armor, armorContext))
            assertFalse(predicate("trim_type", 0.81f).matches(armor, armorContext))

            val clock = ItemStack(requireNotNull(session.registries.item[ResourceLocation.of("minecraft:clock")]))
            val clockContext = ItemPredicateContext.of(session.player, clock, ItemPredicateRuntime())
            assertTrue(predicate("time", 0.01f).matches(clock, clockContext))
            assertFalse(predicate("time", 0.02f).matches(clock, clockContext))

            val compass = ItemStack(requireNotNull(session.registries.item[ResourceLocation.of("minecraft:compass")]))
            val compassContext = ItemPredicateContext.of(session.player, compass, ItemPredicateRuntime())
            assertTrue(predicate("angle", 0.98f).matches(compass, compassContext))

            session.player.lastDeathPosition = GlobalPosition(
                Dimension(overworld, session.world.dimension),
                BlockPosition(0, 0, 10),
            )
            val recovery = ItemStack(
                requireNotNull(session.registries.item[ResourceLocation.of("minecraft:recovery_compass")]),
            )
            val recoveryContext = ItemPredicateContext.of(session.player, recovery, ItemPredicateRuntime())
            assertTrue(predicate("angle", 0.2f).matches(recovery, recoveryContext))
            assertFalse(predicate("angle", 0.25f).matches(recovery, recoveryContext))
        } finally {
            RenderingOptions.disabled = previous
        }
    }

    private fun predicate(path: String, threshold: Float) =
        ItemPredicate(mapOf(ResourceLocation.of("minecraft:$path") to threshold))
}
