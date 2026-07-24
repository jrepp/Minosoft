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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

    private fun clip(
        name: String,
        target: SkeletalAnimationTarget,
        value: Vec3f,
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
    )
}
