/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationController
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalBonePose
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalPose

data class GeckoLibAnimationState(
    val ageSeconds: Float,
    val moving: Boolean = false,
    val data: Map<String, Double> = emptyMap(),
) {
    init {
        require(ageSeconds.isFinite() && ageSeconds >= 0.0f) {
            "GeckoLib animation age must be finite and non-negative."
        }
        require(data.size <= MAX_STATE_VALUES && data.values.all(Double::isFinite)) {
            "GeckoLib animation state must contain at most $MAX_STATE_VALUES finite values."
        }
    }

    fun expressionContext(random: () -> Double = { 0.0 }) = SkeletalExpressionContext(
        variables = buildMap {
            putAll(data)
            put("query.anim_time", ageSeconds.toDouble())
            put("query.is_moving", if (moving) 1.0 else 0.0)
        },
        random = random,
    )

    private companion object {
        const val MAX_STATE_VALUES = 4_096
    }
}

sealed interface GeckoLibControllerDecision {
    data object Keep : GeckoLibControllerDecision
    data object Stop : GeckoLibControllerDecision
    data class Play(
        val clip: String,
        val transitionSeconds: Float? = null,
        val restart: Boolean = false,
    ) : GeckoLibControllerDecision
}

fun interface GeckoLibAnimationPredicate {
    fun decide(state: GeckoLibAnimationState, currentClip: String?): GeckoLibControllerDecision
}

enum class GeckoLibLayerBlend {
    REPLACE,
    ADD,
}

data class GeckoLibControllerDefinition(
    val name: String,
    val initialClip: String? = null,
    val transitionSeconds: Float = 0.0f,
    val weight: Float = 1.0f,
    val blend: GeckoLibLayerBlend = GeckoLibLayerBlend.REPLACE,
    val predicate: GeckoLibAnimationPredicate = GeckoLibAnimationPredicate { _, _ -> GeckoLibControllerDecision.Keep },
) {
    init {
        require(name.isNotBlank()) { "GeckoLib controller name must not be blank." }
        require(transitionSeconds.isFinite() && transitionSeconds >= 0.0f) {
            "GeckoLib controller transition must be finite and non-negative."
        }
        require(weight.isFinite() && weight in 0.0f..1.0f) {
            "GeckoLib controller weight must be within 0..1."
        }
    }
}

/**
 * Source-native controller facade for mods adapted to Minosoft.
 *
 * This API intentionally uses only stable Minosoft DTOs. It does not pretend
 * that classes compiled against GeckoLib/Mojang binaries can link directly.
 * Multiple named controllers evaluate independently, then combine in
 * registration order as replace or additive layers.
 */
class GeckoLibControllerSet(
    private val clips: Map<String, SkeletalAnimationClip>,
    definitions: List<GeckoLibControllerDefinition>,
) {
    private val layers = definitions.also {
        require(it.size <= MAX_CONTROLLERS) {
            "GeckoLib controller set exceeds the $MAX_CONTROLLERS controller limit."
        }
    }.map { definition ->
        definition.initialClip?.let {
            require(it in clips) { "Unknown initial GeckoLib animation '$it' for ${definition.name}." }
        }
        Layer(definition, SkeletalAnimationController(clips, definition.initialClip))
    }

    init {
        require(definitions.map { it.name }.distinct().size == definitions.size) {
            "GeckoLib controller names must be unique."
        }
    }

    fun update(
        deltaSeconds: Float,
        state: GeckoLibAnimationState,
        random: () -> Double = { 0.0 },
    ): SkeletalPose {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0.0f) {
            "GeckoLib controller delta must be finite and non-negative."
        }
        val context = state.expressionContext(random)
        var result = SkeletalPose(emptyMap())
        for (layer in layers) {
            when (val decision = layer.definition.predicate.decide(state, layer.controller.current)) {
                GeckoLibControllerDecision.Keep -> Unit
                GeckoLibControllerDecision.Stop -> layer.controller.stop()
                is GeckoLibControllerDecision.Play -> {
                    require(decision.clip in clips) {
                        "Unknown GeckoLib animation '${decision.clip}' from ${layer.definition.name}."
                    }
                    layer.controller.play(
                        decision.clip,
                        decision.transitionSeconds ?: layer.definition.transitionSeconds,
                        decision.restart,
                    )
                }
            }
            val pose = layer.controller.update(deltaSeconds, context)
            result = combine(result, pose, layer.definition.weight, layer.definition.blend)
        }
        return result
    }

    fun current(controller: String): String? {
        return layers.firstOrNull { it.definition.name == controller }?.controller?.current
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
    }

    val controllerNames: List<String> get() = layers.map { it.definition.name }

    private data class Layer(
        val definition: GeckoLibControllerDefinition,
        val controller: SkeletalAnimationController,
    )

    private companion object {
        const val MAX_CONTROLLERS = 1_024
    }
}

/**
 * Generation-owned instance cache. A content generation closes this cache
 * before releasing controller predicates or animation documents.
 */
class GeckoLibAnimatableCache<K : Any> : AutoCloseable {
    private val entries = linkedMapOf<K, GeckoLibControllerSet>()
    private var closed = false

    @Synchronized
    fun getOrPut(key: K, factory: () -> GeckoLibControllerSet): GeckoLibControllerSet {
        check(!closed) { "GeckoLib animatable cache is closed." }
        return entries.getOrPut(key, factory)
    }

    @Synchronized
    fun remove(key: K): GeckoLibControllerSet? = entries.remove(key)

    @get:Synchronized
    val size get() = entries.size

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries.clear()
    }
}

private fun combine(
    base: SkeletalPose,
    layer: SkeletalPose,
    weight: Float,
    blend: GeckoLibLayerBlend,
): SkeletalPose {
    if (weight <= 0.0f) return base
    val names = base.bones.keys + layer.bones.keys
    return SkeletalPose(names.associateWith { name ->
        val current = base.bones[name] ?: SkeletalBonePose()
        val overlay = layer.bones[name] ?: return@associateWith current
        when (blend) {
            GeckoLibLayerBlend.REPLACE -> SkeletalBonePose(
                rotation = lerp(current.rotation, overlay.rotation, weight),
                translation = lerp(current.translation, overlay.translation, weight),
                scale = lerp(current.scale, overlay.scale, weight),
            )
            GeckoLibLayerBlend.ADD -> SkeletalBonePose(
                rotation = add(current.rotation, multiply(overlay.rotation, weight)),
                translation = add(current.translation, multiply(overlay.translation, weight)),
                scale = multiply(current.scale, lerp(Vec3f(1.0f), overlay.scale, weight)),
            )
        }
    })
}

private fun add(left: Vec3f, right: Vec3f) = Vec3f(left.x + right.x, left.y + right.y, left.z + right.z)
private fun multiply(left: Vec3f, right: Vec3f) = Vec3f(left.x * right.x, left.y * right.y, left.z * right.z)
private fun multiply(value: Vec3f, scalar: Float) = Vec3f(value.x * scalar, value.y * scalar, value.z * scalar)
private fun lerp(from: Vec3f, to: Vec3f, delta: Float) = Vec3f(
    from.x + (to.x - from.x) * delta,
    from.y + (to.y - from.y) * delta,
    from.z + (to.z - from.z) * delta,
)
