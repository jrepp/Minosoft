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
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationController
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationControllerSnapshot
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalBonePose
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalPose
import de.bixilon.minosoft.data.entities.EntityAnimations
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

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
            put("q.anim_time", ageSeconds.toDouble())
            put("query.is_moving", if (moving) 1.0 else 0.0)
            put("q.is_moving", if (moving) 1.0 else 0.0)
        },
        random = random,
    )

    private companion object {
        const val MAX_STATE_VALUES = 4_096
    }
}

/**
 * Declares one protocol-level tracked-data value required by an adapted
 * controller predicate. Renderers read only declared indices rather than
 * scanning the complete metadata range every frame.
 */
data class GeckoLibTrackedDataInput(
    val name: String,
    val index: Int,
    val defaultValue: Double = 0.0,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) {
            "GeckoLib tracked-data input names must contain 1..$MAX_NAME_LENGTH characters."
        }
        require(index in 0..MAX_TRACKED_DATA_INDEX) {
            "GeckoLib tracked-data index must be between 0 and $MAX_TRACKED_DATA_INDEX."
        }
        require(defaultValue.isFinite()) {
            "GeckoLib tracked-data default values must be finite."
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 256
        const val MAX_TRACKED_DATA_INDEX = 254
    }
}

sealed interface GeckoLibHostStateQuery {
    data class RandomInteger(val bound: Int) : GeckoLibHostStateQuery {
        init {
            require(bound in 1..MAX_RANDOM_BOUND) {
                "GeckoLib host random bound must be within 1..$MAX_RANDOM_BOUND."
            }
        }
    }

    data class EntityType(val identifier: ResourceLocation) : GeckoLibHostStateQuery

    data class NearbyPlayer(
        val range: Double,
        val horizontalExpansion: Double = range,
        val verticalExpansion: Double = range,
    ) : GeckoLibHostStateQuery {
        init {
            require(
                range.isFinite() && range in 0.0..MAX_QUERY_DISTANCE &&
                    horizontalExpansion.isFinite() && horizontalExpansion in 0.0..MAX_QUERY_DISTANCE &&
                    verticalExpansion.isFinite() && verticalExpansion in 0.0..MAX_QUERY_DISTANCE
            ) {
                "GeckoLib nearby-player distances must be finite and within 0..$MAX_QUERY_DISTANCE."
            }
        }
    }

    private companion object {
        const val MAX_RANDOM_BOUND = 1_000_000
        const val MAX_QUERY_DISTANCE = 1_024.0
    }
}

/**
 * Declares one bounded value that must be resolved from the host object or
 * world before a dependent-mod controller predicate runs. The query is an
 * immutable DTO rather than an owner callback, so retained generations cannot
 * invoke replacement adapter code while resolving render state.
 */
data class GeckoLibHostStateInput(
    val name: String,
    val query: GeckoLibHostStateQuery,
    val defaultValue: Double = 0.0,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) {
            "GeckoLib host-state input names must contain 1..$MAX_NAME_LENGTH characters."
        }
        require(defaultValue.isFinite()) {
            "GeckoLib host-state default values must be finite."
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 256
    }
}

object GeckoLibHostEvents {
    fun entityAnimation(animation: EntityAnimations) =
        ResourceLocation("minecraft", "entity_animation/${animation.name.lowercase()}")
}

sealed interface GeckoLibControllerDecision {
    data object Keep : GeckoLibControllerDecision
    data object Stop : GeckoLibControllerDecision
    data class Play(
        val clip: String,
        val transitionSeconds: Float? = null,
        val restart: Boolean = false,
    ) : GeckoLibControllerDecision
    data class PlayRaw(
        val animation: GeckoLibRawAnimation,
        val transitionSeconds: Float? = null,
        val restart: Boolean = false,
    ) : GeckoLibControllerDecision
}

fun interface GeckoLibAnimationPredicate {
    fun decide(state: GeckoLibAnimationState, currentClip: String?): GeckoLibControllerDecision
}

fun interface GeckoLibKeyframeListener {
    fun onEvent(controller: String, event: SkeletalAnimationEvent)
}

data class GeckoLibControllerKeyframeEvent(
    val controller: String,
    val animation: String,
    val animationTimeSeconds: Float,
    val state: GeckoLibAnimationState,
    val keyframe: SkeletalAnimationEvent,
)

fun interface GeckoLibSoundKeyframeHandler {
    fun handle(event: GeckoLibControllerKeyframeEvent)
}

fun interface GeckoLibParticleKeyframeHandler {
    fun handle(event: GeckoLibControllerKeyframeEvent)
}

fun interface GeckoLibCustomInstructionKeyframeHandler {
    fun handle(event: GeckoLibControllerKeyframeEvent)
}

