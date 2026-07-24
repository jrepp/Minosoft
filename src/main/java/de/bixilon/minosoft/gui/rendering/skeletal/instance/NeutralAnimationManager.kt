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
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationController
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalPose
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement.Companion.BLOCK_SIZE
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3fUtil.rad
import kotlin.time.Duration

class NeutralAnimationManager(private val instance: SkeletalInstance) {
    private val controller = SkeletalAnimationController(instance.model.neutralAnimations)
    private val transforms = instance.transform.index()
    var expressionContext: SkeletalExpressionContext = SkeletalExpressionContext()

    val current get() = controller.current

    fun play(name: String, transitionSeconds: Float = 0.0f, restart: Boolean = false) {
        controller.play(name, transitionSeconds, restart)
    }

    fun draw(delta: Duration) {
        if (current == null) return
        controller.update(delta.inWholeNanoseconds / 1_000_000_000.0f, expressionContext).apply(transforms)
    }
}

internal fun SkeletalPose.apply(transforms: Map<String, TransformInstance>) {
    for ((bone, pose) in bones) {
        val transform = transforms[bone] ?: continue
        transform.matrix.apply {
            translateAssign(pose.translation / BLOCK_SIZE)
            translateAssign(transform.nPivot)
            rotateRadAssign(pose.rotation.rad)
            scaleAssign(pose.scale)
            translateAssign(transform.pivot)
        }
    }
}

private fun TransformInstance.index(): Map<String, TransformInstance> {
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
