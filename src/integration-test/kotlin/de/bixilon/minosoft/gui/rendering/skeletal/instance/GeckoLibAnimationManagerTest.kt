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
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.SkeletalVectorValue
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerPredicate
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerRegistry
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds

@Test(groups = ["skeletal"])
class GeckoLibAnimationManagerTest {
    fun `identity binding drives retained transforms defers events and disables on owner close`() {
        val identity = SkeletalContentIdentity(
            ResourceLocation.of("test:geo/retained.geo.json"),
            SkeletalContentFormat.GECKOLIB,
            "geometry.retained_test",
        )
        val event = SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.SOUND, "test:step")
        val clip = SkeletalAnimationClip(
            name = "move",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.LOOP,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(
                            SkeletalAnimationKeyframe(
                                0.0f,
                                SkeletalVectorValue.Constant(Vec3f(8.0f, 0.0f, 0.0f)),
                            ),
                        ),
                    ),
                ),
            ),
            events = listOf(event),
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
            neutralAnimations = mapOf("move" to clip),
            contentIdentity = identity,
        )
        val registration = GeckoLibControllerBindingRegistry.register("retained-test", identity) { context ->
            assertEquals(context.animations.keys, setOf("move"))
            listOf(GeckoLibControllerDefinition(name = "main", initialClip = "move"))
        }
        try {
            val instance = SkeletalInstance(
                context = RenderContext::class.java.allocate(),
                model = model,
                transform = model.transform.instance(),
            )
            val received = mutableListOf<Pair<String, SkeletalAnimationEvent>>()
            instance.geckoAnimation.eventConsumer = { animation, value -> received += animation to value }

            assertTrue(instance.geckoAnimation.active)
            instance.geckoAnimation.draw(50.milliseconds, GeckoLibAnimationState(0.05f))
            val position = instance.transform.children.getValue("root").matrix.unsafe * Vec3f.EMPTY
            assertEquals(position.x, 0.5f, 0.0001f)
            assertEquals(received, emptyList<Pair<String, SkeletalAnimationEvent>>())

            instance.geckoAnimation.dispatchEvents()
            assertEquals(received, listOf("move" to event))

            val snapshot = requireNotNull(instance.geckoAnimation.snapshot())
            val replacement = SkeletalInstance(
                context = RenderContext::class.java.allocate(),
                model = model,
                transform = model.transform.instance(),
            )
            assertTrue(replacement.geckoAnimation.restore(snapshot))
            assertEquals(
                replacement.geckoAnimation.controllers
                    ?.snapshot()
                    ?.layers
                    ?.getValue("main")
                    ?.controller
                    ?.elapsedSeconds,
                0.05f,
            )

            registration.close()
            assertFalse(instance.geckoAnimation.active)
            instance.geckoAnimation.draw(50.milliseconds, GeckoLibAnimationState(0.1f))
            assertFalse(
                SkeletalInstance(
                    context = RenderContext::class.java.allocate(),
                    model = model,
                    transform = model.transform.instance(),
                ).geckoAnimation.active,
            )
        } finally {
            registration.close()
        }
    }

    fun `retained render-layer predicate follows entity state and owner lifecycle`() {
        val identity = SkeletalContentIdentity(
            ResourceLocation.of("test:geo/layered.geo.json"),
            SkeletalContentFormat.GECKOLIB,
            "geometry.layered_test",
        )
        val registration = GeckoLibRenderLayerRegistry.register(
            "retained-layer-test",
            identity,
            listOf(
                GeckoLibRenderLayerDefinition(
                    name = "moving_glow",
                    texture = ResourceLocation.of("test:textures/entity/moving_glow.png"),
                    predicate = GeckoLibRenderLayerPredicate { it.moving },
                ),
            ),
        )
        try {
            val model = BakedSkeletalModel(
                mesh = Mesh::class.java.allocate(),
                transform = BakedSkeletalTransform(0, Vec3f.EMPTY, emptyMap()),
                transformCount = 1,
                animations = emptyMap(),
                contentIdentity = identity,
            )
            val manager = SkeletalInstance(
                context = RenderContext::class.java.allocate(),
                model = model,
                transform = model.transform.instance(),
            ).geckoAnimation

            manager.updateState(GeckoLibAnimationState(0.0f, moving = false))
            assertEquals(manager.renderLayer("moving_glow"), null)
            manager.updateState(GeckoLibAnimationState(0.1f, moving = true))
            assertEquals(manager.renderLayer("moving_glow")?.name, "moving_glow")

            registration.close()
            assertEquals(manager.renderLayer("moving_glow"), null)
        } finally {
            registration.close()
        }
    }
}
