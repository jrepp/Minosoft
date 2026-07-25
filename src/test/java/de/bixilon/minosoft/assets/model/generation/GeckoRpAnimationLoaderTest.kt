/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.generation

import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentParsers
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationTarget
import de.bixilon.minosoft.assets.model.skeletal.SkeletalVectorValue
import de.bixilon.minosoft.assets.model.skeletal.gecko.GECKO_PARSER_REGISTRATION
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class GeckoRpAnimationLoaderTest {
    @Test
    fun `RP animation documents attach to matching Gecko geometry`() {
        val root = Files.createTempDirectory("gecko-rp-animation")
        val assets = root.resolve("assets/naturalist")
        val geometry = assets.resolve("geo/entity").createDirectories()
        val animations = assets.resolve("animations").createDirectories()
        geometry.resolve("bird.geo.json").writeText(
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.bird"},"bones":[{"name":"root"}]}]}""",
        )
        animations.resolve("bird.rp_anim.json").writeText(
            """{"format_version":"1.8.0","animations":{"animation.bird.idle":{"loop":true,"bones":{"root":{"rotation":[0,0,0],"scale":1.5}}}}}""",
        )
        val manager = DirectoryAssetsManager(root)
        val registration = SkeletalContentParsers.register(GECKO_PARSER_REGISTRATION)
        try {
            manager.load()
            val prepared = ContentFidelityLoader(manager).prepare()
            val animation = prepared.value.skeletal.values.single().single().animations
            assertEquals(
                setOf("animation.bird.idle"),
                animation.keys,
            )
            val scale = animation.getValue("animation.bird.idle").channels.getValue("root")
                .single { it.target == SkeletalAnimationTarget.SCALE }
                .keyframes.single().value as SkeletalVectorValue.Constant
            assertEquals(de.bixilon.kmath.vec.vec3.f.Vec3f(1.5f), scale.value)
            prepared.cleanup.close()
        } finally {
            registration.close()
            manager.unload()
            root.toFile().deleteRecursively()
        }
    }
}
