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
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentParsers
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliasSet
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliases
import de.bixilon.minosoft.assets.model.skeletal.cem.CEM_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.gecko.GECKO_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemExpressionEvaluator
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemTransformProperty
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationEvaluator
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleParsers
import de.bixilon.minosoft.assets.model.texture.entity.OptifineEntityTexturePropertiesParser
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.skeletal.binding.SkeletalModelBinder
import de.bixilon.minosoft.protocol.protocol.ProtocolVersions
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.file.Paths

class ContentFidelityMultiVersionTest {

    @Test
    fun `headless fixture binds CEM ETF and Gecko surfaces across 1_19_4 and 1_20_4`() {
        val fixture = Paths.get(
            requireNotNull(javaClass.classLoader.getResource("content_fidelity/multi-version/fixture.json")).toURI(),
        ).parent
        val assets = DirectoryAssetsManager(fixture)
        val entity = ResourceLocation.of("test:zombie")
        val registrations = listOf(
            SkeletalContentParsers.register(CEM_PARSER_REGISTRATION),
            SkeletalContentParsers.register(GECKO_PARSER_REGISTRATION),
            EntityTextureRuleParsers.register("test:multi-version", OptifineEntityTexturePropertiesParser),
            SkeletalPartAliases.register(
                SkeletalPartAliasSet(
                    "test:zombie-1.19.4",
                    entity,
                    ProtocolVersions.V_1_19_4,
                    ProtocolVersions.V_1_19_4,
                    mapOf("leftArm" to "left_arm_legacy"),
                ),
            ),
            SkeletalPartAliases.register(
                SkeletalPartAliasSet(
                    "test:zombie-1.20.4",
                    entity,
                    ProtocolVersions.V_1_20_4,
                    ProtocolVersions.V_1_20_4,
                    mapOf("leftArm" to "left_arm"),
                ),
            ),
        )
        try {
            assets.load()
            val prepared = ContentFidelityLoader(assets).prepare()
            try {
                val snapshot = prepared.value
                val cem = snapshot.skeletal.values.flatten()
                    .single { it.format == SkeletalContentFormat.OPTIFINE_CEM }
                val legacy = SkeletalModelBinder.bind(cem, ProtocolVersions.V_1_19_4, entity)
                val modern = SkeletalModelBinder.bind(cem, ProtocolVersions.V_1_20_4, entity)
                assertTrue("left_arm_legacy" in legacy.model.transforms)
                assertTrue("left_arm" in modern.model.transforms)

                val expression = CemExpressionEvaluator(cem.expressions, modern.aliases)
                    .evaluate(SkeletalExpressionContext(mapOf("age" to 10.0)))
                assertTrue(
                    expression.transforms.getValue("left_arm").getValue(CemTransformProperty.ROTATE_X) > 0.8f,
                )

                val gecko = snapshot.skeletal.values.flatten()
                    .single { it.format == SkeletalContentFormat.GECKOLIB }
                val clip = requireNotNull(gecko.animations["animation.zombie.walk"])
                assertTrue(SkeletalAnimationEvaluator.evaluate(clip, 0.5f).bones.containsKey("root"))

                val base = ResourceLocation.of("test:textures/entity/zombie/zombie.png")
                val materials = requireNotNull(snapshot.entityTextureCatalog[base]).materials
                assertEquals(materials.getValue(2).base.toString(), "test:optifine/random/entity/zombie/zombie2.png")
                assertEquals(materials.getValue(2).emissive.toString(), "test:optifine/random/entity/zombie/zombie2_e.png")
            } finally {
                prepared.cleanup.close()
            }
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
            if (assets.loaded) assets.unload()
        }
    }
}