fun interface GeckoLibAnimationSpeedHandler {
    fun speed(state: GeckoLibAnimationState): Double
}

fun interface GeckoLibEasingOverrideHandler {
    fun easing(state: GeckoLibAnimationState): String?
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
    val keyframeListener: GeckoLibKeyframeListener? = null,
    val soundKeyframeHandler: GeckoLibSoundKeyframeHandler? = null,
    val particleKeyframeHandler: GeckoLibParticleKeyframeHandler? = null,
    val customInstructionKeyframeHandler: GeckoLibCustomInstructionKeyframeHandler? = null,
    val animationSpeedHandler: GeckoLibAnimationSpeedHandler = GeckoLibAnimationSpeedHandler { 1.0 },
    val easingOverrideHandler: GeckoLibEasingOverrideHandler = GeckoLibEasingOverrideHandler { null },
    val triggerableAnimations: Map<String, String> = emptyMap(),
    val triggerableRawAnimations: Map<String, GeckoLibRawAnimation> = emptyMap(),
    val receiveTriggeredAnimations: Boolean = false,
    val trackedDataInputs: List<GeckoLibTrackedDataInput> = emptyList(),
    val hostStateInputs: List<GeckoLibHostStateInput> = emptyList(),
    val eventTriggers: Map<ResourceLocation, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "GeckoLib controller name must not be blank." }
        require(transitionSeconds.isFinite() && transitionSeconds >= 0.0f) {
            "GeckoLib controller transition must be finite and non-negative."
        }
        require(weight.isFinite() && weight in 0.0f..1.0f) {
            "GeckoLib controller weight must be within 0..1."
        }
        require(triggerableAnimations.size + triggerableRawAnimations.size <= MAX_TRIGGERABLE_ANIMATIONS) {
            "GeckoLib controller exceeds the $MAX_TRIGGERABLE_ANIMATIONS triggerable animation limit."
        }
        require((triggerableAnimations.keys + triggerableRawAnimations.keys).all { it.isNotBlank() && it.length <= MAX_NAME_LENGTH }) {
            "GeckoLib trigger names must contain 1..$MAX_NAME_LENGTH characters."
        }
        require(triggerableAnimations.keys.intersect(triggerableRawAnimations.keys).isEmpty()) {
            "GeckoLib clip and raw-animation triggers must use distinct names."
        }
        require(trackedDataInputs.size <= MAX_TRACKED_DATA_INPUTS) {
            "GeckoLib controller exceeds the $MAX_TRACKED_DATA_INPUTS tracked-data input limit."
        }
        require(trackedDataInputs.map(GeckoLibTrackedDataInput::name).distinct().size == trackedDataInputs.size) {
            "GeckoLib tracked-data input names must be unique per controller."
        }
        require(hostStateInputs.size <= MAX_HOST_STATE_INPUTS) {
            "GeckoLib controller exceeds the $MAX_HOST_STATE_INPUTS host-state input limit."
        }
        require(hostStateInputs.map(GeckoLibHostStateInput::name).distinct().size == hostStateInputs.size) {
            "GeckoLib host-state input names must be unique per controller."
        }
        require(
            trackedDataInputs.map(GeckoLibTrackedDataInput::name).toSet()
                .intersect(hostStateInputs.map(GeckoLibHostStateInput::name).toSet())
                .isEmpty()
        ) {
            "GeckoLib tracked-data and host-state inputs must use distinct names."
        }
        require(eventTriggers.size <= MAX_EVENT_TRIGGERS) {
            "GeckoLib controller exceeds the $MAX_EVENT_TRIGGERS host-event trigger limit."
        }
        require(eventTriggers.values.all { it in triggerableAnimations || it in triggerableRawAnimations }) {
            "GeckoLib host events must reference a trigger declared by the same controller."
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 256
        const val MAX_TRIGGERABLE_ANIMATIONS = 4_096
        const val MAX_TRACKED_DATA_INPUTS = 255
        const val MAX_HOST_STATE_INPUTS = 255
        const val MAX_EVENT_TRIGGERS = 4_096
    }
}

sealed interface GeckoLibResumeSnapshot {
    data class Raw(val animation: GeckoLibRawAnimation) : GeckoLibResumeSnapshot
    data class Clip(val animation: String) : GeckoLibResumeSnapshot
}

data class GeckoLibTriggeredSnapshot(
    val resume: GeckoLibResumeSnapshot?,
)

