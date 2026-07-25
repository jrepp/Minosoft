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

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.container.stack.properties.DurabilityProperty
import de.bixilon.minosoft.data.container.stack.properties.NbtProperty
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.DurableItem
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ItemPredicateTest {

    @Test
    fun `custom model data uses the last matching threshold override`() {
        val item = object : Item(ResourceLocation.of("test:wand")) {}
        val base = StubRender()
        val low = StubRender()
        val high = StubRender()
        val render = PredicateItemRender(
            base,
            listOf(
                ItemModelOverrideRender(ItemPredicate(mapOf(ResourceLocation.of("minecraft:custom_model_data") to 10.0f)), low),
                ItemModelOverrideRender(ItemPredicate(mapOf(ResourceLocation.of("minecraft:custom_model_data") to 20.0f)), high),
            ),
        )

        assertSame(base, render.select(ItemStack(item)))
        assertSame(low, render.select(ItemStack(item, nbt = NbtProperty(mapOf("CustomModelData" to 15)))))
        assertSame(high, render.select(ItemStack(item, nbt = NbtProperty(mapOf("CustomModelData" to 25)))))
    }

    @Test
    fun `unknown predicates never match even below zero`() {
        val stack = ItemStack(item("wand"))
        val key = ResourceLocation.of("other:missing")
        val predicate = ItemPredicate(mapOf(key to -100.0f))

        assertFalse(predicate.matches(stack))
        assertTrue(predicate.matches(stack, ItemPredicateContext(values = mapOf(key to -50.0f))))
    }

    @Test
    fun `stack backed 1_20_4 properties retain vanilla item gates`() {
        val charged = ItemStack(
            item("crossbow"),
            nbt = NbtProperty(mapOf(
                "Charged" to 1,
                "ChargedProjectiles" to listOf(mapOf("id" to "minecraft:firework_rocket")),
            )),
        )
        assertTrue(predicate("charged", 1.0f).matches(charged))
        assertTrue(predicate("firework", 1.0f).matches(charged))
        assertFalse(predicate("pulling", 1.0f).matches(charged, ItemPredicateContext(active = true)))
        assertFalse(predicate("charged", 0.0f).matches(ItemStack(item("bow"))))

        val light = ItemStack(item("light"), nbt = NbtProperty(mapOf("BlockStateTag" to mapOf("level" to "12"))))
        assertTrue(predicate("level", 0.75f).matches(light))

        val elytra = object : Item(ResourceLocation.of("minecraft:elytra")), DurableItem {
            override val maxDurability = 432
        }
        assertTrue(predicate("broken", 1.0f).matches(ItemStack(elytra, durability = DurabilityProperty(1))))
        assertFalse(predicate("broken", 1.0f).matches(ItemStack(elytra, durability = DurabilityProperty(2))))
    }

    @Test
    fun `bundle filled uses nested stack occupancy and clamps malformed overflow`() {
        val bundle = ItemStack(
            item("bundle"),
            nbt = NbtProperty(mapOf(
                "Items" to listOf(
                    mapOf("id" to "minecraft:stone", "Count" to 16),
                    mapOf("id" to "minecraft:dirt", "Count" to 16),
                ),
            )),
        )
        assertTrue(predicate("filled", 0.5f).matches(bundle))
        assertFalse(predicate("filled", 0.51f).matches(bundle))

        val overflowing = ItemStack(
            item("bundle"),
            nbt = NbtProperty(mapOf(
                "Items" to listOf(mapOf("id" to "minecraft:stone", "Count" to 1000)),
            )),
        )
        assertTrue(predicate("filled", 1.0f).matches(overflowing))
        assertFalse(predicate("filled", 0.0f).matches(ItemStack(item("stick"))))
    }

    @Test
    fun `render context drives active hand and pull thresholds`() {
        val stack = ItemStack(item("bow"))
        val base = StubRender()
        val pulling = StubRender()
        val pulled = StubRender()
        val render = PredicateItemRender(
            base,
            listOf(
                ItemModelOverrideRender(predicate("pulling", 1.0f), pulling),
                ItemModelOverrideRender(predicate("pull", 0.65f), pulled),
            ),
        )

        assertSame(base, render.select(stack, ItemPredicateContext(active = false, useTicks = 20)))
        assertSame(pulling, render.select(stack, ItemPredicateContext(active = true, useTicks = 5)))
        assertSame(pulled, render.select(stack, ItemPredicateContext(active = true, useTicks = 13)))
    }

    @Test
    fun `context properties compose and last matching override wins`() {
        val stack = ItemStack(item("shield"))
        val base = StubRender()
        val active = StubRender()
        val activeLeft = StubRender()
        val render = PredicateItemRender(
            base,
            listOf(
                ItemModelOverrideRender(predicate("blocking", 1.0f), active),
                ItemModelOverrideRender(
                    ItemPredicate(mapOf(
                        ResourceLocation.of("minecraft:blocking") to 1.0f,
                        ResourceLocation.of("minecraft:lefthanded") to 1.0f,
                    )),
                    activeLeft,
                ),
            ),
        )

        assertSame(base, render.select(stack))
        assertSame(active, render.select(stack, ItemPredicateContext(active = true)))
        assertSame(activeLeft, render.select(stack, ItemPredicateContext(active = true, leftHanded = true)))
    }

    private fun item(path: String) = object : Item(ResourceLocation.of("minecraft:$path")) {}

    private fun predicate(path: String, threshold: Float) =
        ItemPredicate(mapOf(ResourceLocation.of("minecraft:$path") to threshold))

    private class StubRender : ItemRender {
        override fun render(gui: GUIRenderer, offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?, size: Vec2f, stack: ItemStack, tints: RGBArray?) = Unit
        override fun render(offset: Vec3f, consumer: BlockVertexConsumer, stack: ItemStack, tints: RGBArray?) = Unit
    }
}
