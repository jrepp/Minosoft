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
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliasRegistry
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliases
import de.bixilon.minosoft.assets.model.skeletal.cem.CEM_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.skeletal.gecko.GECKO_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleParsers
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureSelectionCache
import de.bixilon.minosoft.assets.model.texture.entity.OptifineEntityTexturePropertiesParser

object EntityModelFeaturesCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:entity-model-features-3.0.17-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.SKELETAL_CONTENT)
    override val functionality = FabricFunctionalityCatalog.ENTITY_MODEL_FEATURES

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "entity_model_features" &&
            metadata.version == "3.0.17" &&
            metadata.environment == "client" &&
            metadata.entrypoints == setOf("client", "modmenu", "ebe_v1") &&
            metadata.mixins == 1 &&
            metadata.accessWidener == "entity_model_features_5.accesswidener" &&
            metadata.nestedJars == 0
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Entity Model Features artifact: ${probe.metadata.version}" }
        scope.own(SkeletalContentParsers.register(CEM_PARSER_REGISTRATION))
        SkeletalPartAliasRegistry.MINOSOFT_NATIVE.forEach {
            scope.own(SkeletalPartAliases.register(it))
        }
    }
}

object GeckoLibCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:geckolib-4.4.4-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.SKELETAL_CONTENT)
    override val functionality = FabricFunctionalityCatalog.GECKOLIB

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "geckolib" &&
            metadata.version == "4.4.4" &&
            metadata.environment == "*" &&
            metadata.entrypoints == setOf("main", "client") &&
            metadata.mixins == 1 &&
            metadata.accessWidener == "geckolib.aw" &&
            metadata.nestedJars == 1
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported GeckoLib artifact: ${probe.metadata.version}" }
        scope.own(SkeletalContentParsers.register(GECKO_PARSER_REGISTRATION))
    }
}

object EntityTextureFeaturesCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:entity-texture-features-7.0.13-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.ENTITY_TEXTURE_CONTENT)
    override val functionality = FabricFunctionalityCatalog.ENTITY_TEXTURE_FEATURES

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "entity_texture_features" &&
            metadata.version == "7.0.13" &&
            metadata.environment == "client" &&
            metadata.entrypoints == setOf("client", "modmenu") &&
            metadata.mixins == 1 &&
            metadata.accessWidener == "entity_texture_features_5.accesswidener" &&
            metadata.nestedJars == 0
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Entity Texture Features artifact: ${probe.metadata.version}" }
        scope.own(EntityTextureRuleParsers.register("minosoft:optifine-random-entities", OptifineEntityTexturePropertiesParser))
        scope.own(EntityTextureSelectionCache())
    }
}
