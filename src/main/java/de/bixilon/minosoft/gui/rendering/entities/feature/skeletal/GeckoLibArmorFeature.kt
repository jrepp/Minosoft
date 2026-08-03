/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.f.MVec3f
import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.Colors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.entities.feature.DrawableEntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.ContentModelReloadable
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.GeckoLibAnimationManagerSnapshot
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDrawState
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import java.util.EnumMap
import kotlin.time.Duration

/**
 * Renders source-native GeckoLib armor geometry for equipped armor item routes.
 * Each slot owns an independent controller instance and content-generation
 * lease, matching GeckoLib's per-animatable state model.
 */
class GeckoLibArmorFeature(
    private val livingRenderer: LivingEntityRenderer<*>,
) : DrawableEntityRenderFeature(livingRenderer, EntityRenderStateKeys.GECKO_ARMOR), ContentModelReloadable, EntityOutlineFeature {
    override val castsShadow get() = true
    private val entries = EnumMap<EquipmentSlots, Entry>(EquipmentSlots::class.java)
    private val pending = EnumMap<EquipmentSlots, GeckoLibAnimationManagerSnapshot>(EquipmentSlots::class.java)
    private val rotation = MVec3f()
    private var reload = false

    override val layer get() = EntityLayer.Translucent

    override fun update(delta: Duration) {
        syncEntries()
        val entity = livingRenderer.entity
        val velocity = entity.physics.velocity
        for ((slot, entry) in entries) {
            val instance = entry.instance
            instance.transform.reset()
            instance.animation.draw(delta)
            val state = GeckoLibAnimationState(
                ageSeconds = entity.age.coerceAtLeast(0) / 20.0f,
                moving = velocity.x * velocity.x + velocity.z * velocity.z > MOVEMENT_EPSILON_SQUARED,
                data = mapOf(
                    "query.is_on_ground" to if (entity.physics.onGround) 1.0 else 0.0,
                    "query.is_in_water" to if (entity.physics.inWater) 1.0 else 0.0,
                    "query.armor_slot" to slot.ordinal.toDouble(),
                ),
            )
            instance.geckoAnimation.updateState(state)
            if (instance.geckoAnimation.active) {
                instance.geckoAnimation.draw(delta, state)
            } else {
                instance.neutralAnimation.draw(delta)
            }
            rotation.y = -livingRenderer.info.bodyYaw.rad
            instance.update(rotation.unsafe, livingRenderer.matrix.unsafe)
            instance.transform.transform(instance.matrix.unsafe)
            if (instance.geckoAnimation.active) {
                instance.geckoAnimation.dispatchEvents()
            } else {
                instance.neutralAnimation.dispatchEvents()
            }
        }
    }

    private fun syncEntries() {
        for (slot in EquipmentSlots.ARMOR_SLOTS) {
            val stack = livingRenderer.entity.equipment[slot]
            val model = stack?.let(::model)
            val current = entries[slot]
            if (current != null && current.stack == stack && current.model === model) continue
            if (current != null) {
                entries.remove(slot)
                release(current.instance, enqueue = true)
            }
            if (stack == null || model == null) continue
            val instance = model.createInstance(livingRenderer.renderer.context)
            val events = GeckoLibEntityEventConsumer(livingRenderer, instance)
            instance.neutralAnimation.eventConsumer = events::dispatch
            instance.geckoAnimation.eventConsumer = events::dispatch
            pending.remove(slot)?.let(instance.geckoAnimation::restore)
            entries[slot] = Entry(stack, model, instance)
        }
    }

    private fun model(stack: ItemStack): BakedSkeletalModel? {
        val loader = livingRenderer.renderer.context.models.skeletal
        val name = loader.contentModel(GeckoLibModelTarget.ARMOR, stack.item.identifier) ?: return null
        return loader[name]
    }

    override fun prepare() {
        for (entry in entries.values) {
            if (entry.instance.state == SkeletalModelStates.PREPARING) entry.instance.load()
        }
    }

    override fun draw() {
        val context = livingRenderer.renderer.context
        val tint = livingRenderer.light.value
        context.skeletal.shader.entityColor = IrisEntityOverlay.resolve(livingRenderer.entity)
        for (entry in entries.values) {
            context.shaderPipeline.withDrawState(IrisDrawState(item = entry.stack.item.identifier)) {
                entry.instance.draw(tint)
                drawRenderLayers(entry.instance, tint)
            }
        }
    }

    override fun drawOutline(color: RGBAColor) {
        val context = livingRenderer.renderer.context
        val system = context.system
        val shader = context.skeletal.shader
        try {
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
            shader.outlineColor = color
            for (entry in entries.values) {
                val instance = entry.instance
                instance.draw(shader)
                for ((name, layer) in instance.model.geckoRenderLayers) {
                    instance.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                    instance.drawMesh(shader, layer.mesh)
                }
            }
        } finally {
            shader.outlineColor = Colors.TRANSPARENT
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
        }
    }

    private fun drawRenderLayers(
        instance: SkeletalInstance,
        tint: de.bixilon.minosoft.data.text.formatting.color.RGBColor,
    ) {
        if (instance.model.geckoRenderLayers.isEmpty()) return
        val context = livingRenderer.renderer.context
        val system = context.system
        val shader = context.skeletal.shader
        try {
            for ((name, layer) in instance.model.geckoRenderLayers) {
                instance.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                when (layer.blend) {
                    GeckoLibRenderLayerBlend.OPAQUE -> system.reset(
                        faceCulling = false,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.TRANSLUCENT -> system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.ADDITIVE -> system.reset(
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
                shader.tint = if (layer.fullBright) ChatColors.WHITE.rgb() else tint
                instance.drawMesh(shader, layer.mesh)
            }
        } finally {
            system.reset()
        }
    }

    override fun reloadContentModel() {
        for ((slot, entry) in entries) {
            entry.instance.geckoAnimation.snapshot()?.let { pending[slot] = it }
        }
        reload = true
    }

    override fun enqueueUnload() {
        if (!reload) return
        releaseAll(enqueue = true)
        reload = false
    }

    override fun unload() {
        releaseAll(enqueue = false)
        pending.clear()
        reload = false
    }

    private fun releaseAll(enqueue: Boolean) {
        val previous = entries.values.toList()
        entries.clear()
        previous.forEach { release(it.instance, enqueue) }
    }

    private fun release(instance: SkeletalInstance, enqueue: Boolean) {
        instance.neutralAnimation.clearEvents()
        instance.geckoAnimation.clearEvents()
        val close = {
            when (instance.state) {
                SkeletalModelStates.PREPARING -> instance.drop()
                SkeletalModelStates.LOADED -> instance.unload()
                SkeletalModelStates.UNLOADED -> Unit
            }
        }
        if (enqueue && instance.state == SkeletalModelStates.LOADED) {
            livingRenderer.renderer.retirementQueue += close
        } else {
            close()
        }
    }

    private data class Entry(
        val stack: ItemStack,
        val model: BakedSkeletalModel,
        val instance: SkeletalInstance,
    )

    private companion object {
        const val MOVEMENT_EPSILON_SQUARED = 1.0E-7
    }
}
