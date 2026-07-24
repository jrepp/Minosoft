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

import de.bixilon.minosoft.assets.AssetsManager
import de.bixilon.minosoft.assets.datapack.DataPackFunctionLibrary
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentParsers
import de.bixilon.minosoft.assets.model.skeletal.SkeletalParseContext
import de.bixilon.minosoft.assets.model.skeletal.SkeletalResourceResolver
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleParsers
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureCatalog
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureSelectionCache
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Discovers registered third-party content formats from the same priority asset
 * view used by vanilla models. Parsing is headless and all-or-nothing.
 */
class ContentFidelityLoader(
    private val assets: AssetsManager,
    private val dataPacks: AssetsManager? = null,
) {

    fun prepare(): PreparedContent<ContentFidelitySnapshot> {
        check(assets.loaded) { "Assets must be loaded before content-fidelity discovery." }
        check(dataPacks == null || dataPacks.loaded) { "Data packs must be loaded before content-fidelity discovery." }
        val skeletal = linkedMapOf<ResourceLocation, List<SkeletalContent>>()
        val animations = linkedMapOf<ResourceLocation, Map<String, de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip>>()
        val textureRules = linkedMapOf<ResourceLocation, de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleSet>()

        val available = assets.list().toSet()
        for (source in available.sortedWith(compareBy(ResourceLocation::namespace, ResourceLocation::path))) {
            if (source.path.endsWith(".jpm", ignoreCase = true)) continue
            SkeletalContentParsers.geometry(source)?.let { parser ->
                assets[source].use { input ->
                    skeletal[source] = parser.parse(context(source), input).models
                }
                return@let
            }
            SkeletalContentParsers.animation(source)?.let { parser ->
                assets[source].use { input ->
                    animations[source] = parser.parse(context(source), input)
                }
                return@let
            }
            if (source.path.endsWith(".properties", ignoreCase = true) && isEntityTextureProperties(source.path)) {
                EntityTextureRuleParsers.parser()?.let { parser ->
                    assets[source].use { input ->
                        textureRules[source] = parser.parse(source, input)
                    }
                }
            }
        }

        val attached = skeletal.mapValues { (source, models) ->
            val sourceStem = source.path.substringAfterLast('/').removeSuffix(".geo.json")
            val matching = animations.filterKeys {
                it.namespace == source.namespace &&
                    it.path.substringAfterLast('/').removeSuffix(".animation.json") == sourceStem
            }.values.fold(linkedMapOf<String, de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip>()) { result, clips ->
                result.apply { putAll(clips) }
            }
            if (matching.isEmpty()) models else models.map { it.copy(animations = it.animations + matching) }
        }

        val catalog = EntityTextureCatalog.build(textureRules, available)
        val cache = EntityTextureSelectionCache()
        return PreparedContent(
            ContentFidelitySnapshot(
                skeletal = attached,
                animations = animations,
                entityTextureRules = textureRules,
                entityTextureMaterials = catalog.entries.values
                    .flatMap { it.materials.values }
                    .associateBy { it.base },
                entityTextureCatalog = catalog,
                entityTextureCache = cache,
                dataPackFunctions = dataPacks?.let(DataPackFunctionLibrary::load) ?: DataPackFunctionLibrary.EMPTY,
            ),
            cleanup = cache,
        )
    }

    private fun context(source: ResourceLocation): SkeletalParseContext {
        return SkeletalParseContext(source, SkeletalResourceResolver { reference ->
            assets.getOrNull(resolve(source, reference))
        })
    }

    private fun resolve(owner: ResourceLocation, rawReference: String): ResourceLocation {
        val reference = if (rawReference.endsWith(".jpm", ignoreCase = true)) rawReference else "$rawReference.jpm"
        if (':' in reference) return ResourceLocation.of(reference)
        val clean = reference.removePrefix("/")
        val path = when {
            clean.startsWith("optifine/") -> clean
            '/' in clean -> clean
            else -> owner.path.substringBeforeLast('/', "") + "/" + clean
        }.removePrefix("/")
        require(path.split('/').none { it == ".." }) { "Skeletal part reference escapes its asset namespace: $rawReference" }
        return ResourceLocation(owner.namespace, path)
    }

    private fun isEntityTextureProperties(path: String): Boolean {
        return path.startsWith("optifine/random/entity/") ||
            path.startsWith("optifine/mob/") ||
            path.startsWith("textures/entity/")
    }
}