data class GeckoLibQueuedAnimationSnapshot(
    val stages: List<GeckoLibRawAnimationStage>,
    val transitionSeconds: Float,
    val stageIndex: Int,
    val stageStarted: Boolean,
    val waitRemainingSeconds: Float,
    val completedCycles: Int,
    val held: Boolean,
) {
    init {
        require(stages.isNotEmpty()) { "A queued GeckoLib snapshot must contain at least one stage." }
        require(transitionSeconds.isFinite() && transitionSeconds >= 0.0f) {
            "GeckoLib queue snapshot transition must be finite and non-negative."
        }
        require(stageIndex in 0..stages.size) { "GeckoLib queue snapshot stage index is out of bounds." }
        require(waitRemainingSeconds.isFinite() && waitRemainingSeconds >= 0.0f) {
            "GeckoLib queue snapshot wait must be finite and non-negative."
        }
        require(completedCycles >= 0) { "GeckoLib queue snapshot cycles must be non-negative." }
    }
}

data class GeckoLibControllerLayerSnapshot(
    val controller: SkeletalAnimationControllerSnapshot,
    val triggered: GeckoLibTriggeredSnapshot?,
    val queue: GeckoLibQueuedAnimationSnapshot?,
    val lastPose: SkeletalPose,
    val currentRaw: GeckoLibRawAnimation?,
    val rawFinished: Boolean,
    val forceRawReload: Boolean,
)

data class GeckoLibControllerSetSnapshot(
    val layers: Map<String, GeckoLibControllerLayerSnapshot>,
)

/**
 * Bounded immutable diagnostics for one retained controller layer.
 *
 * This deliberately excludes poses, callbacks, and live controller objects so
 * debug consumers cannot retain an adapter generation or race render updates.
 */
data class GeckoLibControllerInspection(
    val name: String,
    val currentClip: String?,
    val elapsedSeconds: Float,
    val remainingSeconds: Float?,
    val transitionElapsedSeconds: Float,
    val transitionDurationSeconds: Float,
    val triggered: Boolean,
    val queued: Boolean,
    val queueStageIndex: Int?,
    val queueStageCount: Int?,
    val queueCurrentAnimation: String?,
    val queueWaitRemainingSeconds: Float?,
    val completedCycles: Int?,
    val held: Boolean,
    val rawFinished: Boolean,
)

