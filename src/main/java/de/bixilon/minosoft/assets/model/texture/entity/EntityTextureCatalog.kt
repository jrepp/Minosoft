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

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Immutable, headless mapping between the vanilla entity texture addressed by a
 * model and the finite ETF/OptiFine material set that must be baked.
 */
data class EntityTextureCatalog(
    val entries: Map<ResourceLocation, EntityTextureCatalogEntry> = emptyMap(),
) {
    operator fun get(base: ResourceLocation) = entries[base]

    fun select(
        base: ResourceLocation,
        entityKey: String,
        context: EntityTextureContext,
        tick: Long,
        cache: EntityTextureSelectionCache,
    ): EntityTextureMaterialFrame? {
        val entry = entries[base] ?: return null
        val suffix = cache.select(EntityTextureCacheKey(entityKey, base), entry.rules, context)
        val material = entry.materials[suffix] ?: entry.materials[1] ?: return null
        return material.at(tick, context.seed)
    }

    companion object {
        val EMPTY = EntityTextureCatalog()

        fun build(
            rules: Map<ResourceLocation, EntityTextureRuleSet>,
            available: Set<ResourceLocation>,
        ): EntityTextureCatalog {
            val entries = linkedMapOf<ResourceLocation, EntityTextureCatalogEntry>()
            for ((source, ruleSet) in rules) {
                val base = baseTexture(source) ?: continue
                val suffixes = buildSet {
                    add(1)
                    ruleSet.rules.forEach { addAll(it.suffixes) }
                }
                val materials = suffixes.sorted().associateWith { suffix ->
                    material(source, base, suffix, available)
                }.filterValues { it != null }.mapValues { it.value!! }
                if (materials.isEmpty()) continue
                entries[base] = EntityTextureCatalogEntry(base, ruleSet, materials)
            }
            return EntityTextureCatalog(entries)
        }

        internal fun baseTexture(source: ResourceLocation): ResourceLocation? {
            val relative = when {
                source.path.startsWith("optifine/random/entity/") -> source.path.removePrefix("optifine/random/entity/")
                source.path.startsWith("optifine/mob/") -> source.path.removePrefix("optifine/mob/")
                source.path.startsWith("textures/entity/") -> source.path.removePrefix("textures/entity/")
                else -> return null
            }.removeSuffix(".properties")
            return ResourceLocation(source.namespace, "textures/entity/$relative.png")
        }

        private fun material(
            source: ResourceLocation,
            base: ResourceLocation,
            suffix: Int,
            available: Set<ResourceLocation>,
        ): EntityTextureMaterial? {
            val selected = if (suffix == 1) {
                base.takeIf { it in available } ?: variantTexture(source, suffix).takeIf { it in available }
            } else {
                variantTexture(source, suffix).takeIf { it in available }
            } ?: return null
            val emissive = decorated(selected, "_e").takeIf { it in available }
                ?: decorated(selected, "_emissive").takeIf { it in available }
            val blink = decorated(selected, "_blink").takeIf { it in available }
            val blinkEmissive = decorated(selected, "_blink_e").takeIf { it in available }
                ?: decorated(selected, "_blink_emissive").takeIf { it in available }
            return EntityTextureMaterial(selected, emissive, blink, blinkEmissive)
        }

        private fun variantTexture(source: ResourceLocation, suffix: Int): ResourceLocation {
            val stem = source.path.removeSuffix(".properties")
            val suffixText = if (suffix == 1) "" else suffix.toString()
            return ResourceLocation(source.namespace, "$stem$suffixText.png")
        }

        private fun decorated(texture: ResourceLocation, suffix: String): ResourceLocation {
            return ResourceLocation(texture.namespace, texture.path.removeSuffix(".png") + suffix + ".png")
        }
    }
}

data class EntityTextureCatalogEntry(
    val base: ResourceLocation,
    val rules: EntityTextureRuleSet,
    val materials: Map<Int, EntityTextureMaterial>,
) {
    val textures: Set<ResourceLocation> = buildSet {
        for (material in materials.values) {
            add(material.base)
            material.emissive?.let(::add)
            material.blink?.let(::add)
            material.blinkEmissive?.let(::add)
        }
    }
}
