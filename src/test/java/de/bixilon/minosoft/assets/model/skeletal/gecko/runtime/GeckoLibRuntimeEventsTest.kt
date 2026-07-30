/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeckoLibRuntimeEventsTest {
    @Test
    fun `headless playback routes validated sound particle and custom events`() {
        val target = RecordingTarget()
        val sound = context(SkeletalAnimationEventType.SOUND, "test:step")
        val particle = context(SkeletalAnimationEventType.PARTICLE, "test:dust")
        val custom = context(SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "attack")

        assertTrue(GeckoLibEventPlayback.dispatch(sound, target))
        assertTrue(GeckoLibEventPlayback.dispatch(particle, target))
        assertTrue(GeckoLibEventPlayback.dispatch(custom, target))
        assertEquals(listOf(ResourceLocation.of("test:step")), target.sounds)
        assertEquals(listOf(ResourceLocation.of("test:dust")), target.particles)
        assertEquals(listOf("attack"), target.custom)
        assertEquals(emptyList(), target.rejections)
    }

    @Test
    fun `invalid effects are rejected before reaching platform services`() {
        val target = RecordingTarget()
        val invalid = context(SkeletalAnimationEventType.PARTICLE, "../not valid")

        assertFalse(GeckoLibEventPlayback.dispatch(invalid, target))
        assertEquals(1, target.rejections.size)
        assertEquals(emptyList(), target.particles)
    }

    @Test
    fun `runtime effect aliases are generation bound and fail closed after owner removal`() {
        val identity = SkeletalContentIdentity(
            ResourceLocation.of("test:geo/entity.geo.json"),
            SkeletalContentFormat.GECKOLIB,
            "geometry.test",
        )
        val target = RecordingTarget()
        val firstRegistration = GeckoLibRuntimeEffectRegistry.register("first", identity) {
            when (it.event.payload) {
                "step" -> GeckoLibRuntimeEffectResolution.Play(ResourceLocation.of("test:first_step"))
                else -> GeckoLibRuntimeEffectResolution.PassThrough
            }
        }
        val firstBinding = assertNotNull(GeckoLibRuntimeEffectRegistry.bind(identity))
        val sound = context(SkeletalAnimationEventType.SOUND, "step").copy(contentIdentity = identity)
        val particle = context(SkeletalAnimationEventType.PARTICLE, "test:dust").copy(contentIdentity = identity)
        try {
            assertTrue(GeckoLibEventPlayback.dispatch(sound, target, firstBinding))
            assertTrue(GeckoLibEventPlayback.dispatch(particle, target, firstBinding))
            assertEquals(listOf(ResourceLocation.of("test:first_step")), target.sounds)
            assertEquals(listOf(ResourceLocation.of("test:dust")), target.particles)
        } finally {
            firstRegistration.close()
        }

        assertFalse(GeckoLibEventPlayback.dispatch(sound, target, firstBinding))
        assertEquals(listOf(ResourceLocation.of("test:first_step")), target.sounds)

        val replacement = GeckoLibRuntimeEffectRegistry.register("replacement", identity) {
            GeckoLibRuntimeEffectResolution.Play(ResourceLocation.of("test:replacement_step"))
        }
        try {
            val replacementBinding = assertNotNull(GeckoLibRuntimeEffectRegistry.bind(identity))
            assertFalse(GeckoLibEventPlayback.dispatch(sound, target, firstBinding))
            assertTrue(GeckoLibEventPlayback.dispatch(sound, target, replacementBinding))
            assertEquals(
                listOf(
                    ResourceLocation.of("test:first_step"),
                    ResourceLocation.of("test:replacement_step"),
                ),
                target.sounds,
            )
        } finally {
            replacement.close()
        }
        assertEquals(emptyMap(), GeckoLibRuntimeEffectRegistry.owners())
    }

    @Test
    fun `owned listeners are isolated and removed on close`() {
        val received = mutableListOf<String>()
        val failing = GeckoLibRuntimeEvents.register("failing") { error("expected") }
        val healthy = GeckoLibRuntimeEvents.register("healthy") { received += it.event.payload }
        try {
            GeckoLibRuntimeEvents.dispatch(context(SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "first"))
            assertEquals(listOf("first"), received)
            assertEquals(listOf("failing", "healthy"), GeckoLibRuntimeEvents.owners())
            healthy.close()
            GeckoLibRuntimeEvents.dispatch(context(SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "second"))
            assertEquals(listOf("first"), received)
        } finally {
            failing.close()
            healthy.close()
        }
        assertEquals(emptyList(), GeckoLibRuntimeEvents.owners())
    }

    @Test
    fun `close waits for an in flight listener and prevents later dispatch`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var calls = 0
        val registration = GeckoLibRuntimeEvents.register("quiescent") {
            calls++
            started.countDown()
            release.await()
        }
        val dispatch = thread {
            GeckoLibRuntimeEvents.dispatch(context(SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "first"))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val closer = thread {
            closeStarted.countDown()
            registration.close()
            closed.countDown()
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        dispatch.join()
        closer.join()

        GeckoLibRuntimeEvents.dispatch(context(SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "second"))
        assertEquals(1, calls)
    }

    private fun context(type: SkeletalAnimationEventType, payload: String) = GeckoLibRuntimeEventContext(
        animation = "test",
        event = SkeletalAnimationEvent(0.0f, type, payload),
        position = Vec3d.EMPTY,
    )

    private class RecordingTarget : GeckoLibEventPlaybackTarget {
        val sounds = mutableListOf<ResourceLocation>()
        val particles = mutableListOf<ResourceLocation>()
        val custom = mutableListOf<String>()
        val rejections = mutableListOf<String>()

        override fun playSound(context: GeckoLibRuntimeEventContext, sound: ResourceLocation) {
            sounds += sound
        }

        override fun spawnParticle(context: GeckoLibRuntimeEventContext, particle: ResourceLocation) {
            particles += particle
        }

        override fun customInstruction(context: GeckoLibRuntimeEventContext) {
            custom += context.event.payload
        }

        override fun rejected(context: GeckoLibRuntimeEventContext, reason: String) {
            rejections += reason
        }
    }
}