data class GeckoLibControllerSetInspection(
    val controllerCount: Int,
    val controllers: List<GeckoLibControllerInspection>,
) {
    val truncated get() = controllers.size < controllerCount

    companion object {
        val EMPTY = GeckoLibControllerSetInspection(0, emptyList())
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
    var eventConsumer: ((String, SkeletalAnimationEvent) -> Unit)? = null

    val trackedDataInputs: List<GeckoLibTrackedDataInput> = mergeTrackedDataInputs(definitions)
    val hostStateInputs: List<GeckoLibHostStateInput> = mergeHostStateInputs(definitions)

    private val layers = definitions.also {
        require(it.size <= MAX_CONTROLLERS) {
            "GeckoLib controller set exceeds the $MAX_CONTROLLERS controller limit."
        }
    }.map { definition ->
        definition.initialClip?.let {
            require(it in clips) { "Unknown initial GeckoLib animation '$it' for ${definition.name}." }
        }
        for ((trigger, clip) in definition.triggerableAnimations) {
            require(clip in clips) {
                "Unknown GeckoLib animation '$clip' for trigger '$trigger' on ${definition.name}."
            }
        }
        for ((trigger, animation) in definition.triggerableRawAnimations) {
            validateRaw(animation, clips, "trigger '$trigger' on ${definition.name}")
        }
        Layer(definition, SkeletalAnimationController(clips, definition.initialClip))
    }

    init {
        require(definitions.map { it.name }.distinct().size == definitions.size) {
            "GeckoLib controller names must be unique."
        }
        require(
            trackedDataInputs.map(GeckoLibTrackedDataInput::name).toSet()
                .intersect(hostStateInputs.map(GeckoLibHostStateInput::name).toSet())
                .isEmpty()
        ) {
            "GeckoLib tracked-data and host-state inputs must use distinct names across controllers."
        }
    }

    fun resolveTrackedData(reader: (Int) -> Any?): Map<String, Double> {
        if (trackedDataInputs.isEmpty()) return emptyMap()
        return trackedDataInputs.associate { input ->
            val value = when (val raw = reader(input.index)) {
                is Boolean -> if (raw) 1.0 else 0.0
                is Number -> raw.toDouble().takeIf(Double::isFinite)
                else -> null
            } ?: input.defaultValue
            input.name to value
        }
    }

    fun resolveHostState(reader: (GeckoLibHostStateInput) -> Double?): Map<String, Double> {
        if (hostStateInputs.isEmpty()) return emptyMap()
        return hostStateInputs.associate { input ->
            val value = reader(input)?.takeIf(Double::isFinite) ?: input.defaultValue
            input.name to value
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
        val result = linkedMapOf<String, SkeletalBonePose>()
        for (layer in layers) {
            if (layer.triggered == null || layer.definition.receiveTriggeredAnimations) {
                when (val decision = layer.definition.predicate.decide(state, layer.controller.current)) {
                    GeckoLibControllerDecision.Keep -> Unit
                    GeckoLibControllerDecision.Stop -> {
                        layer.queue = null
                        layer.triggered = null
                        layer.controller.stop(layer.lastPose)
                        layer.rawFinished = layer.currentRaw != null
                    }
                    is GeckoLibControllerDecision.Play -> {
                        require(decision.clip in clips) {
                            "Unknown GeckoLib animation '${decision.clip}' from ${layer.definition.name}."
                        }
                        layer.controller.play(
                            decision.clip,
                            decision.transitionSeconds ?: layer.definition.transitionSeconds,
                            decision.restart,
                            transitionSourcePose = layer.lastPose,
                        )
                        layer.queue = null
                        layer.triggered = null
                        layer.currentRaw = null
                        layer.rawFinished = false
                        layer.forceRawReload = false
                    }
                    is GeckoLibControllerDecision.PlayRaw -> {
                        layer.triggered = null
                        startQueue(
                            layer,
                            decision.animation,
                            decision.transitionSeconds ?: layer.definition.transitionSeconds,
                            decision.restart,
                        )
                    }
                }
            }
            val speed = layer.definition.animationSpeedHandler.speed(state)
            require(speed.isFinite() && speed in 0.0..MAX_ANIMATION_SPEED) {
                "GeckoLib animation speed for ${layer.definition.name} must be finite and within 0..$MAX_ANIMATION_SPEED."
            }
            val easingOverride = layer.definition.easingOverrideHandler.easing(state)
            require(easingOverride == null || easingOverride.isNotBlank() && easingOverride.length <= MAX_EASING_NAME_LENGTH) {
                "GeckoLib easing override for ${layer.definition.name} must contain 1..$MAX_EASING_NAME_LENGTH characters."
            }
            val scaledDelta = (deltaSeconds * speed).toFloat()
            val pose = if (layer.queue == null) {
                layer.controller.update(
                    deltaSeconds = scaledDelta,
                    context = context,
                    eventConsumer = { event -> dispatch(layer, state, event) },
                    easingOverride = easingOverride,
                    easingResolver = GeckoLibEasingRegistry,
                ).also { layer.lastPose = it }
            } else {
                updateQueue(layer, scaledDelta, context, state, easingOverride)
            }
            combine(result, pose, layer.definition.weight, layer.definition.blend)
        }
        return SkeletalPose(result)
    }

    fun current(controller: String): String? {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        return layer.controller.current
    }

    fun trigger(animation: String): Boolean {
        for (layer in layers) {
            if (trigger(layer, animation)) return true
        }
        return false
    }

    fun trigger(controller: String, animation: String): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller } ?: return false
        return trigger(layer, animation)
    }

    fun triggerEvent(event: ResourceLocation): Int {
        var triggered = 0
        for (layer in layers) {
            val animation = layer.definition.eventTriggers[event] ?: continue
            if (trigger(layer, animation)) triggered++
        }
        return triggered
    }

    fun isPlayingTriggeredAnimation(controller: String): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        return layer.triggered != null
    }

    fun isPlayingQueuedAnimation(controller: String): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        return layer.queue != null
    }

    fun hasAnimationFinished(controller: String): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        return layer.currentRaw != null && layer.rawFinished
    }

    fun currentRawAnimation(controller: String): GeckoLibRawAnimation? {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        return layer.currentRaw?.let(GeckoLibRawAnimation::copyOf)
    }

    fun isCurrentAnimation(controller: String, animation: GeckoLibRawAnimation): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        return layer.currentRaw == animation
    }

    fun isCurrentAnimationStage(controller: String, animation: String): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller }
            ?: throw IllegalArgumentException("Unknown GeckoLib controller '$controller'.")
        val queue = layer.queue ?: return false
        return queue.stages.getOrNull(queue.stageIndex)?.animation == animation
    }

    fun resetCurrentAnimation(controller: String): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller } ?: return false
        layer.forceRawReload = true
        return true
    }

    fun play(
        controller: String,
        animation: GeckoLibRawAnimation,
        transitionSeconds: Float? = null,
        restart: Boolean = false,
    ): Boolean {
        val layer = layers.firstOrNull { it.definition.name == controller } ?: return false
        startQueue(layer, animation, transitionSeconds ?: layer.definition.transitionSeconds, restart)
        return true
    }

    val controllerNames: List<String> get() = layers.map { it.definition.name }

    /**
     * Copies a small read-only controller view for diagnostics and acceptance.
     * The returned DTO never changes when the live controller advances.
     */
    fun inspect(maxControllers: Int = MAX_CONTROLLER_INSPECTIONS): GeckoLibControllerSetInspection {
        require(maxControllers in 0..MAX_CONTROLLER_INSPECTIONS) {
            "GeckoLib controller inspection limit must be within 0..$MAX_CONTROLLER_INSPECTIONS."
        }
        return GeckoLibControllerSetInspection(
            controllerCount = layers.size,
            controllers = layers.take(maxControllers).map { layer ->
                val queue = layer.queue
                GeckoLibControllerInspection(
                    name = layer.definition.name,
                    currentClip = layer.controller.current,
                    elapsedSeconds = layer.controller.elapsedSeconds,
                    remainingSeconds = layer.controller.remainingSeconds,
                    transitionElapsedSeconds = layer.controller.transitionElapsedSeconds,
                    transitionDurationSeconds = layer.controller.transitionDurationSeconds,
                    triggered = layer.triggered != null,
                    queued = queue != null,
                    queueStageIndex = queue?.stageIndex,
                    queueStageCount = queue?.stages?.size,
                    queueCurrentAnimation = queue?.stages
                        ?.getOrNull(queue.stageIndex)
                        ?.takeUnless(GeckoLibRawAnimationStage::isWait)
                        ?.animation,
                    queueWaitRemainingSeconds = queue
                        ?.takeIf { it.stages.getOrNull(it.stageIndex)?.isWait == true }
                        ?.waitRemainingSeconds,
                    completedCycles = queue?.completedCycles,
                    held = queue?.held == true,
                    rawFinished = layer.rawFinished,
                )
            },
        )
    }

    fun snapshot() = GeckoLibControllerSetSnapshot(
        layers.associate { layer ->
            layer.definition.name to GeckoLibControllerLayerSnapshot(
                controller = layer.controller.snapshot(),
                triggered = layer.triggered?.let { triggered ->
                    GeckoLibTriggeredSnapshot(
                        when (val resume = triggered.resume) {
                            is ResumeAnimation.Raw -> GeckoLibResumeSnapshot.Raw(
                                GeckoLibRawAnimation.copyOf(resume.animation),
                            )
                            is ResumeAnimation.Clip -> GeckoLibResumeSnapshot.Clip(resume.animation)
                            null -> null
                        },
                    )
                },
                queue = layer.queue?.let { queue ->
                    GeckoLibQueuedAnimationSnapshot(
                        stages = queue.stages.toList(),
                        transitionSeconds = queue.transitionSeconds,
                        stageIndex = queue.stageIndex,
                        stageStarted = queue.stageStarted,
                        waitRemainingSeconds = queue.waitRemainingSeconds,
                        completedCycles = queue.completedCycles,
                        held = queue.held,
                    )
                },
                lastPose = SkeletalPose(layer.lastPose.bones.toMap()),
                currentRaw = layer.currentRaw?.let(GeckoLibRawAnimation::copyOf),
                rawFinished = layer.rawFinished,
                forceRawReload = layer.forceRawReload,
            )
        }.toMap(),
    )

    /**
     * Migrates matching named controllers after a retained model generation
     * swap. Incompatible or removed clips reset only their owning layer.
     *
     * @return number of restored controller layers
     */
    fun restore(snapshot: GeckoLibControllerSetSnapshot): Int {
        var restored = 0
        for (layer in layers) {
            val state = snapshot.layers[layer.definition.name] ?: continue
            if (!canRestore(state)) continue
            if (!layer.controller.restore(state.controller)) continue
            layer.triggered = state.triggered?.let { triggered ->
                TriggeredAnimation(
                    when (val resume = triggered.resume) {
                        is GeckoLibResumeSnapshot.Raw -> ResumeAnimation.Raw(
                            GeckoLibRawAnimation.copyOf(resume.animation),
                        )
                        is GeckoLibResumeSnapshot.Clip -> ResumeAnimation.Clip(resume.animation)
                        null -> null
                    },
                )
            }
            layer.queue = state.queue?.let { queue ->
                QueuedAnimation(
                    stages = queue.stages.toList(),
                    transitionSeconds = queue.transitionSeconds,
                    stageIndex = queue.stageIndex,
                    stageStarted = queue.stageStarted,
                    waitRemainingSeconds = queue.waitRemainingSeconds,
                    completedCycles = queue.completedCycles,
                    held = queue.held,
                )
            }
            layer.lastPose = SkeletalPose(state.lastPose.bones.toMap())
            layer.currentRaw = state.currentRaw?.let(GeckoLibRawAnimation::copyOf)
            layer.rawFinished = state.rawFinished
            layer.forceRawReload = state.forceRawReload
            restored++
        }
        return restored
    }

    private fun canRestore(snapshot: GeckoLibControllerLayerSnapshot): Boolean {
        if (snapshot.controller.current != null && snapshot.controller.current !in clips) return false
        val rawAnimations = buildList {
            snapshot.currentRaw?.let(::add)
            val resume = snapshot.triggered?.resume
            if (resume is GeckoLibResumeSnapshot.Raw) add(resume.animation)
        }
        try {
            rawAnimations.forEach { validateRaw(it, clips, "controller migration") }
        } catch (_: IllegalArgumentException) {
            return false
        }
        val resume = snapshot.triggered?.resume
        if (resume is GeckoLibResumeSnapshot.Clip && resume.animation !in clips) return false
        val queue = snapshot.queue ?: return true
        if (queue.stageIndex !in 0..queue.stages.size) return false
        for (stage in queue.stages) {
            if (stage.isWait) continue
            if (stage.animation !in clips) return false
            if (stage.customLoopType != null && !GeckoLibLoopTypeRegistry.contains(stage.customLoopType)) return false
        }
        return true
    }

    private fun trigger(layer: Layer, animation: String): Boolean {
        val raw = layer.definition.triggerableRawAnimations[animation]
            ?: layer.definition.triggerableAnimations[animation]?.let {
                GeckoLibRawAnimation.begin().thenPlay(it)
            }
            ?: return false
        val resume = layer.triggered?.resume ?: when {
            layer.currentRaw != null -> ResumeAnimation.Raw(GeckoLibRawAnimation.copyOf(layer.currentRaw!!))
            layer.controller.current != null -> ResumeAnimation.Clip(layer.controller.current!!)
            else -> null
        }
        startQueue(layer, raw, layer.definition.transitionSeconds, restart = true)
        layer.triggered = TriggeredAnimation(resume)
        return true
    }

    private fun startQueue(
        layer: Layer,
        animation: GeckoLibRawAnimation,
        transitionSeconds: Float,
        restart: Boolean,
    ) {
        require(transitionSeconds.isFinite() && transitionSeconds >= 0.0f) {
            "GeckoLib raw animation transition must be finite and non-negative."
        }
        validateRaw(animation, clips, "controller ${layer.definition.name}")
        val stages = animation.stages
        require(stages.isNotEmpty()) { "GeckoLib raw animation must contain at least one stage." }
        if (!restart && !layer.forceRawReload && layer.currentRaw == animation) return
        val snapshot = GeckoLibRawAnimation.copyOf(animation)
        layer.currentRaw = snapshot
        layer.rawFinished = false
        layer.forceRawReload = false
        layer.queue = QueuedAnimation(snapshot.stages, transitionSeconds)
        beginStage(layer, layer.queue!!)
    }

    private fun updateQueue(
        layer: Layer,
        deltaSeconds: Float,
        context: SkeletalExpressionContext,
        state: GeckoLibAnimationState,
        easingOverride: String?,
    ): SkeletalPose {
        var remaining = deltaSeconds
        var pose = layer.lastPose
        var advances = 0
        while (true) {
            require(++advances <= MAX_STAGE_ADVANCES_PER_UPDATE) {
                "GeckoLib raw animation advanced too many stages in one update."
            }
            val queue = layer.queue ?: return pose
            if (queue.held) return pose
            if (queue.stageIndex >= queue.stages.size) {
                finishQueue(layer, queue)
                return pose
            }
            val stage = queue.stages[queue.stageIndex]
            if (!queue.stageStarted) beginStage(layer, queue)
            if (stage.isWait) {
                val consumed = minOf(remaining, queue.waitRemainingSeconds)
                queue.waitRemainingSeconds -= consumed
                remaining -= consumed
                if (queue.waitRemainingSeconds > 0.0f) return pose
                queue.stageIndex++
                queue.stageStarted = false
                if (remaining <= 0.0f) {
                    if (queue.stageIndex >= queue.stages.size) finishQueue(layer, queue)
                    else beginStage(layer, queue)
                    return pose
                }
                continue
            }

            val stageRemaining = layer.controller.remainingSeconds
            val consumed = stageRemaining?.let { minOf(remaining, it) } ?: remaining
            pose = layer.controller.update(
                deltaSeconds = consumed,
                context = context,
                eventConsumer = { event -> dispatch(layer, state, event) },
                easingOverride = easingOverride,
                easingResolver = GeckoLibEasingRegistry,
            )
            layer.lastPose = pose
            remaining -= consumed
            if (stageRemaining == null || !layer.controller.finished) return pose

            queue.completedCycles++
            when (loopDecision(layer, queue, stage, state)) {
                GeckoLibLoopDecision.REPEAT -> {
                    queue.stageStarted = false
                }
                GeckoLibLoopDecision.HOLD -> {
                    queue.held = true
                    return pose
                }
                GeckoLibLoopDecision.ADVANCE -> {
                    queue.stageIndex++
                    queue.stageStarted = false
                    queue.completedCycles = 0
                }
            }
            if (remaining <= 0.0f) {
                if (queue.stageIndex >= queue.stages.size) finishQueue(layer, queue)
                else beginStage(layer, queue)
                return pose
            }
        }
    }

    private fun beginStage(layer: Layer, queue: QueuedAnimation) {
        val stage = queue.stages.getOrNull(queue.stageIndex) ?: return
        queue.stageStarted = true
        if (stage.isWait) {
            queue.waitRemainingSeconds = stage.additionalTicks / TICKS_PER_SECOND
            return
        }
        layer.controller.play(
            name = stage.animation,
            transitionSeconds = queue.transitionSeconds,
            restart = true,
            loopOverride = loopOverride(stage),
            transitionSourcePose = layer.lastPose,
        )
    }

    private fun finishQueue(layer: Layer, queue: QueuedAnimation) {
        if (layer.queue !== queue) return
        layer.queue = null
        layer.rawFinished = true
        val triggered = layer.triggered
        if (triggered != null) {
            layer.triggered = null
            when (val resume = triggered.resume) {
                is ResumeAnimation.Raw -> {
                    layer.forceRawReload = true
                    startQueue(layer, resume.animation, layer.definition.transitionSeconds, restart = false)
                }
                is ResumeAnimation.Clip -> {
                    layer.currentRaw = null
                    layer.rawFinished = false
                    layer.controller.play(
                        resume.animation,
                        layer.definition.transitionSeconds,
                        restart = true,
                        transitionSourcePose = layer.lastPose,
                    )
                }
                null -> layer.controller.stop(layer.lastPose)
            }
        } else {
            layer.controller.stop(layer.lastPose)
        }
    }

    private fun loopOverride(stage: GeckoLibRawAnimationStage): de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop? {
        if (stage.customLoopType != null) return de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.ONCE
        if (stage.loopType == GeckoLibRawLoopType.DEFAULT && clips.getValue(stage.animation).sourceLoopType != null) {
            return de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.ONCE
        }
        return stage.loopType.skeletalLoop()
    }

    private fun loopDecision(
        layer: Layer,
        queue: QueuedAnimation,
        stage: GeckoLibRawAnimationStage,
        state: GeckoLibAnimationState,
    ): GeckoLibLoopDecision {
        val custom = stage.customLoopType
            ?: clips.getValue(stage.animation).sourceLoopType?.takeIf { stage.loopType == GeckoLibRawLoopType.DEFAULT }
        if (custom != null) {
            return GeckoLibLoopTypeRegistry.decide(
                custom,
                GeckoLibLoopContext(
                    controller = layer.definition.name,
                    animation = stage.animation,
                    state = state,
                    completedCycles = queue.completedCycles,
                ),
            ) ?: GeckoLibLoopDecision.ADVANCE
        }
        return when (stage.loopType) {
            GeckoLibRawLoopType.PLAY_ONCE -> GeckoLibLoopDecision.ADVANCE
            GeckoLibRawLoopType.HOLD_ON_LAST_FRAME -> GeckoLibLoopDecision.HOLD
            GeckoLibRawLoopType.LOOP -> GeckoLibLoopDecision.REPEAT
            GeckoLibRawLoopType.DEFAULT -> when (clips.getValue(stage.animation).loop) {
                de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.ONCE -> GeckoLibLoopDecision.ADVANCE
                de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.LOOP -> GeckoLibLoopDecision.REPEAT
                de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.HOLD -> GeckoLibLoopDecision.HOLD
            }
        }
    }

    private fun dispatch(
        layer: Layer,
        state: GeckoLibAnimationState,
        keyframe: SkeletalAnimationEvent,
    ) {
        val definition = layer.definition
        definition.keyframeListener?.onEvent(definition.name, keyframe)
        val event = GeckoLibControllerKeyframeEvent(
            controller = definition.name,
            animation = layer.controller.current.orEmpty(),
            animationTimeSeconds = layer.controller.elapsedSeconds,
            state = state,
            keyframe = keyframe,
        )
        when (keyframe.type) {
            SkeletalAnimationEventType.SOUND -> definition.soundKeyframeHandler?.handle(event)
            SkeletalAnimationEventType.PARTICLE -> definition.particleKeyframeHandler?.handle(event)
            SkeletalAnimationEventType.CUSTOM_INSTRUCTION -> definition.customInstructionKeyframeHandler?.handle(event)
        }
        eventConsumer?.invoke(event.animation, keyframe)
    }

    private data class Layer(
        val definition: GeckoLibControllerDefinition,
        val controller: SkeletalAnimationController,
        var triggered: TriggeredAnimation? = null,
        var queue: QueuedAnimation? = null,
        var lastPose: SkeletalPose = SkeletalPose(emptyMap()),
        var currentRaw: GeckoLibRawAnimation? = null,
        var rawFinished: Boolean = false,
        var forceRawReload: Boolean = false,
    )

    private data class TriggeredAnimation(
        val resume: ResumeAnimation?,
    )

    private sealed interface ResumeAnimation {
        data class Raw(val animation: GeckoLibRawAnimation) : ResumeAnimation
        data class Clip(val animation: String) : ResumeAnimation
    }

    private data class QueuedAnimation(
        val stages: List<GeckoLibRawAnimationStage>,
        val transitionSeconds: Float,
        var stageIndex: Int = 0,
        var stageStarted: Boolean = false,
        var waitRemainingSeconds: Float = 0.0f,
        var completedCycles: Int = 0,
        var held: Boolean = false,
    )

    private companion object {
        const val MAX_CONTROLLERS = 1_024
        const val MAX_ANIMATION_SPEED = 1_024.0
        const val MAX_EASING_NAME_LENGTH = 256
        const val MAX_STAGE_ADVANCES_PER_UPDATE = 16_384
        const val MAX_CONTROLLER_INSPECTIONS = 64
        const val TICKS_PER_SECOND = 20.0f
    }
}

