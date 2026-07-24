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

package de.bixilon.minosoft.assets.model.skeletal.runtime

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletalAnimationRuntimeTest {

    @Test
    fun `looped channels interpolate constants and expressions deterministically`() {
        val clip = clip(
            loop = SkeletalAnimationLoop.LOOP,
            frames = listOf(
                SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f.EMPTY)),
                SkeletalAnimationKeyframe(1.0f, SkeletalVectorValue.Expression(listOf("query.speed * 2", "0", "0"))),
            ),
        )
        val context = SkeletalExpressionContext(mapOf("query.speed" to 4.0))

        assertEquals(4.0f, SkeletalAnimationEvaluator.evaluate(clip, 0.5f, context).bones.getValue("root").translation.x)
        assertEquals(4.0f, SkeletalAnimationEvaluator.evaluate(clip, 1.5f, context).bones.getValue("root").translation.x)
    }

    @Test
    fun `controller transitions from previous pose and rejects unknown clips`() {
        val idle = clip(frames = listOf(SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f.EMPTY))))
        val moving = clip(frames = listOf(SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f(10, 0, 0)))))
        val controller = SkeletalAnimationController(mapOf("idle" to idle, "moving" to moving), "idle")

        controller.play("moving", transitionSeconds = 1.0f)
        assertEquals(5.0f, controller.update(0.5f).bones.getValue("root").translation.x)
        assertEquals(10.0f, controller.update(0.5f).bones.getValue("root").translation.x)
    }

    private fun clip(
        loop: SkeletalAnimationLoop = SkeletalAnimationLoop.HOLD,
        frames: List<SkeletalAnimationKeyframe>,
    ) = SkeletalAnimationClip(
        name = "test",
        lengthSeconds = frames.maxOfOrNull(SkeletalAnimationKeyframe::timeSeconds) ?: 0.0f,
        loop = loop,
        channels = mapOf("root" to listOf(SkeletalAnimationChannel(SkeletalAnimationTarget.TRANSLATION, frames))),
    )
}
