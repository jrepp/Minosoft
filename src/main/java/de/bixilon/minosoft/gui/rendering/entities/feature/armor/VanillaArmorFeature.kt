/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.armor

import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.registries.item.items.armor.ArmorItem
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.entities.feature.DrawableEntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDrawState
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import java.util.EnumMap
import kotlin.time.Duration

/**
 * Vanilla humanoid armor base layers. Decorations are a separate non-caster
 * drawable so trims and glint cannot replay into the physical shadow view.
 */
class VanillaArmorFeature(
    private val livingRenderer: LivingEntityRenderer<*>,
) : DrawableEntityRenderFeature(livingRenderer) {
    private val entries = EnumMap<EquipmentSlots, Entry>(EquipmentSlots::class.java)
    private val context = livingRenderer.renderer.context
    private var outer: SkeletalInstance? = null
    private var inner: SkeletalInstance? = null
    val decorations = Decorations(this)

    override val castsShadow get() = true
    override val layer get() = EntityLayer.Opaque
    override val updatePriority get() = 1_000
    override val priority get() = 50

    override fun isVisible() = super.isVisible() && retainsForSync(hasVanillaArmor(), entries.size)
    internal val entryCount get() = entries.size
    internal val decorationCount get() = entries.values.count {
        it.material.overlay != null || it.material.trim != null || it.material.enchanted
    }

    override fun update(delta: Duration) {
        syncEntries()
        if (!ensureInstances()) return
        val pose = (livingRenderer as? VanillaArmorPoseSource)?.vanillaArmorPose ?: return
        outer?.transform?.copyRenderedPoseFrom(pose.transform)
        inner?.transform?.copyRenderedPoseFrom(pose.transform)
    }

    private fun hasVanillaArmor(): Boolean {
        val loader = context.models.skeletal
        return EquipmentSlots.ARMOR_SLOTS.any { slot ->
            val item = livingRenderer.entity.equipment[slot]?.item
            item is ArmorItem &&
                loader.contentModel(GeckoLibModelTarget.ARMOR, item.identifier) == null
        }
    }

    private fun syncEntries() {
        entries.clear()
        val textures = livingRenderer.renderer.features.armor.textures
        val loader = context.models.skeletal
        for (slot in EquipmentSlots.ARMOR_SLOTS) {
            val stack = livingRenderer.entity.equipment[slot] ?: continue
            if (stack.item !is ArmorItem) continue
            if (loader.contentModel(GeckoLibModelTarget.ARMOR, stack.item.identifier) != null) continue
            val material = textures.resolve(stack, slot) ?: continue
            entries[slot] = Entry(stack, slot, material)
        }
    }

    override fun prepare() {
        if (!ensureInstances()) return
        outer?.let { if (it.state == SkeletalModelStates.PREPARING) it.load() }
        inner?.let { if (it.state == SkeletalModelStates.PREPARING) it.load() }
    }

    override fun draw() {
        if (!ensureInstances()) return
        try {
            context.system.reset(faceCulling = false)
            for (entry in entries.values) {
                draw(entry, entry.material.base, entry.material.tint, glint = false)
            }
        } finally {
            resetShader()
            context.system.reset()
        }
    }

    private fun draw(
        entry: Entry,
        texture: Texture,
        tint: RGBColor,
        glint: Boolean,
    ) {
        val instance = instance(entry.slot) ?: return
        context.shaderPipeline.withDrawState(IrisDrawState(item = entry.stack.item.identifier)) {
            val shader = livingRenderer.renderer.features.player.shader
            shader.withProgramFamily(if (glint) SceneProgramFamily.ARMOR_GLINT else SceneProgramFamily.ENTITY) {
                shader.texture = texture.shaderId
                shader.tint = if (glint) ChatColors.WHITE.rgb() else livingRenderer.light.value * tint
                shader.entityColor = IrisEntityOverlay.resolve(livingRenderer.entity)
                shader.skinParts = parts(entry.slot)
                shader.inflate = 0.0f
                shader.hideBase = true
                shader.featurePart = 0x00
                shader.allowBaseTransparency = false
                shader.glintTexture = livingRenderer.renderer.features.armor.glint.shaderId
                shader.glintTime = livingRenderer.entity.age.toFloat()
                shader.glint = glint
                context.skeletal.upload(instance)
                instance.model.mesh.draw()
            }
        }
    }

    private fun drawDecorations() {
        val system = context.system
        try {
            for (entry in entries.values) {
                entry.material.overlay?.let {
                    system.reset(
                        faceCulling = false,
                        depthMask = false,
                        depth = DepthFunctions.EQUAL,
                    )
                    draw(entry, it, livingRenderer.light.value, glint = false)
                }
                entry.material.trim?.let {
                    system.reset(
                        faceCulling = false,
                        depthMask = false,
                        depth = DepthFunctions.EQUAL,
                    )
                    draw(entry, it, livingRenderer.light.value, glint = false)
                }
                if (entry.material.enchanted) {
                    system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_COLOR,
                        destinationRGB = BlendingFunctions.ONE,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ZERO,
                        depth = DepthFunctions.EQUAL,
                    )
                    draw(entry, entry.material.base, ChatColors.WHITE.rgb(), glint = true)
                }
            }
        } finally {
            resetShader()
            system.reset()
        }
    }

    private fun instance(slot: EquipmentSlots): SkeletalInstance? =
        if (slot == EquipmentSlots.LEGS) inner else outer

    private fun ensureInstances(): Boolean {
        if (outer != null && inner != null) return true
        val models = context.models.skeletal
        val outerModel = models[VanillaArmorModels.OUTER] ?: return false
        val innerModel = models[VanillaArmorModels.INNER] ?: return false
        outer = outerModel.createInstance(context)
        inner = innerModel.createInstance(context)
        return true
    }

    private fun resetShader() {
        val shader = livingRenderer.renderer.features.player.shader
        shader.glint = false
        shader.inflate = 0.0f
        shader.hideBase = false
        shader.featurePart = 0x00
        shader.allowBaseTransparency = false
        shader.skinParts = 0xFF
    }

    override fun unload() {
        entries.clear()
        outer?.let {
            when (it.state) {
                SkeletalModelStates.PREPARING -> it.drop()
                SkeletalModelStates.LOADED -> it.unload()
                SkeletalModelStates.UNLOADED -> Unit
            }
        }
        inner?.let {
            when (it.state) {
                SkeletalModelStates.PREPARING -> it.drop()
                SkeletalModelStates.LOADED -> it.unload()
                SkeletalModelStates.UNLOADED -> Unit
            }
        }
        outer = null
        inner = null
    }

    class Decorations(
        private val owner: VanillaArmorFeature,
    ) : DrawableEntityRenderFeature(owner.livingRenderer) {
        override val layer get() = EntityLayer.Opaque
        override val priority get() = owner.priority + 1

        override fun isVisible() = super.isVisible() && owner.entries.values.any {
            it.material.overlay != null || it.material.trim != null || it.material.enchanted
        }

        override fun draw() = owner.drawDecorations()
    }

    private data class Entry(
        val stack: ItemStack,
        val slot: EquipmentSlots,
        val material: VanillaArmorMaterial,
    )

    companion object {
        /**
         * Keep the feature updateable for one final frame after armor removal
         * so [syncEntries] can retire retained base/trim/glint draws.
         */
        internal fun retainsForSync(hasArmor: Boolean, entryCount: Int): Boolean =
            hasArmor || entryCount > 0

        internal fun parts(slot: EquipmentSlots): Int = when (slot) {
            EquipmentSlots.HEAD -> de.bixilon.minosoft.data.entities.entities.player.SkinParts.HAT.bitmask
            EquipmentSlots.CHEST ->
                de.bixilon.minosoft.data.entities.entities.player.SkinParts.JACKET.bitmask or
                    de.bixilon.minosoft.data.entities.entities.player.SkinParts.LEFT_SLEEVE.bitmask or
                    de.bixilon.minosoft.data.entities.entities.player.SkinParts.RIGHT_SLEEVE.bitmask
            EquipmentSlots.LEGS ->
                de.bixilon.minosoft.data.entities.entities.player.SkinParts.JACKET.bitmask or
                    de.bixilon.minosoft.data.entities.entities.player.SkinParts.LEFT_PANTS.bitmask or
                    de.bixilon.minosoft.data.entities.entities.player.SkinParts.RIGHT_PANTS.bitmask
            EquipmentSlots.FEET ->
                de.bixilon.minosoft.data.entities.entities.player.SkinParts.LEFT_PANTS.bitmask or
                    de.bixilon.minosoft.data.entities.entities.player.SkinParts.RIGHT_PANTS.bitmask
            EquipmentSlots.MAIN_HAND,
            EquipmentSlots.OFF_HAND,
            -> 0
        }
    }
}
