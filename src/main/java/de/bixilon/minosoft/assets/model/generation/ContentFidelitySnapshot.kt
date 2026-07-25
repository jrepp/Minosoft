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

package de.bixilon.minosoft.assets.model.generation

import de.bixilon.minosoft.assets.datapack.DataPackFunctionLibrary
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimatableManager
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterial
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureCatalog
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleSet
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureSelectionCache
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Immutable CPU-side content consumed by both headless validation and renderer
 * binding. GPU resources belong to [PreparedContent.cleanup], never this value.
 */
data class ContentFidelitySnapshot(
    val skeletal: Map<ResourceLocation, List<SkeletalContent>> = emptyMap(),
    val animations: Map<ResourceLocation, Map<String, SkeletalAnimationClip>> = emptyMap(),
    val entityTextureRules: Map<ResourceLocation, EntityTextureRuleSet> = emptyMap(),
    val entityTextureMaterials: Map<ResourceLocation, EntityTextureMaterial> = emptyMap(),
    val entityTextureCatalog: EntityTextureCatalog = EntityTextureCatalog.EMPTY,
    val entityTextureCache: EntityTextureSelectionCache = EntityTextureSelectionCache(),
    val entityTextureEmissiveSuffixes: Set<String> = EntityTextureCatalog.DEFAULT_EMISSIVE_SUFFIXES,
    val dataPackFunctions: DataPackFunctionLibrary = DataPackFunctionLibrary.EMPTY,
) {
    init {
        require(skeletal.keys.none { it in animations || it in entityTextureRules || it in entityTextureMaterials }) {
            "A content-fidelity source cannot describe both skeletal and entity-texture content."
        }
    }

    fun skeletal(identity: SkeletalContentIdentity): SkeletalContent? {
        val matches = skeletal[identity.source].orEmpty().filter {
            it.format == identity.format && it.identifier == identity.identifier
        }
        require(matches.size <= 1) { "Duplicate skeletal content identity $identity." }
        return matches.singleOrNull()
    }

    /**
     * Builds a renderer-independent generic-object manager from this exact CPU
     * content generation and the active adapted-mod controller registration.
     */
    fun geckoAnimatableManager(identity: SkeletalContentIdentity): GeckoLibAnimatableManager? {
        require(identity.format == SkeletalContentFormat.GECKOLIB) {
            "Generic GeckoLib animatables require a GeckoLib content identity."
        }
        val content = skeletal(identity) ?: return null
        return GeckoLibControllerBindingRegistry.createManager(identity, content.animations)
    }
}
