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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living

import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.SkeletalFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.armor.VanillaArmorPoseSource
import de.bixilon.minosoft.gui.rendering.entities.model.human.HumanoidMobModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.instance.GeckoLibAnimationManagerSnapshot
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

open class HumanoidMobRenderer<E : LivingEntity>(
    renderer: EntitiesRenderer,
    entity: E,
    private val modelResource: ResourceLocation,
) : LivingEntityRenderer<E>(renderer, entity), ContentModelReloadable, ContentModelInspectable, VanillaArmorPoseSource {
    var model: SkeletalFeature? = null
        private set
    private var unloadModel = false
    private var pendingGeckoAnimation: GeckoLibAnimationManagerSnapshot? = null
    override val retainedContentModel get() = model?.instance?.model
    override val retainedContentControllers get() = model?.instance?.geckoAnimation?.inspection
    override val vanillaArmorPose get() = (model as? HumanoidMobModel)?.instance

    override fun enqueueUnload() {
        super.enqueueUnload()
        if (!unloadModel) return
        val model = model
        this.model = null
        if (model != null) {
            features -= model
            renderer.queue += { model.unload() }
        }
        unloadModel = false
    }

    override fun reloadContentModel() {
        pendingGeckoAnimation = model?.instance?.geckoAnimation?.snapshot()
        unloadModel = true
    }

    override fun update(time: ValueTimeMark, delta: Duration, auxiliaryVisible: Boolean) {
        super.update(time, delta, auxiliaryVisible)
        if (model != null) return

        val route = renderer.context.models.skeletal.contentModel(entity.type.identifier)
        val routed = route?.let(renderer.context.models.skeletal::get)
        val generic = routed?.contentIdentity?.format == SkeletalContentFormat.GECKOLIB
        val baked = when {
            generic -> routed
            routed?.hasHumanoidAnimationRig() == true -> routed
            else -> renderer.context.models.skeletal[modelResource]
        } ?: return
        model = (if (generic) {
            SkeletalFeature(this, baked)
        } else {
            HumanoidMobModel(this, baked)
        }).also { feature ->
            pendingGeckoAnimation?.let(feature.instance.geckoAnimation::restore)
            pendingGeckoAnimation = null
            feature.register()
        }
    }

    override fun unload() {
        super.unload()
        model = null
        pendingGeckoAnimation = null
    }

    private fun BakedSkeletalModel.hasHumanoidAnimationRig(): Boolean =
        HUMANOID_ANIMATION_TRANSFORMS.all(transform.children::containsKey)

    private companion object {
        val HUMANOID_ANIMATION_TRANSFORMS = setOf("left_leg", "right_leg", "left_arm", "right_arm")
    }
}
