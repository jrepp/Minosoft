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
import de.bixilon.minosoft.assets.model.skeletal.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeckoLibControllerApiTest {

    @Test
    fun `dependent mod predicates drive concurrent replace and additive layers`() {
        val clips = mapOf(
            "idle" to clip("idle", SkeletalAnimationTarget.TRANSLATION, Vec3f(2.0f, 0.0f, 0.0f)),
            "walk" to clip("walk", SkeletalAnimationTarget.TRANSLATION, Vec3f(10.0f, 0.0f, 0.0f)),
            "wave" to clip("wave", SkeletalAnimationTarget.ROTATION, Vec3f(0.0f, 4.0f, 0.0f)),
        )
        val controllers = GeckoLibControllerSet(
            clips,
            listOf(
                GeckoLibControllerDefinition(
                    name = "locomotion",
                    initialClip = "idle",
                    predicate = GeckoLibAnimationPredicate { state, current ->
                        val desired = if (state.moving) "walk" else "idle"
                        if (desired == current) GeckoLibControllerDecision.Keep
                        else GeckoLibControllerDecision.Play(desired)
                    },
                ),
                GeckoLibControllerDefinition(
                    name = "upper_body",
                    initialClip = "wave",
                    weight = 0.5f,
                    blend = GeckoLibLayerBlend.ADD,
                ),
            ),
        )

        val pose = controllers.update(0.05f, GeckoLibAnimationState(ageSeconds = 1.0f, moving = true))

        assertEquals("walk", controllers.current("locomotion"))
        assertEquals(10.0f, pose.bones.getValue("root").translation.x)
        assertEquals(2.0f, pose.bones.getValue("root").rotation.y)
    }

    @Test
    fun `animatable cache owns controller instances and closes deterministically`() {
        val cache = GeckoLibAnimatableCache<String>()
        val clips = mapOf("idle" to clip("idle", SkeletalAnimationTarget.SCALE, Vec3f(1.0f)))
        val first = cache.getOrPut("entity") {
            GeckoLibControllerSet(clips, listOf(GeckoLibControllerDefinition("main", "idle")))
        }

        assertSame(first, cache.getOrPut("entity") { error("must not recreate") })
        assertEquals(1, cache.size)
        cache.close()
        assertEquals(0, cache.size)
        assertFailsWith<IllegalStateException> {
            cache.getOrPut("other") {
                GeckoLibControllerSet(clips, listOf(GeckoLibControllerDefinition("main", "idle")))
            }
        }
    }

    @Test
    fun `controller dispatches sound particle and custom keyframes across loop boundaries`() {
        val events = listOf(
            SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.SOUND, "test:start"),
            SkeletalAnimationEvent(0.5f, SkeletalAnimationEventType.PARTICLE, "test:dust", "foot"),
            SkeletalAnimationEvent(0.75f, SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "attack"),
        )
        val clips = mapOf(
            "events" to clip(
                "events",
                SkeletalAnimationTarget.TRANSLATION,
                Vec3f.EMPTY,
                events,
            ),
        )
        val received = mutableListOf<Pair<String, SkeletalAnimationEvent>>()
        val controllers = GeckoLibControllerSet(
            clips,
            listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    initialClip = "events",
                    keyframeListener = GeckoLibKeyframeListener { controller, event ->
                        received += controller to event
                    },
                ),
            ),
        )

        controllers.update(0.25f, GeckoLibAnimationState(0.25f))
        controllers.update(0.5f, GeckoLibAnimationState(0.75f))
        controllers.update(0.25f, GeckoLibAnimationState(1.0f))

        assertEquals(
            listOf("test:start", "test:dust", "attack", "test:start"),
            received.map { it.second.payload },
        )
        assertEquals(setOf("main"), received.map { it.first }.toSet())
    }

    @Test
    fun `typed handlers retain particle scripts and controller event context`() {
        val particle = SkeletalAnimationEvent(
            timeSeconds = 0.25f,
            type = SkeletalAnimationEventType.PARTICLE,
            payload = "test:spark",
            locator = "hand",
            preEffectScript = "variable.power = 2;",
        )
        val sound = SkeletalAnimationEvent(0.5f, SkeletalAnimationEventType.SOUND, "test:step")
        val custom = SkeletalAnimationEvent(0.75f, SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "attack")
        val typed = mutableListOf<GeckoLibControllerKeyframeEvent>()
        val controllers = GeckoLibControllerSet(
            clips = mapOf(
                "events" to clip(
                    "events",
                    SkeletalAnimationTarget.TRANSLATION,
                    Vec3f.EMPTY,
                    listOf(particle, sound, custom),
                ),
            ),
            definitions = listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    initialClip = "events",
                    particleKeyframeHandler = GeckoLibParticleKeyframeHandler { typed += it },
                    soundKeyframeHandler = GeckoLibSoundKeyframeHandler { typed += it },
                    customInstructionKeyframeHandler = GeckoLibCustomInstructionKeyframeHandler { typed += it },
                ),
            ),
        )
        val state = GeckoLibAnimationState(ageSeconds = 4.0f, data = mapOf("power" to 2.0))

        controllers.update(0.75f, state)

        assertEquals(listOf(particle, sound, custom), typed.map(GeckoLibControllerKeyframeEvent::keyframe))
        assertEquals(setOf("main"), typed.map(GeckoLibControllerKeyframeEvent::controller).toSet())
        assertEquals(setOf("events"), typed.map(GeckoLibControllerKeyframeEvent::animation).toSet())
        assertEquals(setOf(state), typed.map(GeckoLibControllerKeyframeEvent::state).toSet())
        assertEquals("variable.power = 2;", typed.first().keyframe.preEffectScript)
        assertEquals(0.75f, typed.first().animationTimeSeconds)
    }

    @Test
    fun `animation speed and easing override are evaluated from current state`() {
        val moving = SkeletalAnimationClip(
            name = "moving",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.HOLD,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(
                            SkeletalAnimationKeyframe(
                                0.0f,
                                SkeletalVectorValue.Constant(Vec3f.EMPTY),
                                easing = "easeinquad",
                            ),
                            SkeletalAnimationKeyframe(
                                1.0f,
                                SkeletalVectorValue.Constant(Vec3f(10.0f, 0.0f, 0.0f)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val controllers = GeckoLibControllerSet(
            clips = mapOf("moving" to moving),
            definitions = listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    initialClip = "moving",
                    animationSpeedHandler = GeckoLibAnimationSpeedHandler { state ->
                        if (state.moving) Double.POSITIVE_INFINITY else state.data.getValue("speed")
                    },
                    easingOverrideHandler = GeckoLibEasingOverrideHandler { "easeoutquad" },
                ),
            ),
        )

        val pose = controllers.update(
            deltaSeconds = 0.25f,
            state = GeckoLibAnimationState(0.25f, data = mapOf("speed" to 2.0)),
        )

        assertEquals(7.5f, pose.bones.getValue("root").translation.x)
        assertFailsWith<IllegalArgumentException> {
            controllers.update(
                0.1f,
                GeckoLibAnimationState(0.35f, moving = true, data = mapOf("speed" to 2.0)),
            )
        }
    }

    @Test
    fun `triggered animation preempts its controller then resumes prior clip`() {
        val idle = clip("idle", SkeletalAnimationTarget.TRANSLATION, Vec3f(1.0f, 0.0f, 0.0f))
        val attack = SkeletalAnimationClip(
            name = "attack",
            lengthSeconds = 0.5f,
            loop = SkeletalAnimationLoop.ONCE,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f(9.0f, 0.0f, 0.0f)))),
                    ),
                ),
            ),
        )
        val controllers = GeckoLibControllerSet(
            clips = mapOf("idle" to idle, "attack" to attack),
            definitions = listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    initialClip = "idle",
                    triggerableAnimations = mapOf("attack" to "attack"),
                ),
            ),
        )

        assertFalse(controllers.trigger("missing"))
        assertTrue(controllers.trigger("attack"))
        assertTrue(controllers.isPlayingTriggeredAnimation("main"))
        assertEquals("attack", controllers.current("main"))
        assertEquals(9.0f, controllers.update(0.25f, GeckoLibAnimationState(0.25f)).bones.getValue("root").translation.x)
        assertTrue(controllers.isPlayingTriggeredAnimation("main"))

        assertEquals(9.0f, controllers.update(0.25f, GeckoLibAnimationState(0.5f)).bones.getValue("root").translation.x)
        assertFalse(controllers.isPlayingTriggeredAnimation("main"))
        assertEquals("idle", controllers.current("main"))
        assertEquals(1.0f, controllers.update(0.1f, GeckoLibAnimationState(0.6f)).bones.getValue("root").translation.x)
    }

    @Test
    fun `raw animation builder preserves pinned stage and repeat semantics`() {
        val raw = GeckoLibRawAnimation.begin()
            .thenPlayXTimes("attack", 3)
            .thenWait(4)
            .thenPlayAndHold("recover")
            .thenLoop("idle")

        assertEquals(
            listOf(
                GeckoLibRawLoopType.PLAY_ONCE,
                GeckoLibRawLoopType.PLAY_ONCE,
                GeckoLibRawLoopType.DEFAULT,
                GeckoLibRawLoopType.PLAY_ONCE,
                GeckoLibRawLoopType.HOLD_ON_LAST_FRAME,
                GeckoLibRawLoopType.LOOP,
            ),
            raw.stages.map(GeckoLibRawAnimationStage::loopType),
        )
        assertEquals(listOf(0, 0, 0, 4, 0, 0), raw.stages.map(GeckoLibRawAnimationStage::additionalTicks))
        assertEquals(raw, GeckoLibRawAnimation.copyOf(raw))

        val custom = GeckoLibRawAnimation.begin().then("attack", "test:conditional").stages.single()
        assertEquals(GeckoLibRawLoopType.DEFAULT, custom.loopType)
        assertEquals("test:conditional", custom.customLoopType)
    }

    @Test
    fun `queued raw trigger carries time across clips and waits then resumes`() {
        fun once(name: String, value: Float) = SkeletalAnimationClip(
            name = name,
            lengthSeconds = 0.2f,
            loop = SkeletalAnimationLoop.ONCE,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f(value, 0.0f, 0.0f)))),
                    ),
                ),
            ),
            events = listOf(SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.CUSTOM_INSTRUCTION, name)),
        )
        val idle = clip("idle", SkeletalAnimationTarget.TRANSLATION, Vec3f(1.0f, 0.0f, 0.0f))
        val raw = GeckoLibRawAnimation.begin()
            .then("attack", GeckoLibRawLoopType.PLAY_ONCE)
            .thenWait(2)
            .then("recover", GeckoLibRawLoopType.PLAY_ONCE)
        val events = mutableListOf<String>()
        val controllers = GeckoLibControllerSet(
            clips = mapOf("idle" to idle, "attack" to once("attack", 9.0f), "recover" to once("recover", 5.0f)),
            definitions = listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    initialClip = "idle",
                    triggerableRawAnimations = mapOf("combo" to raw),
                    customInstructionKeyframeHandler = GeckoLibCustomInstructionKeyframeHandler {
                        events += it.keyframe.payload
                    },
                ),
            ),
        )

        assertTrue(controllers.trigger("combo"))
        assertTrue(controllers.isPlayingQueuedAnimation("main"))
        val finalTriggeredPose = controllers.update(0.5f, GeckoLibAnimationState(0.5f))

        assertEquals(5.0f, finalTriggeredPose.bones.getValue("root").translation.x)
        assertEquals(listOf("attack", "recover"), events)
        assertFalse(controllers.isPlayingQueuedAnimation("main"))
        assertFalse(controllers.isPlayingTriggeredAnimation("main"))
        assertEquals("idle", controllers.current("main"))
        assertEquals(1.0f, controllers.update(0.1f, GeckoLibAnimationState(0.6f)).bones.getValue("root").translation.x)
    }

    @Test
    fun `finished raw identity is stable until an explicit reset`() {
        val raw = GeckoLibRawAnimation.begin().thenPlay("once")
        val controllers = GeckoLibControllerSet(
            clips = mapOf("once" to onceClip("once", 0.1f, 3.0f)),
            definitions = listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    predicate = GeckoLibAnimationPredicate { _, _ ->
                        GeckoLibControllerDecision.PlayRaw(raw)
                    },
                ),
            ),
        )

        assertEquals(3.0f, controllers.update(0.1f, GeckoLibAnimationState(0.1f)).bones.getValue("root").translation.x)
        assertTrue(controllers.hasAnimationFinished("main"))
        assertFalse(controllers.isPlayingQueuedAnimation("main"))
        assertTrue(controllers.isCurrentAnimation("main", raw))
        assertEquals(raw, controllers.currentRawAnimation("main"))

        controllers.update(0.1f, GeckoLibAnimationState(0.2f))
        assertTrue(controllers.hasAnimationFinished("main"))
        assertFalse(controllers.isPlayingQueuedAnimation("main"))

        assertTrue(controllers.resetCurrentAnimation("main"))
        controllers.update(0.05f, GeckoLibAnimationState(0.25f))
        assertFalse(controllers.hasAnimationFinished("main"))
        assertTrue(controllers.isPlayingQueuedAnimation("main"))
        assertTrue(controllers.isCurrentAnimationStage("main", "once"))
    }

    @Test
    fun `trigger reloads a preempted base raw animation from its first stage`() {
        val base = GeckoLibRawAnimation.begin().thenPlay("base")
        val triggered = GeckoLibRawAnimation.begin().thenPlay("trigger")
        val controllers = GeckoLibControllerSet(
            clips = mapOf(
                "base" to onceClip("base", 0.2f, 2.0f),
                "trigger" to onceClip("trigger", 0.1f, 9.0f),
            ),
            definitions = listOf(
                GeckoLibControllerDefinition(
                    name = "main",
                    predicate = GeckoLibAnimationPredicate { _, _ ->
                        GeckoLibControllerDecision.PlayRaw(base)
                    },
                    triggerableRawAnimations = mapOf("trigger" to triggered),
                ),
            ),
        )

        controllers.update(0.1f, GeckoLibAnimationState(0.1f))
        assertTrue(controllers.trigger("trigger"))
        assertEquals(9.0f, controllers.update(0.1f, GeckoLibAnimationState(0.2f)).bones.getValue("root").translation.x)

        assertFalse(controllers.isPlayingTriggeredAnimation("main"))
        assertTrue(controllers.isPlayingQueuedAnimation("main"))
        assertTrue(controllers.isCurrentAnimation("main", base))
        assertTrue(controllers.isCurrentAnimationStage("main", "base"))

        controllers.update(0.1f, GeckoLibAnimationState(0.3f))
        assertTrue(controllers.isPlayingQueuedAnimation("main"))
        assertFalse(controllers.hasAnimationFinished("main"))
    }

    @Test
    fun `custom loop type repeats and advances with owned lifecycle`() {
        val cycles = mutableListOf<Int>()
        val registration = GeckoLibLoopTypeRegistry.register("test-owner", "test:twice") { context ->
            cycles += context.completedCycles
            if (context.completedCycles < 2) GeckoLibLoopDecision.REPEAT else GeckoLibLoopDecision.ADVANCE
        }
        try {
            val events = mutableListOf<String>()
            val controllers = GeckoLibControllerSet(
                clips = mapOf(
                    "pulse" to onceClip("pulse", 0.1f, 4.0f, event = true, sourceLoopType = "test:twice"),
                    "done" to onceClip("done", 0.1f, 7.0f, event = true),
                ),
                definitions = listOf(
                    GeckoLibControllerDefinition(
                        name = "main",
                        customInstructionKeyframeHandler = GeckoLibCustomInstructionKeyframeHandler {
                            events += it.keyframe.payload
                        },
                    ),
                ),
            )
            val raw = GeckoLibRawAnimation.begin()
                .thenPlay("pulse")
                .then("done", GeckoLibRawLoopType.PLAY_ONCE)

            assertTrue(controllers.play("main", raw))
            val pose = controllers.update(0.25f, GeckoLibAnimationState(0.25f))

            assertEquals(listOf(1, 2), cycles)
            assertEquals(listOf("pulse", "pulse", "done"), events)
            assertEquals(7.0f, pose.bones.getValue("root").translation.x)
            assertTrue(controllers.isCurrentAnimationStage("main", "done"))
            assertEquals(mapOf("test:twice" to "test-owner"), GeckoLibLoopTypeRegistry.owners())
        } finally {
            registration.close()
        }
        assertEquals(emptyMap(), GeckoLibLoopTypeRegistry.owners())
    }

    @Test
    fun `controller snapshot migrates raw queue time without replaying keyframes`() {
        val moving = SkeletalAnimationClip(
            name = "moving",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.LOOP,
            channels = emptyMap(),
            events = listOf(
                SkeletalAnimationEvent(0.25f, SkeletalAnimationEventType.SOUND, "test:step"),
            ),
        )
        val definitions = listOf(GeckoLibControllerDefinition("main"))
        val source = GeckoLibControllerSet(mapOf("moving" to moving), definitions)
        val raw = GeckoLibRawAnimation.begin().thenLoop("moving")
        assertTrue(source.play("main", raw))
        source.update(0.4f, GeckoLibAnimationState(0.4f))

        val snapshot = source.snapshot()
        val replacement = GeckoLibControllerSet(mapOf("moving" to moving), definitions)
        val replayed = mutableListOf<SkeletalAnimationEvent>()
        replacement.eventConsumer = { _, event -> replayed += event }

        assertEquals(1, replacement.restore(snapshot))
        assertEquals(0.4f, replacement.snapshot().layers.getValue("main").controller.elapsedSeconds)
        replacement.update(0.1f, GeckoLibAnimationState(0.5f))
        assertEquals(0.5f, replacement.snapshot().layers.getValue("main").controller.elapsedSeconds)
        assertEquals(emptyList(), replayed)
        assertTrue(replacement.isCurrentAnimation("main", raw))

        val incompatible = GeckoLibControllerSet(
            mapOf("other" to moving.copy(name = "other")),
            definitions,
        )
        assertEquals(0, incompatible.restore(snapshot))
        assertEquals(null, incompatible.current("main"))
    }

    private fun clip(
        name: String,
        target: SkeletalAnimationTarget,
        value: Vec3f,
        events: List<SkeletalAnimationEvent> = emptyList(),
    ) = SkeletalAnimationClip(
        name = name,
        lengthSeconds = 1.0f,
        loop = SkeletalAnimationLoop.LOOP,
        channels = mapOf(
            "root" to listOf(
                SkeletalAnimationChannel(
                    target,
                    listOf(SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(value))),
                ),
            ),
        ),
        events = events,
    )

    private fun onceClip(
        name: String,
        length: Float,
        value: Float,
        event: Boolean = false,
        sourceLoopType: String? = null,
    ) = SkeletalAnimationClip(
        name = name,
        lengthSeconds = length,
        loop = SkeletalAnimationLoop.ONCE,
        channels = mapOf(
            "root" to listOf(
                SkeletalAnimationChannel(
                    SkeletalAnimationTarget.TRANSLATION,
                    listOf(SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f(value, 0.0f, 0.0f)))),
                ),
            ),
        ),
        events = if (event) {
            listOf(SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.CUSTOM_INSTRUCTION, name))
        } else {
            emptyList()
        },
        sourceLoopType = sourceLoopType,
    )
}
