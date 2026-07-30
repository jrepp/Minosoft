/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.ExternalAssetProviders
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop
import de.bixilon.minosoft.assets.model.skeletal.gecko.GeckoLibParser
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityTextureRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityTextureState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostEvents
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEffectRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEffectResolution
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEventContext
import de.bixilon.minosoft.data.entities.EntityAnimations
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.util.json.Jackson
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaturalistCompatibilityAdapterTest {
    @Test
    fun `native zebra bridge is a four-legged horse layer compatible with shipped clips`() {
        val parser = GeckoLibParser()
        val model = parser.parseGeometry(
            ResourceLocation.of("naturalist:geo/entity/zebra.geo.json"),
            ByteArrayInputStream(NaturalistNativeModels.zebra()),
        ).models.single()

        assertEquals("geometry.minosoft.naturalist.zebra_native", model.identifier)
        assertTrue(listOf("legfl", "legfr", "legbl", "legbr").all(model.bones::containsKey))
        assertTrue(listOf("left_arm", "right_arm", "left_leg", "right_leg").all(model.bones::containsKey))
        assertTrue(listOf("leftWing", "rightWing", "left_wing", "right_wing").none(model.bones::containsKey))
        assertEquals(4, listOf("legfl", "legfr", "legbl", "legbr").sumOf { model.bones.getValue(it).cubes.size })
        assertEquals(Vec3f(30.0f, 0.0f, 0.0f), model.bones.getValue("neck").rotation)
        assertEquals(Vec3f(30.0f, 0.0f, 0.0f), model.bones.getValue("tail").rotation)

        val animations = parser.parseAnimations(
            ResourceLocation.of("naturalist:animations/zebra.rp_anim.json"),
            ByteArrayInputStream(
                """
                    {
                      "format_version": "1.8.0",
                      "animations": {
                        "animation.sf_nba.zebra.idle": {
                          "loop": true,
                          "bones": {
                            "neck": {"position": [0, 0, 0]},
                            "tail": {"rotation": [0, 0, 0]},
                            "legfl": {"scale": 0.99},
                            "legfr": {"scale": 0.99},
                            "legbl": {"scale": 0.99},
                            "legbr": {"scale": 0.99},
                            "left_ear": {"rotation": [0, 0, 0]},
                            "right_ear": {"rotation": [0, 0, 0]}
                          }
                        },
                        "animation.sf_nba.zebra.walk": {
                          "loop": true,
                          "bones": {
                            "body": {"position": [0, 0, 0]},
                            "head": {"rotation": [0, 0, 0]},
                            "front_legs": {"position": [0, 0, 0]},
                            "back_legs": {"position": [0, 0, 0]},
                            "left_arm": {"rotation": [0, 0, 0]},
                            "right_arm": {"rotation": [0, 0, 0]},
                            "left_leg": {"rotation": [0, 0, 0]},
                            "right_leg": {"rotation": [0, 0, 0]},
                            "tail": {"rotation": [0, 0, 0]}
                          }
                        }
                      }
                    }
                """.trimIndent().encodeToByteArray(),
            ),
        )
        val attached = parser.attachAnimations(model, animations)
        assertEquals(setOf("animation.sf_nba.zebra.idle", "animation.sf_nba.zebra.walk"), attached.animations.keys)
    }

    @Test
    fun `pinned Naturalist geometry corrections align appendages and atlas planes`() {
        fun document(vararg bones: String) =
            """{"minecraft:geometry":[{"bones":[${bones.joinToString(",")}]}]}""".encodeToByteArray()

        fun bones(bytes: ByteArray) = Jackson.MAPPER.readTree(bytes)
            .path("minecraft:geometry")[0]
            .path("bones")

        val bird = bones(
            NaturalistGeometryCorrections.bird(
                document(
                    """{"name":"leftLeg","cubes":[{"origin":[0.5,0.1,-1],"size":[2,3,2],"uv":[17,8]},{"origin":[0.5,0.1,1],"size":[1,1,0],"uv":[16,27]}]}""",
                    """{"name":"rightLeg","cubes":[{"origin":[-2.5,0.1,-1],"size":[2,3,2],"uv":[17,8],"mirror":true}]}""",
                ),
            ),
        )
        assertEquals(-1.0, bird[0].path("cubes")[1].path("origin")[2].asDouble())
        assertEquals(2, bird[1].path("cubes").size())
        assertEquals(-1.5, bird[1].path("cubes")[1].path("origin")[0].asDouble())
        assertTrue(bird[1].path("cubes")[1].path("mirror").asBoolean())

        val boar = bones(
            NaturalistGeometryCorrections.boar(
                document(
                    """{"name":"body","cubes":[{"origin":[-6,6,-3],"size":[12,17,10],"uv":[0,0]},{"origin":[0,7,3],"size":[0,18,11],"uv":[0,28]}]}""",
                ),
            ),
        )
        assertEquals(-18.0, boar[0].path("cubes")[1].path("uv").path("west").path("uv_size")[1].asDouble())

        val catfish = bones(
            NaturalistGeometryCorrections.catfish(
                document(
                    """{"name":"whiskers","cubes":[{"origin":[-4,-3.25,-12.5],"size":[8,5,7],"pivot":[0,-0.75,-6],"uv":[56,19]}]}""",
                ),
            ),
        )
        assertEquals(-3.0, catfish[0].path("cubes")[0].path("origin")[1].asDouble())
        assertEquals(-0.5, catfish[0].path("cubes")[0].path("pivot")[1].asDouble())

        fun alignedAntennae(
            correction: (ByteArray) -> ByteArray,
            y: Double,
            z: Double,
        ): Pair<Double, Double> {
            val corrected = bones(
                correction(
                    document(
                        """{"name":"antennae","cubes":[{"origin":[-1.5,$y,$z],"size":[3,2,3],"uv":[0,24]}]}""",
                    ),
                ),
            )
            val origin = corrected[0].path("cubes")[0].path("origin")
            return origin[1].asDouble() to origin[2].asDouble()
        }
        assertEquals(
            2.75 to -8.75,
            alignedAntennae(NaturalistGeometryCorrections::caterpillar, 3.0, -9.0),
        )
        assertEquals(
            5.75 to -7.75,
            alignedAntennae(NaturalistGeometryCorrections::firefly, 6.0, -8.0),
        )
    }

    @Test
    fun `exact Naturalist surface owns filtered assets routes and controllers`() {
        val artifact = Files.createTempFile("naturalist-5.0.0-pre.4", ".jar")
        JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
            for (entry in listOf(
                "assets/naturalist/geo/entity/alligator.geo.json",
                "assets/naturalist/geo/entity/lizard_tail.geo.json",
                "assets/naturalist/geo/entity/ostrich.geo.json",
                "assets/naturalist/animations/alligator.rp_anim.json",
                "assets/naturalist/animations/0_old/alligator.animation.json",
                "assets/naturalist/textures/entity/alligator/alligator.png",
                "assets/naturalist/textures/entity/caterpillar.png",
                "assets/naturalist/textures/entity/lizard/beardie.png",
                "assets/naturalist/textures/entity/lizard/green_tail.png",
                "assets/naturalist/textures/entity/zebra.png",
            )) {
                jar.putNextEntry(JarEntry(entry))
                jar.write("{}".encodeToByteArray())
                jar.closeEntry()
            }
        }
        val metadata = FabricMetadata(
            id = "naturalist",
            version = "5.0.0-pre.4",
            name = "Naturalist",
            environment = "*",
            entrypoints = setOf("main", "client"),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 2,
            accessWidener = null,
            nestedJarPaths = listOf(
                "META-INF/jars/midnightlib-1.5.3-fabric.jar",
                "META-INF/jars/cloth-config-fabric-13.0.138-fabric.jar",
            ),
            source = artifact.toString(),
        )
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        assertEquals(NaturalistCompatibilityAdapter, adapter)
        assertNull(FabricCompatibilityAdapters.resolve(metadata.copy(version = "5.0pre3")))
        val scope = FabricRegistrationScope()
        try {
            adapter!!.activate(FabricModProbe(metadata, emptySet(), adapter), scope)
            assertEquals(32, GeckoLibEntityModelRegistry.owners().count { it.value == adapter.id })
            assertEquals(25, GeckoLibControllerBindingRegistry.owners().count { it.value == adapter.id })
            assertEquals(25, GeckoLibEntityTextureRegistry.owners().count { it.value == adapter.id })
            assertEquals(1, GeckoLibRuntimeEffectRegistry.owners().count { it.value == adapter.id })
            assertEquals(33, FabricRemoteRegistrySync.definitions().count { it.value == adapter.id })
            assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:bluejay")))
            assertEquals(
                GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:bluejay")),
                GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:canary")),
            )
            val zebra = assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:zebra")))
            assertEquals(ResourceLocation.of("naturalist:geo/entity/zebra.geo.json"), zebra.source)
            assertEquals("geometry.minosoft.naturalist.zebra_native", zebra.identifier)
            val lizardTail = assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:lizard_tail")))
            assertEquals("geometry.lizard_tail", lizardTail.identifier)
            val tortoise = assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:tortoise")))
            val tortoiseTextures = assertNotNull(GeckoLibEntityTextureRegistry.snapshot(tortoise))
            assertEquals(
                ResourceLocation.of("naturalist:textures/entity/tortoise/black.png"),
                GeckoLibEntityTextureRegistry.select(
                    tortoise,
                    tortoiseTextures.registrationId,
                    GeckoLibEntityTextureState(
                        ResourceLocation.of("naturalist:tortoise"),
                        null,
                        baby = false,
                        aggressive = false,
                    ) { index -> if (index == 19) 2 else null },
                ),
            )
            val bird = assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:bluejay")))
            val birdTextures = assertNotNull(GeckoLibEntityTextureRegistry.snapshot(bird))
            assertEquals(
                ResourceLocation.of("naturalist:textures/entity/bird/bluejay.png"),
                GeckoLibEntityTextureRegistry.select(
                    bird,
                    birdTextures.registrationId,
                    GeckoLibEntityTextureState(
                        ResourceLocation.of("naturalist:bluejay"),
                        null,
                        baby = false,
                        aggressive = false,
                    ) { null },
                ),
            )
            for (species in listOf("canary", "cardinal")) {
                assertEquals(
                    ResourceLocation.of("naturalist:textures/entity/bird/$species.png"),
                    GeckoLibEntityTextureRegistry.select(
                        bird,
                        birdTextures.registrationId,
                        GeckoLibEntityTextureState(
                            ResourceLocation.of("naturalist:$species"),
                            null,
                            baby = false,
                            aggressive = false,
                        ) { null },
                    ),
                    "Shared bird geometry must retain the selected species texture.",
                )
            }
            val snake = assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:snake")))
            val snakeEffects = assertNotNull(GeckoLibRuntimeEffectRegistry.bind(snake))
            fun snakeSound(animation: String, effect: String) = GeckoLibRuntimeEventContext(
                animation = animation,
                event = SkeletalAnimationEvent(0.0f, SkeletalAnimationEventType.SOUND, effect),
                position = Vec3d.EMPTY,
                contentIdentity = snake,
            )
            assertEquals(
                GeckoLibRuntimeEffectResolution.Play(ResourceLocation.of("naturalist:entity.snake.hiss")),
                snakeEffects.resolve(snakeSound("animation.sf_nba.snake.tongue", "hiss")),
            )
            assertEquals(
                GeckoLibRuntimeEffectResolution.Ignore,
                snakeEffects.resolve(snakeSound("animation.sf_nba.snake.tongue", "idle")),
            )
            assertEquals(
                GeckoLibRuntimeEffectResolution.Ignore,
                snakeEffects.resolve(snakeSound("animation.sf_nba.snake.rattle", "rattle")),
            )
            val snakeClips = listOf("move", "climb", "sleep", "idle", "attack", "tongue", "rattle").associate { suffix ->
                val name = "animation.sf_nba.snake.$suffix"
                val loop = if (suffix == "attack" || suffix == "tongue") {
                    SkeletalAnimationLoop.ONCE
                } else {
                    SkeletalAnimationLoop.LOOP
                }
                val length = when (suffix) {
                    "attack" -> 0.25f
                    "tongue" -> 0.75f
                    else -> 1.0f
                }
                name to SkeletalAnimationClip(name, length, loop, emptyMap())
            }
            val snakeManager = assertNotNull(
                GeckoLibControllerBindingRegistry.createManager(snake, snakeClips),
            )
            try {
                assertEquals(
                    mapOf(
                        "naturalist.snake.climbing" to 0.0,
                        "naturalist.snake.sleeping" to 0.0,
                    ),
                    snakeManager.resolveTrackedData { null },
                )
                assertEquals(
                    setOf(
                        "naturalist.snake.tongue_random",
                        "naturalist.snake.is_rattlesnake",
                        "naturalist.snake.nearby_player",
                    ),
                    snakeManager.hostStateInputs.map { it.name }.toSet(),
                )
                fun state(
                    ageSeconds: Float,
                    moving: Boolean = false,
                    tracked: (Int) -> Any? = { null },
                    host: Map<String, Double> = mapOf("naturalist.snake.tongue_random" to 999.0),
                ) = GeckoLibAnimationState(
                    ageSeconds,
                    moving,
                    buildMap {
                        putAll(snakeManager.resolveTrackedData(tracked))
                        putAll(snakeManager.resolveHostState { host[it.name] })
                    },
                )
                snakeManager.update(
                    0.05f,
                    state(0.05f),
                )
                assertNull(snakeManager.current("controller"))

                snakeManager.update(
                    0.05f,
                    state(0.1f, moving = true),
                )
                assertEquals("animation.sf_nba.snake.move", snakeManager.current("controller"))

                val sleeping = state(0.15f, moving = true, tracked = { index -> index == 19 })
                snakeManager.update(0.05f, sleeping)
                assertEquals("animation.sf_nba.snake.sleep", snakeManager.current("controller"))

                val climbing = state(0.2f, tracked = { index -> if (index == 17) 1.toByte() else false })
                snakeManager.update(0.05f, climbing)
                assertEquals("animation.sf_nba.snake.climb", snakeManager.current("controller"))

                assertEquals(
                    1,
                    snakeManager.triggerEvent(GeckoLibHostEvents.entityAnimation(EntityAnimations.SWING_MAIN_ARM)),
                )
                snakeManager.update(0.05f, climbing)
                assertEquals("animation.sf_nba.snake.attack", snakeManager.current("attackController"))

                snakeManager.update(
                    0.05f,
                    state(
                        10.0f,
                        host = mapOf("naturalist.snake.tongue_random" to 199.0),
                    ),
                )
                assertEquals("animation.sf_nba.snake.tongue", snakeManager.current("tongueController"))
                snakeManager.update(0.75f, state(10.75f))
                assertNull(snakeManager.current("tongueController"))
                snakeManager.update(
                    0.05f,
                    state(
                        10.8f,
                        tracked = { index -> index == 19 },
                        host = mapOf("naturalist.snake.tongue_random" to 0.0),
                    ),
                )
                assertNull(snakeManager.current("tongueController"))

                val rattling = state(
                    0.3f,
                    host = mapOf(
                        "naturalist.snake.tongue_random" to 999.0,
                        "naturalist.snake.is_rattlesnake" to 1.0,
                        "naturalist.snake.nearby_player" to 1.0,
                    ),
                )
                snakeManager.update(0.05f, rattling)
                assertEquals("animation.sf_nba.snake.rattle", snakeManager.current("rattleController"))
                snakeManager.update(
                    0.05f,
                    state(
                        0.35f,
                        host = mapOf(
                            "naturalist.snake.tongue_random" to 999.0,
                            "naturalist.snake.is_rattlesnake" to 1.0,
                            "naturalist.snake.nearby_player" to 0.0,
                        ),
                    ),
                )
                assertNull(snakeManager.current("rattleController"))
            } finally {
                snakeManager.close()
            }

            val provider = ExternalAssetProviders.snapshot().single { it.owner == adapter.id }
            val assets = provider.create()
            try {
                assets.load()
                assertTrue(ResourceLocation.of("naturalist:geo/entity/alligator.geo.json") in assets)
                assertTrue(ResourceLocation.of("naturalist:animations/alligator.rp_anim.json") in assets)
                assertTrue(ResourceLocation.of("naturalist:textures/entity/alligator/alligator.png") in assets)
                assertTrue(ResourceLocation.of("naturalist:textures/entity/lizard.png") in assets)
                assertTrue(ResourceLocation.of("naturalist:textures/entity/lizard_tail.png") in assets)
                assertFalse(ResourceLocation.of("naturalist:textures/entity/ostrich.png") in assets)
                val zebraSource = ResourceLocation.of("naturalist:geo/entity/zebra.geo.json")
                assertTrue(zebraSource in assets)
                val zebraGeometry = Jackson.MAPPER.readTree(assertNotNull(assets[zebraSource]))
                    .path("minecraft:geometry")[0]
                assertEquals(
                    "geometry.minosoft.naturalist.zebra_native",
                    zebraGeometry.path("description").path("identifier").asText(),
                )
                val zebraBones = zebraGeometry.path("bones").associateBy { it.path("name").asText() }
                assertTrue(listOf("legfl", "legfr", "legbl", "legbr").all(zebraBones::containsKey))
                assertTrue(listOf("left_arm", "right_arm", "left_leg", "right_leg").all(zebraBones::containsKey))
                assertTrue(listOf("leftWing", "rightWing", "left_wing", "right_wing").none(zebraBones::containsKey))
                assertTrue(ResourceLocation.of("naturalist:textures/__content/geometry.unknown.png") in assets)
                assertFalse(ResourceLocation.of("naturalist:geo/entity/ostrich.geo.json") in assets)
                assertFalse(ResourceLocation.of("naturalist:animations/0_old/alligator.animation.json") in assets)
            } finally {
                assets.unload()
            }
        } finally {
            scope.close()
            Files.deleteIfExists(artifact)
        }
        assertFalse(ExternalAssetProviders.snapshot().any { it.owner == NaturalistCompatibilityAdapter.id })
        assertFalse(GeckoLibEntityModelRegistry.owners().any { it.value == NaturalistCompatibilityAdapter.id })
        assertFalse(GeckoLibControllerBindingRegistry.owners().any { it.value == NaturalistCompatibilityAdapter.id })
        assertFalse(GeckoLibEntityTextureRegistry.owners().any { it.value == NaturalistCompatibilityAdapter.id })
        assertFalse(GeckoLibRuntimeEffectRegistry.owners().any { it.value == NaturalistCompatibilityAdapter.id })
        assertFalse(FabricRemoteRegistrySync.definitions().any { it.value == NaturalistCompatibilityAdapter.id })
    }
}
