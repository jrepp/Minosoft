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

package de.bixilon.minosoft.gui.rendering.camera.arm

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.entities.feature.block.BlockMeshBuilder
import de.bixilon.minosoft.gui.rendering.models.item.FlatItemRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemRenderUtil.getModel
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates

class HeldItemRenderer(private val context: RenderContext) {
    private val shader = context.system.shader.create(minosoft("camera/held_item")) { HeldItemShader(it) }
    private var mesh: Mesh? = null
    private var key: MeshKey? = null
    private var display = Mat4f()
    private var flat = false

    fun postInit() {
        shader.load()
    }

    fun draw(entity: PlayerEntity, slot: EquipmentSlots, arm: Arms, perspective: Mat4f) {
        val stack = entity.equipment[slot] ?: return clear()
        val model = stack.item.getModel(context.session) ?: return clear()
        ensureMesh(stack, arm, model)
        val mesh = this.mesh ?: return

        shader.use()
        shader.viewProjectionMatrix = perspective
        shader.matrix = FirstPersonItemTransform.create(arm, display, flat, entity.armSwing.progress(arm))
        shader.tint = ChatColors.WHITE.rgb()
        mesh.draw()
    }

    private fun ensureMesh(stack: ItemStack, arm: Arms, model: ItemRender) {
        val key = MeshKey(stack, arm, model)
        if (this.key == key) return
        clear()

        val builder = BlockMeshBuilder(context)
        model.render(Vec3f.EMPTY, builder, stack, context.tints.getItemTint(stack))
        val mesh = builder.bake()
        mesh.load()

        this.key = key
        this.mesh = mesh
        this.display = when (arm) {
            Arms.RIGHT -> model.getDisplay(FirstPersonItemTransform.displayPosition(arm), stack)?.matrix ?: Mat4f()
            Arms.LEFT -> model.getDisplay(FirstPersonItemTransform.displayPosition(arm), stack)?.matrix
                ?: model.getDisplay(FirstPersonItemTransform.displayPosition(Arms.RIGHT), stack)?.matrix
                    ?.let(FirstPersonItemTransform::mirrorRightHandDisplay)
                ?: Mat4f()
        }
        this.flat = model.isFlat(stack)
    }

    fun unload() {
        clear()
    }

    private fun clear() {
        val mesh = this.mesh
        if (mesh != null) {
            when (mesh.state) {
                MeshStates.PREPARING -> mesh.drop()
                MeshStates.LOADED -> mesh.unload()
                MeshStates.UNLOADED -> Unit
            }
        }
        this.mesh = null
        this.key = null
        this.display = Mat4f()
        this.flat = false
    }

    private data class MeshKey(
        val stack: ItemStack,
        val arm: Arms,
        val model: ItemRender,
    )
}
