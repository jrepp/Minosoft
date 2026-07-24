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
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpression
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

data class SkeletalBonePose(
    val rotation: Vec3f = Vec3f.EMPTY,
    val translation: Vec3f = Vec3f.EMPTY,
    val scale: Vec3f = Vec3f(1.0f),
)

data class SkeletalPose(val bones: Map<String, SkeletalBonePose>) {
    fun blend(target: SkeletalPose, delta: Float): SkeletalPose {
        val t = delta.coerceIn(0.0f, 1.0f)
        val names = bones.keys + target.bones.keys
        return SkeletalPose(names.associateWith { name ->
            val from = bones[name] ?: SkeletalBonePose()
            val to = target.bones[name] ?: SkeletalBonePose()
            SkeletalBonePose(
                rotation = lerp(from.rotation, to.rotation, t),
                translation = lerp(from.translation, to.translation, t),
                scale = lerp(from.scale, to.scale, t),
            )
        })
    }
}

object SkeletalAnimationEvaluator {
    private val expressions = ConcurrentHashMap<String, SkeletalExpression>()

    fun evaluate(
        clip: SkeletalAnimationClip,
        elapsedSeconds: Float,
        expressionContext: SkeletalExpressionContext = SkeletalExpressionContext(),
    ): SkeletalPose {
        val time = timeline(clip, elapsedSeconds)
        val bones = clip.channels.mapValues { (_, channels) ->
            var pose = SkeletalBonePose()
            for (channel in channels) {
                val value = sample(channel.keyframes, time, expressionContext)
                pose = when (channel.target) {
                    SkeletalAnimationTarget.ROTATION -> pose.copy(rotation = value)
                    SkeletalAnimationTarget.TRANSLATION -> pose.copy(translation = value)
                    SkeletalAnimationTarget.SCALE -> pose.copy(scale = value)
                }
            }
            pose
        }
        return SkeletalPose(bones)
    }

    private fun timeline(clip: SkeletalAnimationClip, elapsed: Float): Float {
        if (clip.lengthSeconds <= 0.0f) return 0.0f
        return when (clip.loop) {
            SkeletalAnimationLoop.LOOP -> ((elapsed % clip.lengthSeconds) + clip.lengthSeconds) % clip.lengthSeconds
            SkeletalAnimationLoop.ONCE, SkeletalAnimationLoop.HOLD -> elapsed.coerceIn(0.0f, clip.lengthSeconds)
        }
    }

    private fun sample(
        frames: List<SkeletalAnimationKeyframe>,
        time: Float,
        context: SkeletalExpressionContext,
    ): Vec3f {
        require(frames.isNotEmpty()) { "Animation channel has no keyframes." }
        if (frames.size == 1 || time <= frames.first().timeSeconds) return value(frames.first().value, context)
        if (time >= frames.last().timeSeconds) return value(frames.last().value, context)
        val rightIndex = frames.indexOfFirst { it.timeSeconds >= time }
        val leftIndex = rightIndex - 1
        val left = frames[leftIndex]
        val right = frames[rightIndex]
        val duration = right.timeSeconds - left.timeSeconds
        var delta = if (duration <= 0.0f) 1.0f else (time - left.timeSeconds) / duration
        delta = ease(left.easing, delta)
        val p1 = value(left.value, context)
        if (left.interpolation == SkeletalInterpolation.STEP) return p1
        val p2 = value(right.value, context)
        if (left.interpolation != SkeletalInterpolation.CATMULL_ROM) return lerp(p1, p2, delta)
        val p0 = value(frames.getOrElse(leftIndex - 1) { left }.value, context)
        val p3 = value(frames.getOrElse(rightIndex + 1) { right }.value, context)
        return catmull(p0, p1, p2, p3, delta)
    }

    private fun value(value: SkeletalVectorValue, context: SkeletalExpressionContext): Vec3f = when (value) {
        is SkeletalVectorValue.Constant -> value.value
        is SkeletalVectorValue.Expression -> Vec3f(
            expression(value.components[0]).evaluate(context).toFloat(),
            expression(value.components[1]).evaluate(context).toFloat(),
            expression(value.components[2]).evaluate(context).toFloat(),
        )
    }

    private fun expression(source: String) = expressions.computeIfAbsent(source, SkeletalExpression::compile)

    private fun ease(name: String?, value: Float): Float = when (name?.lowercase()) {
        "easeinsine" -> (1.0 - cos(value * PI / 2.0)).toFloat()
        "easeoutsine" -> sin(value * PI / 2.0).toFloat()
        "easeinoutsine" -> (-(cos(PI * value) - 1.0) / 2.0).toFloat()
        "easeinquad" -> value * value
        "easeoutquad" -> 1.0f - (1.0f - value).pow(2)
        "easeinoutquad" -> if (value < 0.5f) 2.0f * value * value else 1.0f - (-2.0f * value + 2.0f).pow(2) / 2.0f
        else -> value
    }

    private fun catmull(p0: Vec3f, p1: Vec3f, p2: Vec3f, p3: Vec3f, t: Float) = Vec3f(
        catmull(p0.x, p1.x, p2.x, p3.x, t),
        catmull(p0.y, p1.y, p2.y, p3.y, t),
        catmull(p0.z, p1.z, p2.z, p3.z, t),
    )

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2.0f * p1) + (-p0 + p2) * t + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 + (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3)
    }
}

private fun lerp(from: Vec3f, to: Vec3f, delta: Float) = Vec3f(
    from.x + (to.x - from.x) * delta,
    from.y + (to.y - from.y) * delta,
    from.z + (to.z - from.z) * delta,
)
