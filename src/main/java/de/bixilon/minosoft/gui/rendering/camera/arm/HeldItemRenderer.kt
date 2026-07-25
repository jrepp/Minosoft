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
import de.bixilon.kutil.time.TimeUtil
import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.entities.feature.block.BlockMeshBuilder
import de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.GeckoLibEntityEventConsumer
import de.bixilon.minosoft.gui.rendering.models.item.FlatItemRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateContext
import de.bixilon.minosoft.gui.rendering.models.item.ItemRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemRenderUtil.getModel
import de.bixilon.minosoft.gui.rendering.models.item.resolve
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

class HeldItemRenderer(private val context: RenderContext) {
    private val shader = context.system.shader.create(minosoft("camera/held_item")) { HeldItemShader(it) }
    private var mesh: Mesh? = null
    private var key: MeshKey? = null
    private var display = Mat4f()
    private var flat = false
    private var skeletal: SkeletalInstance? = null
    private var skeletalKey: SkeletalKey? = null
    private var lastSkeletalDraw: ValueTimeMark = TimeUtil.NULL

    fun postInit() {
        shader.load()
    }

    fun draw(entity: PlayerEntity, slot: EquipmentSlots, arm: Arms, perspective: Mat4f) {
        val stack = entity.equipment[slot] ?: return clear()
        val model = stack.item.getModel(context.session)?.resolve(
            stack,
            ItemPredicateContext.of(entity, stack, context.itemPredicates),
        )
        val skeletalName = context.models.skeletal.contentModel(GeckoLibModelTarget.ITEM, stack.item.identifier)
        val skeletalModel = skeletalName?.let(context.models.skeletal::get)
        if (skeletalModel != null) {
            drawSkeletal(entity, stack, arm, perspective, model, skeletalModel)
            return
        }
        clearSkeletal()
        model ?: return clear()
        ensureMesh(stack, arm, model)
        val mesh = this.mesh ?: return

        shader.use()
        shader.viewProjectionMatrix = perspective
        shader.matrix = FirstPersonItemTransform.create(arm, display, flat, entity.armSwing.progress(arm))
        shader.tint = ChatColors.WHITE.rgb()
        mesh.draw()
    }

    private fun drawSkeletal(
        entity: PlayerEntity,
        stack: ItemStack,
        arm: Arms,
        perspective: Mat4f,
        vanilla: ItemRender?,
        model: BakedSkeletalModel,
    ) {
        clearMesh()
        val key = SkeletalKey(stack, arm, model)
        if (skeletalKey != key) {
            clearSkeletal()
            val instance = model.createInstance(context)
            val renderer = entity.renderer
            if (renderer != null) {
                val events = GeckoLibEntityEventConsumer(renderer, instance)
                instance.neutralAnimation.eventConsumer = events::dispatch
                instance.geckoAnimation.eventConsumer = events::dispatch
            }
            instance.load()
            skeletal = instance
            skeletalKey = key
            display = display(vanilla, stack, arm)
            flat = vanilla?.isFlat(stack) == true
        }
        val instance = skeletal ?: return
        val time = now()
        val delta = if (lastSkeletalDraw == TimeUtil.NULL) Duration.ZERO else time - lastSkeletalDraw
        lastSkeletalDraw = time
        instance.transform.reset()
        instance.animation.draw(delta)
        val velocity = entity.physics.velocity
        val state = GeckoLibAnimationState(
            ageSeconds = entity.age.coerceAtLeast(0) / 20.0f,
            moving = velocity.x * velocity.x + velocity.z * velocity.z > MOVEMENT_EPSILON_SQUARED,
            data = mapOf(
                "query.is_on_ground" to if (entity.physics.onGround) 1.0 else 0.0,
                "query.is_in_water" to if (entity.physics.inWater) 1.0 else 0.0,
            ),
        )
        instance.geckoAnimation.updateState(state)
        if (instance.geckoAnimation.active) {
            instance.geckoAnimation.draw(delta, state)
        } else {
            instance.neutralAnimation.draw(delta)
        }
        instance.matrix.set(FirstPersonItemTransform.create(arm, display, flat, entity.armSwing.progress(arm)))
        instance.transform.transform(instance.matrix.unsafe)
        if (instance.geckoAnimation.active) {
            instance.geckoAnimation.dispatchEvents()
        } else {
            instance.neutralAnimation.dispatchEvents()
        }

        val shader = context.skeletal.shader
        shader.viewProjectionMatrix = perspective
        instance.draw(ChatColors.WHITE.rgb())
        drawSkeletalLayers(instance, shader)
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
        this.display = display(model, stack, arm)
        this.flat = model.isFlat(stack)
    }

    private fun display(model: ItemRender?, stack: ItemStack, arm: Arms): Mat4f {
        model ?: return Mat4f()
        return when (arm) {
            Arms.RIGHT -> model.getDisplay(FirstPersonItemTransform.displayPosition(arm), stack)?.matrix ?: Mat4f()
            Arms.LEFT -> model.getDisplay(FirstPersonItemTransform.displayPosition(arm), stack)?.matrix
                ?: model.getDisplay(FirstPersonItemTransform.displayPosition(Arms.RIGHT), stack)?.matrix
                    ?.let(FirstPersonItemTransform::mirrorRightHandDisplay)
                ?: Mat4f()
        }
    }

    fun unload() {
        clear()
    }

    private fun clear() {
        clearMesh()
        clearSkeletal()
        this.display = Mat4f()
        this.flat = false
    }

    private fun clearMesh() {
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
    }

    private fun clearSkeletal() {
        val skeletal = this.skeletal
        this.skeletal = null
        this.skeletalKey = null
        this.lastSkeletalDraw = TimeUtil.NULL
        if (skeletal != null) {
            skeletal.neutralAnimation.clearEvents()
            skeletal.geckoAnimation.clearEvents()
            when (skeletal.state) {
                SkeletalModelStates.PREPARING -> skeletal.drop()
                SkeletalModelStates.LOADED -> skeletal.unload()
                SkeletalModelStates.UNLOADED -> Unit
            }
        }
    }

    private fun drawSkeletalLayers(
        instance: SkeletalInstance,
        shader: de.bixilon.minosoft.gui.rendering.skeletal.shader.SkeletalShader,
    ) {
        if (instance.model.geckoRenderLayers.isEmpty()) return
        try {
            for ((name, layer) in instance.model.geckoRenderLayers) {
                instance.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                when (layer.blend) {
                    GeckoLibRenderLayerBlend.OPAQUE -> context.system.reset(
                        faceCulling = false,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.TRANSLUCENT -> context.system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.ADDITIVE -> context.system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE,
                        depth = DepthFunctions.EQUAL,
                    )
                }
                shader.use()
                shader.tint = ChatColors.WHITE.rgb()
                instance.drawMesh(shader, layer.mesh)
            }
        } finally {
            context.system.reset()
        }
    }

    private data class MeshKey(
        val stack: ItemStack,
        val arm: Arms,
        val model: ItemRender,
    )

    private data class SkeletalKey(
        val stack: ItemStack,
        val arm: Arms,
        val model: BakedSkeletalModel,
    )

    private companion object {
        const val MOVEMENT_EPSILON_SQUARED = 1.0E-7
    }
}
