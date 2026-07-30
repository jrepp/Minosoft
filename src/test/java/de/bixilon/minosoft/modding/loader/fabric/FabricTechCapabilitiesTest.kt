/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.assets.ExternalAssetProviders
import de.bixilon.minosoft.assets.IntegratedAssets
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FabricTechCapabilitiesTest {
    @Test
    fun `energy storage clamps transfers and honors simulation`() {
        val storage = FabricEnergyStorage(capacity = 100L, initialAmount = 20L)

        assertEquals(80L, storage.insert(200L, simulate = true))
        assertEquals(20L, storage.amount)
        assertEquals(80L, storage.insert(200L))
        assertEquals(100L, storage.amount)
        assertEquals(60L, storage.extract(60L, simulate = true))
        assertEquals(100L, storage.amount)
        assertEquals(100L, storage.extract(200L))
        assertEquals(0L, storage.amount)
        assertFailsWith<IllegalArgumentException> { storage.insert(-1L) }
    }

    @Test
    fun `content catalog reads namespaced source resources`() {
        val jarPath = createTempFile("minosoft-tech-content-", ".jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            for (entry in listOf(
                "assets/techreborn/blockstates/generator.json",
                "assets/techreborn/models/block/generator.json",
                "assets/other/models/item/example.json",
                "data/techreborn/recipes/generator.json",
                "data/techreborn/loot_tables/blocks/generator.json",
                "data/techreborn/worldgen/configured_feature/ruby_ore.json",
            )) {
                jar.putNextEntry(JarEntry(entry))
                jar.write("{}".toByteArray())
                jar.closeEntry()
            }
        }
        val metadata = FabricMetadata(
            id = "techreborn",
            version = "5.10.4",
            name = "Tech Reborn",
            environment = "*",
            entrypoints = setOf("main", "client", "rei_client"),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 0,
            accessWidener = "techreborn.accesswidener",
            nestedJarPaths = emptyList(),
            source = jarPath.toString(),
        )

        val catalog = FabricContentCatalogReader.read(metadata)

        assertEquals(setOf("other", "techreborn"), catalog.assetNamespaces)
        assertEquals(setOf("techreborn"), catalog.dataNamespaces)
        assertEquals(setOf("generator"), catalog.blockStates)
        assertEquals(setOf("block/generator"), catalog.models)
        assertEquals(setOf("generator"), catalog.recipes)
        assertEquals(setOf("blocks/generator"), catalog.lootTables)
        assertEquals(setOf("configured_feature/ruby_ore"), catalog.worldGeneration)
        assertEquals(5, catalog.resources)
    }

    @Test
    fun `world content decodes blocks items ores and stable palette`() {
        val jarPath = createTempFile("minosoft-tech-world-", ".jar")
        val entries = linkedMapOf(
            "assets/techreborn/blockstates/bauxite_ore.json" to """
                {"variants":{"active=false":{"model":"techreborn:block/bauxite_ore"},"active=true":{"model":"techreborn:block/bauxite_ore_active"}}}
            """.trimIndent(),
            "assets/techreborn/blockstates/deepslate_bauxite_ore.json" to "{}",
            "assets/techreborn/models/item/bauxite_ore.json" to "{}",
            "assets/techreborn/models/item/bauxite_dust.json" to "{}",
            "data/techreborn/worldgen/configured_feature/bauxite_ore.json" to """
                {
                  "type":"minecraft:ore",
                  "config":{"size":6,"targets":[
                    {"state":{"Name":"techreborn:bauxite_ore"},"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:stone_ore_replaceables"}},
                    {"state":{"Name":"techreborn:deepslate_bauxite_ore"},"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:deepslate_ore_replaceables"}}
                  ]}
                }
            """.trimIndent(),
            "data/techreborn/worldgen/placed_feature/bauxite_ore.json" to """
                {
                  "feature":"techreborn:bauxite_ore",
                  "placement":[
                    {"type":"minecraft:count","count":12},
                    {"type":"minecraft:in_square"},
                    {"type":"minecraft:height_range","height":{"type":"minecraft:uniform","min_inclusive":{"above_bottom":0},"max_inclusive":{"absolute":20}}}
                  ]
                }
            """.trimIndent(),
        )
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            for ((name, value) in entries) {
                jar.putNextEntry(JarEntry(name))
                jar.write(value.toByteArray())
                jar.closeEntry()
            }
        }

        val content = FabricWorldContentReader.read(techMetadata(jarPath.toString()))
        val snapshot = FabricRegistrySnapshot.of(content)

        assertEquals(3, content.content.size)
        assertEquals(2, content.blocks.size)
        assertEquals(2, content.items.size)
        assertEquals(mapOf("active" to listOf("false", "true")), content.blocks.first { it.id.endsWith("bauxite_ore") && !it.id.contains("deepslate") }.blockProperties)
        assertEquals(1, content.ores.size)
        assertEquals(12, content.ores.single().count)
        assertEquals(-64, content.ores.single().minHeight.resolve(-64, 319))
        assertEquals(20, content.ores.single().maxHeight.resolve(-64, 319))
        snapshot.validate(content)
        val id = requireNotNull(snapshot.paletteId("techreborn:bauxite_ore"))
        assertEquals("techreborn:bauxite_ore", snapshot.identifier(id))
        assertEquals(3, snapshot.states.size)
        val activeState = requireNotNull(snapshot.statePaletteId("techreborn:bauxite_ore", mapOf("active" to "true")))
        assertEquals(mapOf("active" to "true"), snapshot.state(activeState)?.properties)
        assertTrue(content.fingerprint.matches("[0-9a-f]{64}".toRegex()))
    }

    @Test
    fun `world content rejects combinatorial block state expansion`() {
        val properties = (0 until 17).associate { "property_$it" to listOf("false", "true") }
        val content = FabricWorldContent(
            namespace = "test",
            content = listOf(FabricContentDefinition("test:explosive", true, false, properties)),
            ores = emptyList(),
            fingerprint = "test",
        )

        assertFailsWith<IllegalArgumentException> { FabricRegistrySnapshot.of(content) }
    }

    @Test
    fun `owned technical capabilities leave no stale registrations`() {
        val scope = FabricRegistrationScope()
        scope.own(FabricEnergyCapabilities.register("test:energy", FabricEnergyCapability("energy", "1")))
        scope.own(FabricApiModuleRegistry.register("test:api", FabricApiModules("1", mapOf("module" to "1"))))
        scope.own(FabricClientEvents.install("test:api"))
        scope.own(FabricClientConnectionEvents.install("test:api"))
        scope.own(FabricClientCommands.install("test:api"))
        scope.own(FabricClientPayloadChannels.install("test:api"))
        scope.own(FabricClientTickEvents.install("test:api"))
        scope.own(FabricBlockMutationEvents.install("test:api"))
        scope.own(FabricChunkEvents.install("test:api"))
        scope.own(FabricHudLayers.install("test:api"))
        scope.own(FabricEntityEvents.install("test:api"))
        scope.own(FabricInputEvents.install("test:api"))
        scope.own(FabricKeyBindings.install("test:api"))
        scope.own(FabricParticleEvents.install("test:api"))
        scope.own(FabricPlayerInteractionHooks.install("test:api"))
        scope.own(FabricRemoteRegistrySync.install("test:api"))
        scope.own(FabricResourceReloadEvents.install("test:api"))
        scope.own(FabricScreens.install("test:api"))
        scope.own(FabricSoundEvents.install("test:api"))
        scope.own(FabricWorldEvents.install("test:api"))
        scope.own(FabricContentCatalogs.register("test:content", FabricContentCatalog(
            modId = "test",
            assetNamespaces = emptySet(),
            dataNamespaces = emptySet(),
            blockStates = emptySet(),
            models = emptySet(),
            recipes = emptySet(),
            lootTables = emptySet(),
            worldGeneration = emptySet(),
        )))
        val worldContent = FabricWorldContent("test", emptyList(), emptyList(), "fingerprint")
        scope.own(FabricWorldContents.register("test:world", worldContent))
        scope.own(ExternalAssetProviders.register("test:assets") { IntegratedAssets.DEFAULT })

        assertTrue(FabricEnergyCapabilities.registrations().isNotEmpty())
        assertTrue(FabricApiModuleRegistry.registrations().isNotEmpty())
        assertEquals(listOf("test:api"), FabricClientEvents.providers())
        assertEquals(listOf("test:api"), FabricClientConnectionEvents.providers())
        assertEquals(listOf("test:api"), FabricClientCommands.providers())
        assertEquals(listOf("test:api"), FabricClientPayloadChannels.providers())
        assertEquals(listOf("test:api"), FabricClientTickEvents.providers())
        assertEquals(listOf("test:api"), FabricBlockMutationEvents.providers())
        assertEquals(listOf("test:api"), FabricChunkEvents.providers())
        assertEquals(listOf("test:api"), FabricHudLayers.providers())
        assertEquals(listOf("test:api"), FabricEntityEvents.providers())
        assertEquals(listOf("test:api"), FabricInputEvents.providers())
        assertEquals(listOf("test:api"), FabricKeyBindings.providers())
        assertEquals(listOf("test:api"), FabricParticleEvents.providers())
        assertEquals(listOf("test:api"), FabricPlayerInteractionHooks.providers())
        assertEquals(listOf("test:api"), FabricRemoteRegistrySync.providers())
        assertEquals(listOf("test:api"), FabricResourceReloadEvents.providers())
        assertEquals(listOf("test:api"), FabricScreens.providers())
        assertEquals(listOf("test:api"), FabricSoundEvents.providers())
        assertEquals(listOf("test:api"), FabricWorldEvents.providers())
        assertTrue(FabricContentCatalogs.registrations().isNotEmpty())
        assertTrue(FabricWorldContents.registrations().isNotEmpty())
        assertTrue(ExternalAssetProviders.snapshot().isNotEmpty())
        scope.close()
        assertTrue(FabricEnergyCapabilities.registrations().isEmpty())
        assertTrue(FabricApiModuleRegistry.registrations().isEmpty())
        assertTrue(FabricClientEvents.providers().isEmpty())
        assertTrue(FabricClientConnectionEvents.providers().isEmpty())
        assertTrue(FabricClientCommands.providers().isEmpty())
        assertTrue(FabricClientPayloadChannels.providers().isEmpty())
        assertTrue(FabricClientTickEvents.providers().isEmpty())
        assertTrue(FabricBlockMutationEvents.providers().isEmpty())
        assertTrue(FabricChunkEvents.providers().isEmpty())
        assertTrue(FabricHudLayers.providers().isEmpty())
        assertTrue(FabricEntityEvents.providers().isEmpty())
        assertTrue(FabricInputEvents.providers().isEmpty())
        assertTrue(FabricKeyBindings.providers().isEmpty())
        assertTrue(FabricParticleEvents.providers().isEmpty())
        assertTrue(FabricPlayerInteractionHooks.providers().isEmpty())
        assertTrue(FabricRemoteRegistrySync.providers().isEmpty())
        assertTrue(FabricResourceReloadEvents.providers().isEmpty())
        assertTrue(FabricScreens.providers().isEmpty())
        assertTrue(FabricSoundEvents.providers().isEmpty())
        assertTrue(FabricWorldEvents.providers().isEmpty())
        assertTrue(FabricContentCatalogs.registrations().isEmpty())
        assertTrue(FabricWorldContents.registrations().isEmpty())
        assertTrue(ExternalAssetProviders.snapshot().isEmpty())
        scope.close()
    }

    private fun techMetadata(source: String) = FabricMetadata(
        id = "techreborn",
        version = "5.10.4",
        name = "Tech Reborn",
        environment = "*",
        entrypoints = setOf("main", "client", "rei_client"),
        dependencies = emptyMap(),
        provides = emptySet(),
        mixins = 0,
        accessWidener = "techreborn.accesswidener",
        nestedJarPaths = emptyList(),
        source = source,
    )
}
