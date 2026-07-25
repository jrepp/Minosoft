/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEvents
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.audio.AbstractAudioPlayer
import de.bixilon.minosoft.data.world.particle.AbstractParticleRenderer
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.particle.types.Particle
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.util.Random

@Test(groups = ["entities", "skeletal"])
class GeckoLibEntityEventConsumerTest {
    fun `entity consumer reaches native audio particles and owned instruction listeners`() {
        val entities = EntityRendererTestUtil.create()
        val entity = entities.createEntity(Pig)
        val renderer = object : EntityRenderer<Pig>(entities, entity) {}
        val audio = RecordingAudio()
        val particles = RecordingParticles()
        entity.session.world.audio = audio
        entity.session.world.particle = particles
        val model = BakedSkeletalModel(
            mesh = Mesh::class.java.allocate(),
            transform = BakedSkeletalTransform(0, Vec3f.EMPTY, emptyMap()),
            transformCount = 1,
            animations = emptyMap(),
        )
        val instance = SkeletalInstance(entities.context, model, model.transform.instance())
        val consumer = GeckoLibEntityEventConsumer(renderer, instance)
        val instructions = mutableListOf<String>()
        val registration = GeckoLibRuntimeEvents.register("test") {
            if (it.event.type == SkeletalAnimationEventType.CUSTOM_INSTRUCTION) {
                instructions += it.event.payload
            }
        }
        try {
            consumer.dispatch("idle", SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.SOUND, "test:step"))
            consumer.dispatch("idle", SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.PARTICLE, "minecraft:flame"))
            consumer.dispatch("idle", SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.CUSTOM_INSTRUCTION, "attack"))
        } finally {
            registration.close()
        }

        assertEquals(audio.sounds, listOf(ResourceLocation.of("test:step")))
        assertEquals(audio.positions, listOf(entity.physics.position))
        assertEquals(particles.values.size, 1)
        assertTrue((particles.values.single().position - entity.physics.position).length() < 0.1)
        assertEquals(instructions, listOf("attack"))
        assertEquals(GeckoLibRuntimeEvents.owners(), emptyList<String>())
    }

    private class RecordingAudio : AbstractAudioPlayer {
        val sounds = mutableListOf<ResourceLocation>()
        val positions = mutableListOf<Vec3d?>()

        override fun play(sound: ResourceLocation, position: Vec3d?, volume: Float, pitch: Float) {
            sounds += sound
            positions += position
        }

        override fun stopAll() = Unit
        override fun stop(sound: ResourceLocation) = Unit
    }

    private class RecordingParticles : AbstractParticleRenderer {
        override val random = Random(0L)
        val values = mutableListOf<Particle>()

        override fun add(particle: Particle) {
            values += particle
        }

        override fun removeAll() {
            values.clear()
        }
    }
}
