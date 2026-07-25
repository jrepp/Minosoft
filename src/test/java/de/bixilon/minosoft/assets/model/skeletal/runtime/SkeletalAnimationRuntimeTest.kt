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
import kotlin.test.assertFailsWith

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

    @Test
    fun `non finite animation inputs are rejected before pose evaluation`() {
        assertFailsWith<IllegalArgumentException> {
            SkeletalAnimationClip("invalid", Float.POSITIVE_INFINITY, SkeletalAnimationLoop.HOLD, emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            SkeletalAnimationKeyframe(
                Float.POSITIVE_INFINITY,
                SkeletalVectorValue.Constant(Vec3f.EMPTY),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SkeletalVectorValue.Constant(Vec3f(Float.POSITIVE_INFINITY, 0.0f, 0.0f))
        }
        val expression = clip(
            frames = listOf(
                SkeletalAnimationKeyframe(
                    0.0f,
                    SkeletalVectorValue.Expression(listOf("1e309", "0", "0")),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            SkeletalAnimationEvaluator.evaluate(expression, 0.0f)
        }
    }

    @Test
    fun `built in GeckoLib 4_4_4 easing catalog matches pinned transformers`() {
        val expected = mapOf(
            "linear" to 0.25f,
            "none" to 0.25f,
            "step" to 0.0f,
            "easeinsine" to 0.07612047f,
            "easeoutsine" to 0.38268343f,
            "easeinoutsine" to 0.14644661f,
            "easeinquad" to 0.0625f,
            "easeoutquad" to 0.4375f,
            "easeinoutquad" to 0.125f,
            "easeincubic" to 0.015625f,
            "easeoutcubic" to 0.578125f,
            "easeinoutcubic" to 0.0625f,
            "easeinquart" to 0.00390625f,
            "easeoutquart" to 0.68359375f,
            "easeinoutquart" to 0.03125f,
            "easeinquint" to 0.00390625f,
            "easeoutquint" to 0.7626953f,
            "easeinoutquint" to 0.015625f,
            "easeinexpo" to 0.005524272f,
            "easeoutexpo" to 0.8232233f,
            "easeinoutexpo" to 0.015625f,
            "easeincirc" to 0.031754162f,
            "easeoutcirc" to 0.6614378f,
            "easeinoutcirc" to 0.0669873f,
            "easeinback" to -0.064136565f,
            "easeoutback" to 0.8174097f,
            "easeinoutback" to -0.04384875f,
            "easeinelastic" to 0.44238937f,
            "easeoutelastic" to -0.039628167f,
            "easeinoutelastic" to 0.5f,
            "easeinbounce" to 0.47265625f,
            "easeoutbounce" to 0.10937502f,
            "easeinoutbounce" to 0.265625f,
            "catmullrom" to 1.25f,
        )

        for ((name, value) in expected) {
            assertEquals(value, SkeletalAnimationEvaluator.ease(name, 0.25f), 0.00001f, name)
        }
        assertEquals(-0.14389813f, SkeletalAnimationEvaluator.ease("easeinback", 0.25f, listOf(2.0f)), 0.00001f)
        assertEquals(1.0f, SkeletalAnimationEvaluator.ease("easeinelastic", 0.25f, listOf(2.0f)), 0.00001f)
        assertEquals(0.5f, SkeletalAnimationEvaluator.ease("step", 0.75f, listOf(4.0f)))
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
