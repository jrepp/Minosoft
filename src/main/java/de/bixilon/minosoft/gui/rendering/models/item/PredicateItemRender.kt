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
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions

data class ItemModelOverrideRender(
    val predicate: ItemPredicate,
    val model: ItemRender,
)

class PredicateItemRender(
    private val base: ItemRender,
    private val overrides: List<ItemModelOverrideRender>,
) : ItemRender {
    override val particle get() = base.particle

    fun select(stack: ItemStack): ItemRender = overrides.lastOrNull { it.predicate.matches(stack) }?.model ?: base

    override fun render(gui: GUIRenderer, offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?, size: Vec2f, stack: ItemStack, tints: RGBArray?) {
        select(stack).render(gui, offset, consumer, options, size, stack, tints)
    }

    override fun render(offset: Vec3f, consumer: BlockVertexConsumer, stack: ItemStack, tints: RGBArray?) {
        select(stack).render(offset, consumer, stack, tints)
    }

    override fun getDisplay(position: DisplayPositions, stack: ItemStack?) =
        stack?.let { select(it).getDisplay(position, it) } ?: base.getDisplay(position)

    override fun isFlat(stack: ItemStack) = select(stack).isFlat(stack)
}