private fun mergeTrackedDataInputs(
    definitions: List<GeckoLibControllerDefinition>,
): List<GeckoLibTrackedDataInput> {
    val inputs = linkedMapOf<String, GeckoLibTrackedDataInput>()
    for (definition in definitions) {
        for (input in definition.trackedDataInputs) {
            val previous = inputs.putIfAbsent(input.name, input)
            require(previous == null || previous == input) {
                "Conflicting GeckoLib tracked-data input '${input.name}' across controllers."
            }
        }
    }
    require(inputs.size <= 255) {
        "GeckoLib controller set exceeds the 255 tracked-data input limit."
    }
    return inputs.values.toList()
}

private fun mergeHostStateInputs(
    definitions: List<GeckoLibControllerDefinition>,
): List<GeckoLibHostStateInput> {
    val inputs = linkedMapOf<String, GeckoLibHostStateInput>()
    for (definition in definitions) {
        for (input in definition.hostStateInputs) {
            val previous = inputs.putIfAbsent(input.name, input)
            require(previous == null || previous == input) {
                "Conflicting GeckoLib host-state input '${input.name}' across controllers."
            }
        }
    }
    require(inputs.size <= 255) {
        "GeckoLib controller set exceeds the 255 host-state input limit."
    }
    return inputs.values.toList()
}

