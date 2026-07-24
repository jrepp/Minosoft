/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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
import de.bixilon.minosoft.gui.rendering.models.raw.display.ModelDisplay
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.models.block.state.apply.SingleBlockStateApply

class ItemModelPrototype(
    private var layers: Array<Texture>?,
    override val particle: Texture?,
    private val display: Map<DisplayPositions, ModelDisplay>?,
    private var overrides: List<ItemModelOverridePrototype> = emptyList(),
    private val block: SingleBlockStateApply? = null,
) : ItemRender {
    constructor(block: SingleBlockStateApply) : this(
        layers = null,
        particle = null,
        display = block.model.display,
        block = block,
    )
    override fun render(gui: GUIRenderer, offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?, size: Vec2f, stack: ItemStack, tints: RGBArray?) = prototype()
    override fun render(offset: Vec3f, consumer: BlockVertexConsumer, stack: ItemStack, tints: RGBArray?) = prototype()


    private fun prototype(): Nothing = throw IllegalStateException("prototype")

    override fun getDisplay(position: DisplayPositions, stack: ItemStack?): ModelDisplay? = display?.get(position)

    fun bake(): ItemRender {
        val base = block?.bake() ?: FlatItemRender(
            requireNotNull(this.layers) { "Item model prototype has neither layered nor element geometry." },
            this.particle,
            this.display,
        )
        if (overrides.isEmpty()) return base
        return PredicateItemRender(base, overrides.map { ItemModelOverrideRender(it.predicate, it.model.bake()) })
    }

    fun withOverrides(overrides: List<ItemModelOverridePrototype>): ItemModelPrototype {
        this.overrides = overrides
        return this
    }
}

data class ItemModelOverridePrototype(
    val predicate: ItemPredicate,
    val model: ItemModelPrototype,
)
