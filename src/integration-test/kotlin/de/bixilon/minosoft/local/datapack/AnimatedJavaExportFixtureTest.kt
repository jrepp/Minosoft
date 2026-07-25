/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.local.datapack

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.datapack.DataPackFunction
import de.bixilon.minosoft.assets.datapack.DataPackFunctionLibrary
import de.bixilon.minosoft.assets.datapack.DataPackFunctionReference
import de.bixilon.minosoft.assets.datapack.DataPackFunctionRuntime
import de.bixilon.minosoft.assets.datapack.LocalDataPackCommandAuthority
import de.bixilon.minosoft.assets.datapack.SessionDataPackRuntime
import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.assets.model.generation.ContentFidelityLoader
import de.bixilon.minosoft.assets.model.generation.ContentGenerationStore
import de.bixilon.minosoft.assets.model.generation.PreparedContent
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderingOptions
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

class AnimatedJavaExportFixtureTest {

    @Test
    fun `runs pinned 1_10_2 summon tick and removal lifecycle`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        val fixture = Paths.get(
            requireNotNull(javaClass.classLoader.getResource("content_fidelity/animated_java-1.10.2/fixture.json")).toURI(),
        ).parent
        val data = DirectoryAssetsManager(fixture, prefix = "data")
        try {
            IT.VERSION
            data.load()
            val session = createSession(version = "1.20.4")
            val origin = Vec3d(8.0, 12.0, 4.0)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, nbt, position -> factory.summon(type, nbt, position) },
                entities = entities,
            )
            val runtime = DataPackFunctionRuntime(DataPackFunctionLibrary.load(data), authority)

            runtime.load()
            runtime.execute("demo:summon")
            val root = entities.select(
                "@e[type=item_display,tag=demo.root,limit=1,distance=..0.01]",
                de.bixilon.minosoft.assets.datapack.DataPackCommandContext(
                    de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("demo:test"),
                    0,
                    0,
                    position = origin,
                ),
            ).single() as ItemDisplayEntity
            assertFalse("aj.new" in root.commandTags)
            assertEquals(authority.score(root.uuid.toString(), "aj.id"), 1)

            runtime.tick()
            val node = root.attachment.passengers.single() as ItemDisplayEntity
            assertEquals(authority.score(root.uuid.toString(), "demo.frame"), 0)
            assertEquals(node.stack!!.nbt.nbt["CustomModelData"], 1)

            runtime.tick()
            assertEquals(authority.score(root.uuid.toString(), "demo.frame"), 1)
            assertEquals(node.stack!!.nbt.nbt["CustomModelData"], 27)
            assertEquals(node.interpolationStartDeltaTicks, -1)

            assertEquals(runtime.execute("demo:remove"), 2)
            assertTrue(entities.select(
                "@e[tag=demo.entity]",
                de.bixilon.minosoft.assets.datapack.DataPackCommandContext(
                    de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("demo:test"),
                    0,
                    runtime.tick,
                    position = origin,
                ),
            ).isEmpty())
        } finally {
            data.unload()
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `runs unmodified 1_10_2 Blockbench export`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        val fixture = Paths.get(
            requireNotNull(
                javaClass.classLoader.getResource("content_fidelity/animated_java-1.10.2-export/fixture.json"),
            ).toURI(),
        ).parent
        assertEquals(
            fixture.manifestHash(),
            "d4ae9d007aeb6c736255de8738e5c63c54d6e2b9a0c0f6fbb3dfb5c2d5192c11",
        )

        val resources = DirectoryAssetsManager(fixture.resolve("resources"))
        val data = DirectoryAssetsManager(fixture.resolve("datapacks"), prefix = "data")
        try {
            IT.VERSION
            resources.load()
            data.load()
            assertEquals(
                resources.list("models/blueprint/armor_stand_minimal").size,
                7,
            )
            assertTrue(
                de.bixilon.minosoft.data.registries.identified.ResourceLocation.of(
                    "aj:textures/blueprint/armor_stand_minimal/wood.png",
                ) in resources,
            )

            val session = createSession(version = "1.20.4")
            val origin = Vec3d(8.0, 12.0, 4.0)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, nbt, position -> factory.summon(type, nbt, position) },
                entities = entities,
            )
            val runtime = DataPackFunctionRuntime(DataPackFunctionLibrary.load(data), authority)

            runtime.load()
            runtime.execute("aj:armor_stand_minimal/summon", mapOf("args" to "{}"))
            val context = de.bixilon.minosoft.assets.datapack.DataPackCommandContext(
                de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("aj:test"),
                0,
                runtime.tick,
                position = origin,
            )
            val root = entities.select(
                "@e[type=item_display,tag=aj.armor_stand_minimal.root,limit=1,distance=..0.01]",
                context,
            ).single() as ItemDisplayEntity
            assertFalse("aj.new" in root.commandTags)
            assertEquals(root.attachment.passengers.size, 7)
            assertEquals(
                root.attachment.passengers.map {
                    (it as ItemDisplayEntity).stack!!.nbt.nbt["CustomModelData"]
                }.toSet(),
                setOf(2, 3, 4, 5, 6, 7, 8),
            )

            runtime.executeAs("aj:armor_stand_minimal/animations/walk/play", root)
            runtime.tick()
            assertTrue(
                requireNotNull(authority.score(root.uuid.toString(), "aj.walk.frame")) > 0,
            )

            runtime.executeAs("aj:armor_stand_minimal/remove/this", root)
            assertTrue(entities.select("@e[tag=aj.armor_stand_minimal.entity]", context).isEmpty())
        } finally {
            if (resources.loaded) resources.unload()
            if (data.loaded) data.unload()
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `repeatedly reloads unmodified export with retained entities and owned cleanup`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        val fixture = Paths.get(
            requireNotNull(
                javaClass.classLoader.getResource("content_fidelity/animated_java-1.10.2-export/fixture.json"),
            ).toURI(),
        ).parent
        val resources = DirectoryAssetsManager(fixture.resolve("resources"))
        val data = DirectoryAssetsManager(fixture.resolve("datapacks"), prefix = "data")
        val store = ContentGenerationStore<de.bixilon.minosoft.assets.model.generation.ContentFidelitySnapshot>()
        var host: SessionDataPackRuntime? = null
        try {
            IT.VERSION
            resources.load()
            data.load()
            val cleaned = mutableListOf<String>()
            val published = mutableListOf<String>()

            fun publish(label: String, rejectLoad: Boolean = false): Long {
                val prepared = ContentFidelityLoader(resources, data).prepare()
                val snapshot = if (!rejectLoad) {
                    prepared.value
                } else {
                    val reject = ResourceLocation.of("minosoft_test:animated_java_reject")
                    val load = ResourceLocation.of("minecraft:load")
                    val library = prepared.value.dataPackFunctions
                    val loadTag = requireNotNull(library.tags[load])
                    prepared.value.copy(
                        dataPackFunctions = library.copy(
                            functions = library.functions + (
                                reject to DataPackFunction(reject, listOf("unsupported candidate command"))
                                ),
                            tags = library.tags + (
                                load to loadTag.copy(
                                    values = loadTag.values + DataPackFunctionReference(reject.toString()),
                                )
                                ),
                        ),
                    )
                }
                published += label
                return store.reload {
                    PreparedContent(
                        snapshot,
                        AutoCloseable {
                            try {
                                prepared.cleanup.close()
                            } finally {
                                cleaned += label
                            }
                        },
                    )
                }
            }

            val session = createSession(version = "1.20.4")
            val origin = Vec3d(8.0, 12.0, 4.0)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, nbt, position -> factory.summon(type, nbt, position) },
                entities = entities,
            )
            val runtimeHost = SessionDataPackRuntime(store, authority)
            host = runtimeHost
            val first = publish("generation-0")
            assertTrue(runtimeHost.refresh())
            assertEquals(runtimeHost.activeGenerationId, first)
            runtimeHost.execute("aj:armor_stand_minimal/summon", mapOf("args" to "{}"))
            val context = de.bixilon.minosoft.assets.datapack.DataPackCommandContext(
                ResourceLocation.of("aj:reload_test"),
                0,
                runtimeHost.tick,
                position = origin,
            )
            val root = entities.select(
                "@e[type=item_display,tag=aj.armor_stand_minimal.root,limit=1,distance=..0.01]",
                context,
            ).single() as ItemDisplayEntity
            val rootId = root.id
            val rootUuid = root.uuid
            runtimeHost.executeAs("aj:armor_stand_minimal/animations/walk/play", root)
            runtimeHost.tick()

            var previousLabel = "generation-0"
            repeat(8) { index ->
                val label = "generation-${index + 1}"
                val generation = publish(label)
                assertFalse(previousLabel in cleaned)
                assertTrue(runtimeHost.refresh())
                assertEquals(runtimeHost.activeGenerationId, generation)
                assertTrue(previousLabel in cleaned)
                val retained = entities.select(
                    "@e[type=item_display,tag=aj.armor_stand_minimal.root,limit=1,distance=..0.01]",
                    context,
                ).single()
                assertSame(retained, root)
                assertEquals(retained.id, rootId)
                assertEquals(retained.uuid, rootUuid)
                assertEquals(retained.attachment.passengers.size, 7)
                val frame = requireNotNull(authority.score(rootUuid.toString(), "aj.walk.frame"))
                runtimeHost.tick()
                assertTrue(requireNotNull(authority.score(rootUuid.toString(), "aj.walk.frame")) != frame)
                previousLabel = label
            }

            val stableGeneration = requireNotNull(runtimeHost.activeGenerationId)
            val stableFrame = authority.score(rootUuid.toString(), "aj.walk.frame")
            publish("rejected", rejectLoad = true)
            org.testng.Assert.expectThrows(
                de.bixilon.minosoft.assets.datapack.UnsupportedDataPackCommandException::class.java,
            ) {
                runtimeHost.refresh()
            }
            assertEquals(runtimeHost.activeGenerationId, stableGeneration)
            assertEquals(authority.score(rootUuid.toString(), "aj.walk.frame"), stableFrame)
            assertSame(
                entities.select(
                    "@e[type=item_display,tag=aj.armor_stand_minimal.root,limit=1,distance=..0.01]",
                    context,
                ).single(),
                root,
            )
            assertFalse(previousLabel in cleaned)
            assertFalse("rejected" in cleaned)

            val recovery = publish("recovery")
            assertTrue("rejected" in cleaned)
            assertTrue(runtimeHost.refresh())
            assertEquals(runtimeHost.activeGenerationId, recovery)
            assertTrue(previousLabel in cleaned)
            assertSame(
                entities.select(
                    "@e[type=item_display,tag=aj.armor_stand_minimal.root,limit=1,distance=..0.01]",
                    context,
                ).single(),
                root,
            )
            assertFalse("recovery" in cleaned)

            runtimeHost.close()
            host = null
            assertFalse("recovery" in cleaned)
            store.close()
            assertEquals(cleaned.size, published.size)
            assertEquals(cleaned.toSet(), published.toSet())
        } finally {
            host?.close()
            store.close()
            if (resources.loaded) resources.unload()
            if (data.loaded) data.unload()
            RenderingOptions.disabled = previous
        }
    }

    private fun Path.manifestHash(): String {
        val manifest = MessageDigest.getInstance("SHA-256")
        val files = Files.walk(this).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter {
                    val relative = relativize(it).toString().replace('\\', '/')
                    relative.startsWith("resources/") || relative.startsWith("datapacks/")
                }
                .sorted(compareBy { relativize(it).toString().replace('\\', '/') })
                .toList()
        }
        for (file in files) {
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).toHex()
            val relative = relativize(file).toString().replace('\\', '/')
            manifest.update("$digest  $relative\n".toByteArray())
        }
        return manifest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
