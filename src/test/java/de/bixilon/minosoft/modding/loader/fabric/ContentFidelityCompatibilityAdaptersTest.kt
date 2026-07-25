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

import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentParsers
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliases
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentFidelityCompatibilityAdaptersTest {

    @Test
    fun `exact EMF and GeckoLib adapters own and remove parser registrations`() {
        val emf = metadata(
            id = "entity_model_features",
            version = "3.0.17",
            environment = "client",
            entrypoints = setOf("client", "modmenu", "ebe_v1"),
            accessWidener = "entity_model_features_5.accesswidener",
        )
        val gecko = metadata(
            id = "geckolib",
            version = "4.4.4",
            environment = "*",
            entrypoints = setOf("main", "client"),
            accessWidener = "geckolib.aw",
            nestedJars = 1,
        )
        assertEquals(EntityModelFeaturesCompatibilityAdapter, FabricCompatibilityAdapters.resolve(emf))
        assertEquals(GeckoLibCompatibilityAdapter, FabricCompatibilityAdapters.resolve(gecko))

        val scope = FabricRegistrationScope()
        try {
            EntityModelFeaturesCompatibilityAdapter.activate(FabricModProbe(emf, emptySet(), EntityModelFeaturesCompatibilityAdapter), scope)
            GeckoLibCompatibilityAdapter.activate(FabricModProbe(gecko, emptySet(), GeckoLibCompatibilityAdapter), scope)

            assertEquals(setOf("minosoft:optifine-cem", "minosoft:geckolib-json"), SkeletalContentParsers.snapshot().map { it.id }.toSet())
            assertEquals(
                setOf(
                    "minosoft:emf/player",
                    "minosoft:emf/zombie",
                    "minosoft:emf/cow",
                    "minosoft:emf/pig",
                    "minosoft:emf/sheep",
                ),
                SkeletalPartAliases.snapshot().map { it.id }.toSet(),
            )
        } finally {
            scope.close()
        }

        assertTrue(SkeletalContentParsers.snapshot().isEmpty())
        assertTrue(SkeletalPartAliases.snapshot().isEmpty())
    }

    @Test
    fun `exact ETF adapter owns and removes its rule parser`() {
        val etf = metadata(
            id = "entity_texture_features",
            version = "7.0.13",
            environment = "client",
            entrypoints = setOf("client", "modmenu"),
            accessWidener = "entity_texture_features_5.accesswidener",
        )
        assertEquals(EntityTextureFeaturesCompatibilityAdapter, FabricCompatibilityAdapters.resolve(etf))

        val scope = FabricRegistrationScope()
        try {
            EntityTextureFeaturesCompatibilityAdapter.activate(
                FabricModProbe(etf, emptySet(), EntityTextureFeaturesCompatibilityAdapter),
                scope,
            )
            assertEquals(listOf("minosoft:optifine-random-entities"), EntityTextureRuleParsers.snapshot())
        } finally {
            scope.close()
        }

        assertTrue(EntityTextureRuleParsers.snapshot().isEmpty())
    }

    private fun metadata(
        id: String,
        version: String,
        environment: String,
        entrypoints: Set<String>,
        accessWidener: String,
        nestedJars: Int = 0,
    ) = FabricMetadata(
        id = id,
        version = version,
        name = id,
        environment = environment,
        entrypoints = entrypoints,
        dependencies = emptyMap(),
        provides = emptySet(),
        mixins = 1,
        accessWidener = accessWidener,
        nestedJarPaths = List(nestedJars) { "META-INF/jars/$it.jar" },
        source = "test",
    )
}
