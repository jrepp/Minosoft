/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuntimeEnvironment
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.util.json.Jackson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricPackPreflightTest {
    @Test
    fun `metadata and nested recursion are bounded`() {
        assertFailsWith<IllegalArgumentException> {
            FabricMetadataReader.read(
                ByteArrayInputStream(ByteArray(FabricMetadataReader.MAX_METADATA_BYTES + 1)),
                "oversized metadata",
            )
        }

        val root = createTempDirectory("minosoft-fabric-nesting-")
        val metadata = root.resolve("metadata").createDirectories()
        val mods = root.resolve("mods").createDirectories()
        Files.writeString(metadata.resolve("fabric.mod.json"), """{"schemaVersion":1,"id":"test_pack","version":"1.0.0"}""")
        val nestedPath = "nested.jar"
        val modJson = """{"schemaVersion":1,"id":"outer_mod","version":"1.0.0","jars":[{"file":"$nestedPath"}]}"""
        JarOutputStream(Files.newOutputStream(mods.resolve("outer.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("fabric.mod.json"))
            jar.write(modJson.toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry(nestedPath))
            jar.write(nestedChain(FabricPackPreflight.MAX_NESTED_DEPTH + 1))
            jar.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> { FabricPackPreflight.inspect(root) }
    }

    @Test
    fun `pinned inventory management surface owns its container screen extension`() {
        val metadata = FabricMetadata(
            id = "inventorymanagement",
            version = "1.5.0",
            name = "Inventory Management",
            environment = "*",
            entrypoints = setOf("main", "client", "modmenu"),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 2,
            accessWidener = null,
            nestedJarPaths = emptyList(),
            source = "test",
        )
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        assertEquals(InventoryManagementCompatibilityAdapter, adapter)
        val scope = FabricRegistrationScope()
        try {
            adapter!!.activate(FabricModProbe(metadata, emptySet(), adapter), scope)
            assertEquals(listOf(minosoft("inventory_management")), FabricContainerScreenExtensions.registrations())
        } finally {
            scope.close()
        }
        assertTrue(FabricContainerScreenExtensions.registrations().isEmpty())
    }

    @Test
    fun `pinned JEI surface owns its recipe screen and container extension`() {
        val metadata = FabricMetadata(
            id = "jei",
            version = "17.3.1.5",
            name = "Just Enough Items",
            environment = "*",
            entrypoints = setOf("client", "jei_mod_plugin", "main"),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 1,
            accessWidener = "jei.accesswidener",
            nestedJarPaths = emptyList(),
            source = "test",
        )
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        assertEquals(JeiCompatibilityAdapter, adapter)
        val scope = FabricRegistrationScope()
        try {
            adapter!!.activate(FabricModProbe(metadata, emptySet(), adapter), scope)
            assertEquals(listOf(minosoft("jei_container")), FabricContainerScreenExtensions.registrations())
            assertEquals(listOf(minosoft("jei_recipes")), FabricScreens.registrations().map { it.id })
        } finally {
            scope.close()
        }
        assertTrue(FabricContainerScreenExtensions.registrations().isEmpty())
        assertTrue(FabricScreens.registrations().isEmpty())
    }

    @Test
    fun `pinned Iris surface owns render and shader reload callbacks`() {
        val metadata = FabricMetadata(
            id = "iris",
            version = "1.7.2+mc1.20.4",
            name = "Iris",
            environment = "client",
            entrypoints = setOf("modmenu"),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 9,
            accessWidener = "iris.accesswidener",
            nestedJarPaths = List(5) { "META-INF/jars/$it.jar" },
            source = "test",
        )
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        assertEquals(IrisCompatibilityAdapter, adapter)
        val scope = FabricRegistrationScope()
        try {
            adapter!!.activate(FabricModProbe(metadata, emptySet(), adapter), scope)
            assertEquals(listOf(IrisCompatibilityAdapter.id), FabricClientEvents.registrations(FabricClientEventPhase.BEFORE_WORLD_RENDER))
            assertEquals(listOf(IrisCompatibilityAdapter.id), FabricResourceReloadEvents.registrations(FabricResourceReloadPhase.COMPLETE))
        } finally {
            scope.close()
        }
        assertTrue(FabricClientEvents.registrations(FabricClientEventPhase.BEFORE_WORLD_RENDER).isEmpty())
        assertTrue(FabricResourceReloadEvents.registrations(FabricResourceReloadPhase.COMPLETE).isEmpty())
    }

    @Test
    fun `adapter registry rejects duplicate ids and ambiguous matches`() {
        fun adapter(id: String) = object : FabricCompatibilityAdapter {
            override val id = id
            override val handledBlockers = emptySet<FabricCompatibilityBlocker>()
            override val capabilities = emptySet<FabricHostCapability>()
            override fun supports(metadata: FabricMetadata) = metadata.id == "target"
            override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) = Unit
        }

        val duplicate = FabricCompatibilityAdapterRegistry(listOf(adapter("one")))
        assertFailsWith<IllegalArgumentException> { duplicate.register(adapter("one")) }

        val ambiguous = FabricCompatibilityAdapterRegistry(listOf(adapter("one"), adapter("two")))
        val metadata = FabricMetadata(
            id = "target",
            version = "1.0.0",
            name = "Target",
            environment = "client",
            entrypoints = emptySet(),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 0,
            accessWidener = null,
            nestedJarPaths = emptyList(),
            source = "test",
        )
        assertFailsWith<IllegalArgumentException> { ambiguous.resolve(metadata) }
    }

    @Test
    fun `reports unsupported transformation and linkage features`() {
        val root = createTempDirectory("minosoft-fabric-pack-")
        val metadata = root.resolve("metadata").createDirectories()
        val mods = root.resolve("mods").createDirectories()
        Files.writeString(metadata.resolve("fabric.mod.json"), """
            {
              "schemaVersion": 1,
              "id": "test_pack",
              "version": "1.0.0",
              "depends": {"fabricloader": ">=0.15.11", "test_mod": "~1.0"}
            }
        """.trimIndent())
        writeMod(mods.resolve("test.jar"), """
                {
                  "schemaVersion": 1,
                  "id": "test_mod",
                  "version": "1.0.1",
                  "environment": "client",
                  "entrypoints": {"client": ["example.Client"]},
                  "mixins": ["test.mixins.json"],
                  "accessWidener": "test.accesswidener",
                  "jars": [{"file": "META-INF/jars/api.jar"}]
                }
            """.trimIndent())

        val report = FabricPackPreflight.inspect(root)

        assertEquals("test_pack", report.pack.id)
        assertEquals("test_mod", report.mods.single().metadata.id)
        assertEquals(
            setOf(
                FabricCompatibilityBlocker.ACTIVATION_ADAPTER,
                FabricCompatibilityBlocker.ACCESS_WIDENER,
                FabricCompatibilityBlocker.ENTRYPOINT_LINKAGE,
                FabricCompatibilityBlocker.MIXINS,
                FabricCompatibilityBlocker.NESTED_JARS,
            ),
            report.mods.single().blockers,
        )
        assertFalse(report.activatable)
    }

    @Test
    fun `pinned Sodium surface activates through owned native adapter`() {
        val root = createTempDirectory("minosoft-sodium-pack-")
        val metadata = root.resolve("metadata").createDirectories()
        val mods = root.resolve("mods").createDirectories()
        Files.writeString(metadata.resolve("fabric.mod.json"), """
            {
              "schemaVersion": 1,
              "id": "sodium_pack",
              "version": "1.0.0",
              "depends": {"fabricloader": ">=0.15.11", "sodium": "=0.5.8+mc1.20.4"}
            }
        """.trimIndent())
        writeMod(mods.resolve("sodium.jar"), """
                {
                  "schemaVersion": 1,
                  "id": "sodium",
                  "version": "0.5.8+mc1.20.4",
                  "environment": "client",
                  "entrypoints": {
                    "client": ["me.jellysquid.mods.sodium.client.SodiumClientMod"],
                    "preLaunch": ["me.jellysquid.mods.sodium.client.SodiumPreLaunch"]
                  },
                  "mixins": ["sodium.mixins.json"],
                  "accessWidener": "sodium.accesswidener",
                  "jars": [
                    {"file": "META-INF/jars/one.jar"},
                    {"file": "META-INF/jars/two.jar"},
                    {"file": "META-INF/jars/three.jar"},
                    {"file": "META-INF/jars/four.jar"},
                    {"file": "META-INF/jars/five.jar"}
                  ]
                }
            """.trimIndent())

        val report = FabricPackPreflight.inspect(root)
        val probe = report.mods.single()

        assertTrue(report.activatable)
        assertEquals(FabricActivationMode.ADAPTED, report.activation)
        assertEquals(FabricActivationMode.ADAPTED, probe.activation)
        assertEquals("minosoft:sodium-0.5.8-mc1.20.4", probe.adapter?.id)
        assertTrue(probe.blockers.isEmpty())

        FabricPackLoader.deactivate()
        try {
            FabricPackLoader.activate(root)
            assertEquals(report.pack.id, FabricPackLoader.current()?.report?.pack?.id)
            assertTrue(SodiumRendererHook in FabricRendererRegistry.snapshot())
            assertTrue("sodium" in EntityTextureRuntimeEnvironment.loadedMods())
        } finally {
            FabricPackLoader.deactivate()
        }
        assertTrue(FabricRendererRegistry.snapshot().isEmpty())
        assertTrue(EntityTextureRuntimeEnvironment.loadedMods().isEmpty())
    }

    @Test
    fun `stacked pinned surfaces activate independent owned hooks`() {
        val root = createTempDirectory("minosoft-fabric-stack-")
        val metadata = root.resolve("metadata").createDirectories()
        val mods = root.resolve("mods").createDirectories()
        Files.writeString(metadata.resolve("fabric.mod.json"), """
            {
              "schemaVersion": 1,
              "id": "fabric_stack",
              "version": "1.0.0",
              "depends": {
                "fabricloader": ">=0.15.11",
                "sodium": "=0.5.8+mc1.20.4",
                "entityculling": "=1.10.5",
                "immediatelyfast": "=1.5.5+1.20.4"
              }
            }
        """.trimIndent())
        writeMod(mods.resolve("sodium.jar"), """
            {
              "schemaVersion": 1,
              "id": "sodium",
              "version": "0.5.8+mc1.20.4",
              "environment": "client",
              "entrypoints": {"client": ["SodiumClient"], "preLaunch": ["SodiumPreLaunch"]},
              "mixins": ["sodium.mixins.json"],
              "accessWidener": "sodium.accesswidener",
              "jars": [{"file":"1.jar"},{"file":"2.jar"},{"file":"3.jar"},{"file":"4.jar"},{"file":"5.jar"}]
            }
        """.trimIndent())
        writeMod(mods.resolve("entityculling.jar"), """
            {
              "schemaVersion": 1,
              "id": "entityculling",
              "version": "1.10.5",
              "entrypoints": {"client": ["EntityCulling"], "modmenu": ["EntityCullingMenu"]},
              "mixins": ["entityculling.mixins.json"],
              "jars": [{"file":"transition.jar"},{"file":"trender.jar"}]
            }
        """.trimIndent())
        writeMod(mods.resolve("immediatelyfast.jar"), """
            {
              "schemaVersion": 1,
              "id": "immediatelyfast",
              "version": "1.5.5+1.20.4",
              "environment": "client",
              "mixins": ["common.mixins.json", "fabric.mixins.json"],
              "accessWidener": "immediatelyfast.accesswidener",
              "jars": [{"file":"reflect.jar"}]
            }
        """.trimIndent())

        val report = FabricPackPreflight.inspect(root)
        assertTrue(report.activatable)
        assertEquals(FabricActivationMode.ADAPTED, report.activation)
        assertEquals(
            setOf(
                FabricHostCapability.CHUNK_RENDER_SCHEDULING,
                FabricHostCapability.ENTITY_VISIBILITY,
                FabricHostCapability.FRAME_BATCHING,
            ),
            report.mods.flatMap { it.adapter!!.capabilities }.toSet(),
        )

        FabricPackLoader.deactivate()
        try {
            FabricPackLoader.activate(root)
            assertEquals(listOf(SodiumCompatibilityAdapter.id), FabricRendererRegistry.registrations().map { it.owner })
            assertEquals(listOf(EntityCullingCompatibilityAdapter.id), FabricEntityVisibilityHooks.registrations().map { it.owner })
            assertEquals(listOf(ImmediatelyFastCompatibilityAdapter.id), FabricFrameHooks.registrations().map { it.owner })
        } finally {
            FabricPackLoader.deactivate()
        }
        assertTrue(FabricRendererRegistry.registrations().isEmpty())
        assertTrue(FabricEntityVisibilityHooks.registrations().isEmpty())
        assertTrue(FabricFrameHooks.registrations().isEmpty())
    }

    @Test
    fun `Iris and Sodium activate together through owned adapters`() {
        val root = createTempDirectory("minosoft-fabric-mixed-")
        val metadata = root.resolve("metadata").createDirectories()
        val mods = root.resolve("mods").createDirectories()
        Files.writeString(metadata.resolve("fabric.mod.json"), """
            {
              "schemaVersion": 1,
              "id": "mixed_pack",
              "version": "1.0.0",
              "depends": {"sodium": "=0.5.8+mc1.20.4", "iris": "=1.7.2+mc1.20.4"}
            }
        """.trimIndent())
        writeMod(mods.resolve("sodium.jar"), """
            {
              "schemaVersion": 1,
              "id": "sodium",
              "version": "0.5.8+mc1.20.4",
              "environment": "client",
              "entrypoints": {"client": ["SodiumClient"], "preLaunch": ["SodiumPreLaunch"]},
              "mixins": ["sodium.mixins.json"],
              "accessWidener": "sodium.accesswidener",
              "jars": [{"file":"1.jar"},{"file":"2.jar"},{"file":"3.jar"},{"file":"4.jar"},{"file":"5.jar"}]
            }
        """.trimIndent())
        writeMod(mods.resolve("iris.jar"), """
            {
              "schemaVersion": 1,
              "id": "iris",
              "version": "1.7.2+mc1.20.4",
              "environment": "client",
              "entrypoints": {"modmenu": ["IrisMenu"]},
              "depends": {"sodium": ["0.5.8", "0.5.11"]},
              "mixins": [
                "iris.1.json",
                "iris.2.json",
                "iris.3.json",
                "iris.4.json",
                "iris.5.json",
                "iris.6.json",
                "iris.7.json",
                "iris.8.json",
                "iris.9.json"
              ],
              "accessWidener": "iris.accesswidener",
              "jars": [
                {"file":"iris-1.jar"},
                {"file":"iris-2.jar"},
                {"file":"iris-3.jar"},
                {"file":"iris-4.jar"},
                {"file":"iris-5.jar"}
              ]
            }
        """.trimIndent())

        val report = FabricPackPreflight.inspect(root)
        assertTrue(report.activatable)
        assertTrue(report.launchable)
        assertEquals(FabricActivationMode.ADAPTED, report.activation)
        assertEquals(listOf("sodium", "iris"), report.activatableMods.map { it.metadata.id })
        assertTrue(report.blockedMods.isEmpty())

        FabricPackLoader.deactivate()
        try {
            FabricPackLoader.activate(root)
            assertEquals(listOf(SodiumCompatibilityAdapter.id), FabricRendererRegistry.registrations().map { it.owner })
            assertEquals(FabricModRuntimeStatus.ACTIVE, FabricModDiagnostics.snapshot()?.mods?.single { it.id == "iris" }?.status)
            assertEquals(listOf(IrisCompatibilityAdapter.id), FabricClientEvents.registrations(FabricClientEventPhase.BEFORE_WORLD_RENDER))
        } finally {
            FabricPackLoader.deactivate()
        }
        assertTrue(FabricRendererRegistry.registrations().isEmpty())
        assertTrue(FabricClientEvents.registrations(FabricClientEventPhase.BEFORE_WORLD_RENDER).isEmpty())
    }

    private fun writeMod(path: java.nio.file.Path, json: String) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("fabric.mod.json"))
            jar.write(json.toByteArray())
            jar.closeEntry()
            Jackson.MAPPER.readTree(json).path("jars").forEachIndexed { index, nested ->
                jar.putNextEntry(JarEntry(nested.path("file").asText()))
                jar.write(nestedMod("nested_${index}"))
                jar.closeEntry()
            }
        }
    }

    private fun nestedMod(id: String): ByteArray {
        val output = ByteArrayOutputStream()
        JarOutputStream(output).use { jar ->
            jar.putNextEntry(JarEntry("fabric.mod.json"))
            jar.write("""{"schemaVersion":1,"id":"$id","version":"1.0.0"}""".toByteArray())
            jar.closeEntry()
        }
        return output.toByteArray()
    }

    private fun nestedChain(depth: Int): ByteArray {
        val output = ByteArrayOutputStream()
        JarOutputStream(output).use { jar ->
            val child = "child.jar"
            val metadata = if (depth > 1) {
                """{"schemaVersion":1,"id":"nested_$depth","version":"1.0.0","jars":[{"file":"$child"}]}"""
            } else {
                """{"schemaVersion":1,"id":"nested_$depth","version":"1.0.0"}"""
            }
            jar.putNextEntry(JarEntry("fabric.mod.json"))
            jar.write(metadata.toByteArray())
            jar.closeEntry()
            if (depth > 1) {
                jar.putNextEntry(JarEntry(child))
                jar.write(nestedChain(depth - 1))
                jar.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
