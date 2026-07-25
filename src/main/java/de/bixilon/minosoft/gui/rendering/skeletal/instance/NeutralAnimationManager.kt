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

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEasingRegistry
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationController
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalPose
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement.Companion.BLOCK_SIZE
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3fUtil.rad
import kotlin.time.Duration

class NeutralAnimationManager(private val instance: SkeletalInstance) {
    private val controller = SkeletalAnimationController(instance.model.neutralAnimations)
    private val transforms = instance.transform.index()
    private val pendingEvents = mutableListOf<PendingEvent>()
    var expressionContext: SkeletalExpressionContext = SkeletalExpressionContext()
    var eventConsumer: ((String, SkeletalAnimationEvent) -> Unit)? = null

    val current get() = controller.current

    init {
        instance.model.neutralAnimations.keys.singleOrNull()?.let(controller::play)
    }

    fun play(name: String, transitionSeconds: Float = 0.0f, restart: Boolean = false) {
        controller.play(name, transitionSeconds, restart)
    }

    fun draw(delta: Duration) {
        if (current == null) return
        val collect = if (eventConsumer == null) null else event@{ value: SkeletalAnimationEvent ->
            val animation = current ?: return@event
            pendingEvents += PendingEvent(animation, value)
        }
        controller.update(
            delta.inWholeNanoseconds / 1_000_000_000.0f,
            expressionContext,
            collect,
            easingResolver = GeckoLibEasingRegistry,
        ).apply(transforms)
    }

    fun dispatchEvents() {
        if (pendingEvents.isEmpty()) return
        val consumer = eventConsumer
        try {
            if (consumer != null) {
                for ((animation, event) in pendingEvents) consumer(animation, event)
            }
        } finally {
            pendingEvents.clear()
        }
    }

    fun clearEvents() {
        pendingEvents.clear()
        eventConsumer = null
    }

    private data class PendingEvent(val animation: String, val event: SkeletalAnimationEvent)
}

internal fun SkeletalPose.apply(transforms: Map<String, TransformInstance>) {
    for ((bone, pose) in bones) {
        val transform = transforms[bone] ?: continue
        transform.recordTranslationPixels(pose.translation)
        transform.recordRotation(pose.rotation.rad)
        transform.recordScale(pose.scale)
        transform.matrix.apply {
            translateAssign(pose.translation / BLOCK_SIZE)
            translateAssign(transform.nPivot)
            rotateRadAssign(pose.rotation.rad)
            scaleAssign(pose.scale)
            translateAssign(transform.pivot)
        }
    }
}

internal fun TransformInstance.index(): Map<String, TransformInstance> {
    val result = linkedMapOf<String, TransformInstance>()
    fun collect(transform: TransformInstance) {
        for ((name, child) in transform.children) {
            result.putIfAbsent(name, child)
            collect(child)
        }
    }
    collect(this)
    return result
}
