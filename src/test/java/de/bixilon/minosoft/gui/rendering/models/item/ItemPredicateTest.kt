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
import de.bixilon.minosoft.data.container.stack.properties.NbtProperty
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import kotlin.test.Test
import kotlin.test.assertSame

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

    private class StubRender : ItemRender {
        override fun render(gui: GUIRenderer, offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?, size: Vec2f, stack: ItemStack, tints: RGBArray?) = Unit
        override fun render(offset: Vec3f, consumer: BlockVertexConsumer, stack: ItemStack, tints: RGBArray?) = Unit
    }
}
