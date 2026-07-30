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

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.MVec3d
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEventPlayback
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEventPlaybackTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEffectRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEventContext
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEvents
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.skeletal.instance.TransformInstance
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

class GeckoLibEntityEventConsumer(
    private val renderer: EntityRenderer<*>,
    private val instance: SkeletalInstance,
) : GeckoLibEventPlaybackTarget {
    private val effectBinding = instance.model.contentIdentity?.let(GeckoLibRuntimeEffectRegistry::bind)

    fun dispatch(animation: String, event: SkeletalAnimationEvent) {
        val context = GeckoLibRuntimeEventContext(
            animation = animation,
            event = event,
            position = position(event.locator),
            entityId = renderer.entity.id,
            entityUuid = renderer.entity.uuid,
            contentIdentity = instance.model.contentIdentity,
        )
        GeckoLibRuntimeEvents.dispatch(context)
        GeckoLibEventPlayback.dispatch(context, this, effectBinding)
    }

    override fun playSound(context: GeckoLibRuntimeEventContext, sound: ResourceLocation) {
        renderer.entity.session.world.audio?.play(sound, context.position)
    }

    override fun spawnParticle(context: GeckoLibRuntimeEventContext, particle: ResourceLocation) {
        val session = renderer.entity.session
        val particleRenderer = session.world.particle ?: return
        val type = session.registries.particleType[particle]
            ?: return rejected(context, "Unknown particle type '$particle'.")
        val factory = type.factory
            ?: return rejected(context, "Particle type '$particle' has no native renderer factory.")
        try {
            particleRenderer += factory.build(session, context.position, MVec3d.EMPTY, type.default())
        } catch (error: Throwable) {
            rejected(context, "Could not build particle '$particle': ${error.message}")
        }
    }

    override fun customInstruction(context: GeckoLibRuntimeEventContext) = Unit

    override fun rejected(context: GeckoLibRuntimeEventContext, reason: String) {
        Log.log(LogMessageType.RENDERING, LogLevels.VERBOSE) {
            "GECKOLIB_EVENT_REJECTED entity=${context.entityId} animation=${context.animation} type=${context.event.type} reason=$reason"
        }
    }

    private fun position(locator: String?): Vec3d {
        if (locator.isNullOrBlank()) return renderer.entity.physics.position
        val transform = instance.transform.find(locator) ?: return renderer.entity.physics.position
        val local = transform.matrix.unsafe * transform.pivot
        val offset = renderer.renderer.context.camera.offset.offset
        return Vec3d(local.x + offset.x, local.y + offset.y, local.z + offset.z)
    }

    private fun TransformInstance.find(name: String): TransformInstance? {
        children[name]?.let { return it }
        for (child in children.values) child.find(name)?.let { return it }
        return null
    }
}
