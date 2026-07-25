/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.assets.ExternalAssetProviders
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityModelRegistry
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
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
    fun `exact Naturalist surface owns filtered assets routes and controllers`() {
        val artifact = Files.createTempFile("naturalist-5.0pre3", ".jar")
        JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
            for (entry in listOf(
                "assets/naturalist/geo/entity/alligator.geo.json",
                "assets/naturalist/geo/entity/ostrich.geo.json",
                "assets/naturalist/animations/alligator.rp_anim.json",
                "assets/naturalist/animations/0_old/alligator.animation.json",
                "assets/naturalist/textures/entity/alligator/alligator.png",
                "assets/naturalist/textures/entity/caterpillar.png",
                "assets/naturalist/textures/entity/lizard/beardie.png",
                "assets/naturalist/textures/entity/zebra.png",
            )) {
                jar.putNextEntry(JarEntry(entry))
                jar.write("{}".encodeToByteArray())
                jar.closeEntry()
            }
        }
        val metadata = FabricMetadata(
            id = "naturalist",
            version = "5.0pre3",
            name = "Naturalist",
            environment = "*",
            entrypoints = setOf("main", "client"),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 2,
            accessWidener = null,
            nestedJarPaths = listOf(
                "META-INF/jars/cloth-config-fabric-8.2.88.jar",
                "META-INF/jars/midnightlib-1.4.1-fabric.jar",
            ),
            source = artifact.toString(),
        )
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        assertEquals(NaturalistCompatibilityAdapter, adapter)
        val scope = FabricRegistrationScope()
        try {
            adapter!!.activate(FabricModProbe(metadata, emptySet(), adapter), scope)
            assertEquals(31, GeckoLibEntityModelRegistry.owners().count { it.value == adapter.id })
            assertEquals(24, GeckoLibControllerBindingRegistry.owners().count { it.value == adapter.id })
            assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:bluejay")))
            assertEquals(
                GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:bluejay")),
                GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:canary")),
            )
            val zebra = assertNotNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:zebra")))
            assertEquals(ResourceLocation.of("naturalist:geo/entity/zebra.geo.json"), zebra.source)
            assertEquals("geometry.sf_nba.ostrich", zebra.identifier)
            assertNull(GeckoLibEntityModelRegistry.identity(ResourceLocation.of("naturalist:lizard_tail")))

            val provider = ExternalAssetProviders.snapshot().single { it.owner == adapter.id }
            val assets = provider.create()
            try {
                assets.load()
                assertTrue(ResourceLocation.of("naturalist:geo/entity/alligator.geo.json") in assets)
                assertTrue(ResourceLocation.of("naturalist:animations/alligator.rp_anim.json") in assets)
                assertTrue(ResourceLocation.of("naturalist:textures/entity/alligator/alligator.png") in assets)
                assertTrue(ResourceLocation.of("naturalist:textures/entity/lizard.png") in assets)
                assertTrue(ResourceLocation.of("naturalist:textures/entity/ostrich.png") in assets)
                assertTrue(ResourceLocation.of("naturalist:geo/entity/zebra.geo.json") in assets)
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
    }
}
