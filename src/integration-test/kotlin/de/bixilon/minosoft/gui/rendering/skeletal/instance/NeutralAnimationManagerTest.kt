/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationChannel
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationKeyframe
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationTarget
import de.bixilon.minosoft.assets.model.skeletal.SkeletalVectorValue
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEasingRegistry
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds

@Test(groups = ["skeletal"])
class NeutralAnimationManagerTest {
    fun `single neutral clip starts automatically and defers events until transforms finish`() {
        val event = SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.SOUND, "test:step")
        val clip = SkeletalAnimationClip(
            name = "idle",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.LOOP,
            channels = emptyMap(),
            events = listOf(event),
        )
        val model = BakedSkeletalModel(
            mesh = Mesh::class.java.allocate(),
            transform = BakedSkeletalTransform(0, Vec3f.EMPTY, emptyMap()),
            transformCount = 1,
            animations = emptyMap(),
            neutralAnimations = mapOf("idle" to clip),
        )
        val instance = SkeletalInstance(
            context = RenderContext::class.java.allocate(),
            model = model,
            transform = model.transform.instance(),
        )
        val received = mutableListOf<Pair<String, SkeletalAnimationEvent>>()
        instance.neutralAnimation.eventConsumer = { animation, value -> received += animation to value }

        assertEquals(instance.neutralAnimation.current, "idle")
        instance.neutralAnimation.draw(50.milliseconds)
        assertEquals(received, emptyList<Pair<String, SkeletalAnimationEvent>>())
        instance.neutralAnimation.dispatchEvents()
        assertEquals(received, listOf("idle" to event))
    }

    fun `registered Gecko easing reaches retained transform playback`() {
        val clip = SkeletalAnimationClip(
            name = "custom_easing",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.ONCE,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        target = SkeletalAnimationTarget.TRANSLATION,
                        keyframes = listOf(
                            SkeletalAnimationKeyframe(
                                timeSeconds = 0.0f,
                                value = SkeletalVectorValue.Constant(Vec3f.EMPTY),
                                easing = "test:instant",
                            ),
                            SkeletalAnimationKeyframe(
                                timeSeconds = 1.0f,
                                value = SkeletalVectorValue.Constant(Vec3f(16.0f, 0.0f, 0.0f)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val model = BakedSkeletalModel(
            mesh = Mesh::class.java.allocate(),
            transform = BakedSkeletalTransform(
                id = 0,
                pivot = Vec3f.EMPTY,
                children = mapOf("root" to BakedSkeletalTransform(1, Vec3f.EMPTY, emptyMap())),
            ),
            transformCount = 2,
            animations = emptyMap(),
            neutralAnimations = mapOf(clip.name to clip),
        )
        val instance = SkeletalInstance(
            context = RenderContext::class.java.allocate(),
            model = model,
            transform = model.transform.instance(),
        )

        GeckoLibEasingRegistry.register("integration-test", "test:instant") { _, _ -> 1.0 }.use {
            instance.neutralAnimation.draw(500.milliseconds)
        }

        val position = instance.transform.children.getValue("root").matrix.unsafe * Vec3f.EMPTY
        assertEquals(position.x, 1.0f, 0.0001f)
        assertEquals(position.y, 0.0f, 0.0001f)
        assertEquals(position.z, 0.0f, 0.0001f)
    }
}
