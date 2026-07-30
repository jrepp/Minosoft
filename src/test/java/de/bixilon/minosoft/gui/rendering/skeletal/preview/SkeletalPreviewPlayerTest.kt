/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.preview

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationChannel
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationKeyframe
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationTarget
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalVectorValue
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletalPreviewPlayerTest {
    @Test
    fun `selected clip advances retained preview bone matrices`() {
        val transform = BakedSkeletalTransform(
            0,
            Vec3f.EMPTY,
            mapOf("root" to BakedSkeletalTransform(1, Vec3f.EMPTY, emptyMap())),
        )
        val clip = SkeletalAnimationClip(
            name = "move",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.ONCE,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(
                            SkeletalAnimationKeyframe(0.0f, SkeletalVectorValue.Constant(Vec3f.EMPTY)),
                            SkeletalAnimationKeyframe(1.0f, SkeletalVectorValue.Constant(Vec3f(1.0f, 0.0f, 0.0f))),
                        ),
                    ),
                ),
            ),
        )
        val player = SkeletalPreviewPlayer(
            transform,
            mapOf(clip.name to clip),
            SkeletalContentFormat.GECKOLIB,
        )

        player.select(clip.name)
        val initial = player.matrices.getValue(1) * Vec3f.EMPTY
        player.tick()
        val advanced = player.matrices.getValue(1) * Vec3f.EMPTY
        assertTrue(advanced.x > initial.x)

        repeat(20) { player.tick() }
        val looped = player.matrices.getValue(1) * Vec3f.EMPTY
        assertEquals(
            advanced.x,
            looped.x,
            0.0001f,
            "Preview must loop a production one-shot clip back to the equivalent next-cycle pose.",
        )

        player.select(null)
        val neutral = player.matrices.getValue(1) * Vec3f.EMPTY
        assertEquals(0.0f, neutral.x, 0.0001f)
    }

    @Test
    fun `preview supplies advancing animation time to expression channels`() {
        val transform = BakedSkeletalTransform(
            0,
            Vec3f.EMPTY,
            mapOf("root" to BakedSkeletalTransform(1, Vec3f.EMPTY, emptyMap())),
        )
        val clip = SkeletalAnimationClip(
            name = "expression",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.LOOP,
            channels = mapOf(
                "root" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(
                            SkeletalAnimationKeyframe(
                                0.0f,
                                SkeletalVectorValue.Expression(
                                    listOf("query.anim_time * 2", "q.anim_time * 2", "q.target_y_rotation"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val player = SkeletalPreviewPlayer(
            transform,
            mapOf(clip.name to clip),
            SkeletalContentFormat.GECKOLIB,
        )

        player.select(clip.name)
        player.tick()

        val translated = player.matrices.getValue(1) * Vec3f.EMPTY
        assertEquals(0.1f / 16.0f, translated.x, 0.0001f)
        assertEquals(0.1f / 16.0f, translated.y, 0.0001f)
        assertEquals(0.0f, translated.z, 0.0001f)
    }

    @Test
    fun `preview resolves snake case animation bones to camel case geometry`() {
        val transform = BakedSkeletalTransform(
            0,
            Vec3f.EMPTY,
            mapOf("leftLeg" to BakedSkeletalTransform(1, Vec3f.EMPTY, emptyMap())),
        )
        val clip = SkeletalAnimationClip(
            name = "walk",
            lengthSeconds = 1.0f,
            loop = SkeletalAnimationLoop.LOOP,
            channels = mapOf(
                "left_leg" to listOf(
                    SkeletalAnimationChannel(
                        SkeletalAnimationTarget.TRANSLATION,
                        listOf(
                            SkeletalAnimationKeyframe(
                                0.0f,
                                SkeletalVectorValue.Constant(Vec3f(2.0f, 0.0f, 0.0f)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val player = SkeletalPreviewPlayer(
            transform,
            mapOf(clip.name to clip),
            SkeletalContentFormat.GECKOLIB,
        )

        player.select(clip.name)

        val translated = player.matrices.getValue(1) * Vec3f.EMPTY
        assertEquals(2.0f / 16.0f, translated.x, 0.0001f)
    }
}