private fun validateRaw(
    animation: GeckoLibRawAnimation,
    clips: Map<String, SkeletalAnimationClip>,
    owner: String,
) {
    for (stage in animation.stages) {
        if (!stage.isWait) {
            require(stage.animation in clips) {
                "Unknown GeckoLib animation '${stage.animation}' in $owner."
            }
            require(stage.customLoopType == null || GeckoLibLoopTypeRegistry.contains(stage.customLoopType)) {
                "Unknown GeckoLib loop type '${stage.customLoopType}' in $owner."
            }
        }
    }
}

private fun GeckoLibRawLoopType.skeletalLoop(): de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop? {
    return when (this) {
        GeckoLibRawLoopType.DEFAULT -> null
        GeckoLibRawLoopType.PLAY_ONCE -> de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.ONCE
        GeckoLibRawLoopType.HOLD_ON_LAST_FRAME -> de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.HOLD
        GeckoLibRawLoopType.LOOP -> de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop.LOOP
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
    base: MutableMap<String, SkeletalBonePose>,
    layer: SkeletalPose,
    weight: Float,
    blend: GeckoLibLayerBlend,
): Unit {
    if (weight <= 0.0f) return
    for ((name, overlay) in layer.bones) {
        val current = base[name] ?: SkeletalBonePose()
        base[name] = when (blend) {
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
    }
}

private fun add(left: Vec3f, right: Vec3f) = Vec3f(left.x + right.x, left.y + right.y, left.z + right.z)
private fun multiply(left: Vec3f, right: Vec3f) = Vec3f(left.x * right.x, left.y * right.y, left.z * right.z)
private fun multiply(value: Vec3f, scalar: Float) = Vec3f(value.x * scalar, value.y * scalar, value.z * scalar)
private fun lerp(from: Vec3f, to: Vec3f, delta: Float) = Vec3f(
    from.x + (to.x - from.x) * delta,
    from.y + (to.y - from.y) * delta,
    from.z + (to.z - from.z) * delta,
)
