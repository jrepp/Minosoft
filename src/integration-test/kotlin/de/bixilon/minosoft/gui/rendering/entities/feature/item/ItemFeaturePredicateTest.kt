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

package de.bixilon.minosoft.gui.rendering.entities.feature.item

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.entities.player.Hands
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.data.world.time.WorldTime
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.item.ItemModelOverrideRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemPredicate
import de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateCatalog
import de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateContext
import de.bixilon.minosoft.gui.rendering.models.item.ItemRender
import de.bixilon.minosoft.gui.rendering.models.item.PredicateItemRender
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import org.testng.Assert.assertEquals
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration

class ItemFeaturePredicateTest {

    @Test
    fun `remote living item use retains elapsed ticks and resets with tracked state`() {
        for (version in listOf("1.19.4", "1.20.4")) {
            verifyRemoteItemUse(version)
        }
    }

    private fun verifyRemoteItemUse(version: String) {
        val renderer = create(version = version).create(Pig)
        val entity = renderer.entity
        val bow = requireNotNull(entity.session.registries.item[ResourceLocation.of("minecraft:bow")])
        val first = ItemStack(bow)
        entity.equipment[Hands.MAIN.slot] = first
        entity.data[LivingEntity.FLAGS_DATA] = 0x01

        assertEquals(ItemPredicateContext.of(entity, first).useTicks, 0)
        entity.forceTick()
        entity.forceTick()
        val active = ItemPredicateContext.of(entity, first)
        assertSame(
            active.catalog,
            if (version == "1.20.4") ItemPredicateCatalog.V1_20_4 else ItemPredicateCatalog.V1_19_4,
        )
        assertEquals(active.useTicks, 2)
        assertSame(
            PredicateItemRender(
                NoOpItemRender,
                listOf(
                    ItemModelOverrideRender(
                        ItemPredicate(mapOf(ResourceLocation.of("minecraft:pull") to 0.1f)),
                        ActiveItemRender,
                    ),
                ),
            ).select(first, active),
            ActiveItemRender,
        )

        val replacement = ItemStack(bow)
        entity.equipment[Hands.MAIN.slot] = replacement
        entity.forceTick()
        assertEquals(ItemPredicateContext.of(entity, replacement).useTicks, 1)
        assertEquals(ItemPredicateContext.of(entity, first).useTicks, 0)

        val offhand = ItemStack(bow)
        entity.equipment[Hands.OFF.slot] = offhand
        entity.data[LivingEntity.FLAGS_DATA] = 0x03
        entity.forceTick()
        assertEquals(ItemPredicateContext.of(entity, offhand).useTicks, 1)
        assertEquals(ItemPredicateContext.of(entity, replacement).useTicks, 0)

        entity.data[LivingEntity.FLAGS_DATA] = 0x00
        entity.forceTick()
        assertEquals(entity.itemUseTicks, 0)
        assertEquals(ItemPredicateContext.of(entity, offhand).useTicks, 0)

        if (version == "1.20.4") {
            val brush = ItemStack(
                requireNotNull(entity.session.registries.item[ResourceLocation.of("minecraft:brush")]),
            )
            entity.equipment[Hands.MAIN.slot] = brush
            entity.data[LivingEntity.FLAGS_DATA] = 0x01
            entity.forceTick()
            entity.forceTick()
            assertTrue(
                ItemPredicate(mapOf(ResourceLocation.of("minecraft:brushing") to 0.8f))
                    .matches(brush, ItemPredicateContext.of(entity, brush)),
            )
        }
    }

    @Test
    fun `item feature reselects a clock override without replacing the stack`() {
        val entities = create()
        val renderer = entities.create(Pig)
        renderer.distance2 = 0.0
        val session = entities.session
        session.world.name = ResourceLocation.of("minecraft:overworld")
        session.world.time = WorldTime(time = 12_000, age = 1L)

        val clock = requireNotNull(session.registries.item[ResourceLocation.of("minecraft:clock")])
        val previousModel = clock.model
        val base = RecordingItemRender()
        val override = RecordingItemRender()
        clock.model = PredicateItemRender(
            base,
            listOf(
                ItemModelOverrideRender(
                    ItemPredicate(mapOf(ResourceLocation.of("minecraft:time") to 0.5f)),
                    override,
                ),
            ),
        )

        try {
            val stack = ItemStack(clock)
            ItemFeature(renderer, stack, DisplayPositions.FIXED, many = false).also { feature ->
                feature.update(Duration.ZERO)
                feature.enqueueUnload()
                assertEquals(base.blockRenders, 1)

                session.world.time = WorldTime(time = 0, age = 2L)
                feature.update(Duration.ZERO)
                feature.enqueueUnload()
                session.world.time = WorldTime(time = 0, age = 3L)
                feature.update(Duration.ZERO)
                feature.enqueueUnload()
                feature.update(Duration.ZERO)

                assertSame(feature.stack, stack, "selection must change without replacing the item stack")
                assertEquals(base.blockRenders, 1, "the stale model must not be rebuilt")
                assertEquals(override.blockRenders, 1, "the selected override must rebuild the mesh")
                feature.unload()
            }
        } finally {
            clock.model = previousModel
        }
    }

    private class RecordingItemRender : ItemRender {
        var blockRenders = 0

        override fun render(
            gui: GUIRenderer,
            offset: Vec2f,
            consumer: GuiVertexConsumer,
            options: GUIVertexOptions?,
            size: Vec2f,
            stack: ItemStack,
            tints: RGBArray?,
        ) = Unit

        override fun render(
            offset: Vec3f,
            consumer: BlockVertexConsumer,
            stack: ItemStack,
            tints: RGBArray?,
        ) {
            blockRenders++
        }
    }

    private object NoOpItemRender : ItemRender by RecordingItemRender()
    private object ActiveItemRender : ItemRender by RecordingItemRender()
}
