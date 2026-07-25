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
import kotlin.math.*

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

fun interface SkeletalEasingResolver {
    fun transform(name: String, value: Float, arguments: List<Float>): Float?
}

object SkeletalAnimationEvaluator {
    private val expressions = ConcurrentHashMap<String, SkeletalExpression>()

    fun evaluate(
        clip: SkeletalAnimationClip,
        elapsedSeconds: Float,
        expressionContext: SkeletalExpressionContext = SkeletalExpressionContext(),
        easingOverride: String? = null,
        easingResolver: SkeletalEasingResolver? = null,
    ): SkeletalPose {
        require(elapsedSeconds.isFinite()) { "Animation elapsed time must be finite." }
        val time = timeline(clip, elapsedSeconds)
        val bones = clip.channels.mapValues { (_, channels) ->
            var pose = SkeletalBonePose()
            for (channel in channels) {
                val value = sample(channel.keyframes, time, expressionContext, easingOverride, easingResolver)
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
        easingOverride: String?,
        easingResolver: SkeletalEasingResolver?,
    ): Vec3f {
        require(frames.isNotEmpty()) { "Animation channel has no keyframes." }
        if (frames.size == 1 || time <= frames.first().timeSeconds) return value(frames.first().value, context)
        if (time >= frames.last().timeSeconds) return value(frames.last().value, context)
        val rightIndex = frames.binarySearchBy(time) { it.timeSeconds }
            .let { if (it >= 0) it else -it - 1 }
        val leftIndex = rightIndex - 1
        val left = frames[leftIndex]
        val right = frames[rightIndex]
        val duration = right.timeSeconds - left.timeSeconds
        var delta = if (duration <= 0.0f) 1.0f else (time - left.timeSeconds) / duration
        delta = ease(easingOverride ?: left.easing, delta, left.easingArguments, easingResolver)
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
            expressionComponent(value.components[0], context),
            expressionComponent(value.components[1], context),
            expressionComponent(value.components[2], context),
        )
    }

    private fun expression(source: String) = expressions.computeIfAbsent(source, SkeletalExpression::compile)

    private fun expressionComponent(source: String, context: SkeletalExpressionContext): Float {
        val value = expression(source).evaluate(context)
        require(value.isFinite() && value in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()) {
            "Skeletal vector expression '$source' returned a non-finite or out-of-range value."
        }
        return value.toFloat()
    }

    internal fun ease(
        name: String?,
        value: Float,
        arguments: List<Float> = emptyList(),
        easingResolver: SkeletalEasingResolver? = null,
    ): Float {
        val t = value.toDouble()
        val argument = arguments.firstOrNull()?.toDouble()
        val key = name?.lowercase() ?: "linear"
        val normalized = key.replace("_", "")
        val result = when (normalized) {
            "linear", "none" -> t
            "step" -> step(t, argument)
            "easeinsine" -> easeIn(t, ::sine)
            "easeoutsine" -> easeOut(t, ::sine)
            "easeinoutsine" -> easeInOut(t, ::sine)
            "easeinquad" -> easeIn(t) { it.pow(2) }
            "easeoutquad" -> easeOut(t) { it.pow(2) }
            "easeinoutquad" -> easeInOut(t) { it.pow(2) }
            "easeincubic" -> easeIn(t) { it.pow(3) }
            "easeoutcubic" -> easeOut(t) { it.pow(3) }
            "easeinoutcubic" -> easeInOut(t) { it.pow(3) }
            "easeinquart" -> easeIn(t) { it.pow(4) }
            "easeoutquart" -> easeOut(t) { it.pow(4) }
            "easeinoutquart" -> easeInOut(t) { it.pow(4) }
            // GeckoLib 4.4.4 registers its ease-in quint transformer with a
            // fourth-power base, while out/in-out use fifth power.
            "easeinquint" -> easeIn(t) { it.pow(4) }
            "easeoutquint" -> easeOut(t) { it.pow(5) }
            "easeinoutquint" -> easeInOut(t) { it.pow(5) }
            "easeinexpo" -> easeIn(t, ::exponential)
            "easeoutexpo" -> easeOut(t, ::exponential)
            "easeinoutexpo" -> easeInOut(t, ::exponential)
            "easeincirc" -> easeIn(t, ::circular)
            "easeoutcirc" -> easeOut(t, ::circular)
            "easeinoutcirc" -> easeInOut(t, ::circular)
            "easeinback" -> easeIn(t) { back(it, argument) }
            "easeoutback" -> easeOut(t) { back(it, argument) }
            "easeinoutback" -> easeInOut(t) { back(it, argument) }
            "easeinelastic" -> easeIn(t) { elastic(it, argument) }
            "easeoutelastic" -> easeOut(t) { elastic(it, argument) }
            "easeinoutelastic" -> easeInOut(t) { elastic(it, argument) }
            "easeinbounce" -> easeIn(t) { bounce(it, argument) }
            "easeoutbounce" -> easeOut(t) { bounce(it, argument) }
            "easeinoutbounce" -> easeInOut(t) { bounce(it, argument) }
            // GeckoLib 4.4.4 exposes this unusual easing in addition to
            // Catmull-Rom channel interpolation. Preserve its exact
            // ease-in-out transformer behavior for adapter compatibility.
            "catmullrom" -> easeInOut(t) { it + 2.0 }
            else -> {
                val custom = easingResolver?.transform(key, value, arguments) ?: return value
                require(custom.isFinite()) { "Custom skeletal easing '$key' returned a non-finite value." }
                return custom
            }
        }
        return result.toFloat().also {
            require(it.isFinite()) { "Skeletal easing '$key' returned a non-finite or out-of-range value." }
        }
    }

    internal fun isBuiltInEasing(name: String): Boolean {
        return name.lowercase().replace("_", "") in BUILT_IN_EASINGS
    }

    private fun easeIn(value: Double, base: (Double) -> Double) = base(value)
    private fun easeOut(value: Double, base: (Double) -> Double) = 1.0 - base(1.0 - value)
    private fun easeInOut(value: Double, base: (Double) -> Double): Double {
        return if (value < 0.5) base(value * 2.0) / 2.0
        else 1.0 - base((1.0 - value) * 2.0) / 2.0
    }

    private fun sine(value: Double) = 1.0 - cos(value * PI / 2.0)
    private fun exponential(value: Double) = 2.0.pow(10.0 * (value - 1.0))
    private fun circular(value: Double) = 1.0 - sqrt(1.0 - value * value)

    private fun back(value: Double, argument: Double?): Double {
        val amount = argument?.times(1.70158) ?: 1.70158
        return value * value * ((amount + 1.0) * value - amount)
    }

    private fun elastic(value: Double, argument: Double?): Double {
        val amount = argument ?: 1.0
        return 1.0 - cos(value * PI / 2.0).pow(3) * cos(value * amount * PI)
    }

    private fun bounce(value: Double, argument: Double?): Double {
        val amount = argument ?: 0.5
        val amount2 = amount * amount
        val amount3 = amount2 * amount
        return minOf(
            7.5625 * value * value,
            1.0 + 30.25 * amount * (value - 0.5454545617103577).pow(2) - amount,
            1.0 + 121.0 * amount2 * (value - 0.8181818127632141).pow(2) - amount2,
            1.0 + 484.0 * amount3 * (value - 0.9545454382896423).pow(2) - amount3,
        )
    }

    private fun step(value: Double, argument: Double?): Double {
        val steps = (argument ?: 2.0).toInt()
        require(steps in 2..MAX_EASING_STEPS) {
            "Step easing count must be within 2..$MAX_EASING_STEPS."
        }
        if (value < 0.0) return 0.0
        val index = (ceil(value * steps).toInt() - 1).coerceIn(0, steps - 1)
        return index.toDouble() / steps
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

    private const val MAX_EASING_STEPS = 1_000_000
    private val BUILT_IN_EASINGS = setOf(
        "linear", "none", "step", "catmullrom",
        "easeinsine", "easeoutsine", "easeinoutsine",
        "easeinquad", "easeoutquad", "easeinoutquad",
        "easeincubic", "easeoutcubic", "easeinoutcubic",
        "easeinquart", "easeoutquart", "easeinoutquart",
        "easeinquint", "easeoutquint", "easeinoutquint",
        "easeinexpo", "easeoutexpo", "easeinoutexpo",
        "easeincirc", "easeoutcirc", "easeinoutcirc",
        "easeinback", "easeoutback", "easeinoutback",
        "easeinelastic", "easeoutelastic", "easeinoutelastic",
        "easeinbounce", "easeoutbounce", "easeinoutbounce",
    )
}

private fun lerp(from: Vec3f, to: Vec3f, delta: Float) = Vec3f(
    from.x + (to.x - from.x) * delta,
    from.y + (to.y - from.y) * delta,
    from.z + (to.z - from.z) * delta,
)
