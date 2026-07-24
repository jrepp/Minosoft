/*
 * Minosoft
 * Copyright (C) 2020-2023 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.kutil.json.JsonObject
import de.bixilon.kutil.json.JsonUtil.toJsonObject
import de.bixilon.kutil.json.JsonUtil.toJsonList
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.models.block.BlockModel
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement
import de.bixilon.minosoft.gui.rendering.models.block.state.apply.SingleBlockStateApply
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import de.bixilon.minosoft.gui.rendering.models.raw.display.ModelDisplay
import de.bixilon.minosoft.gui.rendering.models.raw.light.GUILights
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import de.bixilon.minosoft.util.KUtil.toResourceLocation

class ItemModel(
    val display: Map<DisplayPositions, ModelDisplay>? = null,
    val textures: Map<String, Any>?,
    val builtinEntity: Boolean = false,
    val overrides: List<ItemModelOverride> = emptyList(),
    val elements: List<ModelElement>? = null,
    val guiLight: GUILights = GUILights.SIDE,
    val ambientOcclusion: Boolean = true,
) {

    fun load(textures: TextureManager, useParticleFallback: Boolean = false): ItemModelPrototype? {
        if (elements != null) {
            val block = BlockModel(guiLight, display, elements, this.textures, ambientOcclusion)
            val apply = SingleBlockStateApply(block)
            apply.load(textures)
            return ItemModelPrototype(apply)
        }
        if (this.textures == null) return null
        val particle = this.textures["particle"]?.let { textures.static.create(it.toResourceLocation().texture()) }

        val layers: MutableList<IndexedValue<Texture>> = mutableListOf()
        for ((key, texture) in this.textures) {
            if (!key.startsWith("layer")) continue
            if (layers.size >= MAX_LAYERS) break
            val index = key.removePrefix("layer").toIntOrNull() ?: continue
            layers += IndexedValue(index, textures.static.create(texture.toResourceLocation().texture()))
        }
        if (layers.isEmpty()) {
            if (!useParticleFallback || particle == null) return null
            return ItemModelPrototype(arrayOf(particle), particle, display)
        }

        layers.sortBy { it.index }
        val array = layers.map { it.value }.toTypedArray()

        return ItemModelPrototype(array, particle, display)
    }

    companion object {

        fun deserialize(parent: ItemModel?, data: JsonObject): ItemModel {
            val blockParent = parent?.let {
                BlockModel(it.guiLight, it.display, it.elements, it.textures, it.ambientOcclusion)
            }
            val block = BlockModel.deserialize(blockParent, data)

            val overrides = data["overrides"]?.toJsonList()?.take(MAX_OVERRIDES)?.map { entry ->
                val override = entry.toJsonObject()
                    ?: throw IllegalArgumentException("Item model override must be an object.")
                val model = override["model"]?.toString()?.toResourceLocation()
                    ?: throw IllegalArgumentException("Item model override is missing its model.")
                val predicates = override["predicate"]?.toJsonObject()?.entries?.take(MAX_PREDICATES)?.associate { (key, value) ->
                    val number = value as? Number
                        ?: throw IllegalArgumentException("Item model predicate $key must be numeric.")
                    ResourceLocation.of(key) to number.toFloat()
                } ?: emptyMap()
                ItemModelOverride(ItemPredicate(predicates), model)
            } ?: parent?.overrides ?: emptyList()

            return ItemModel(
                display = block.display,
                textures = block.textures,
                builtinEntity = parent?.builtinEntity == true,
                overrides = overrides,
                elements = block.elements,
                guiLight = block.guiLight,
                ambientOcclusion = block.ambientOcclusion,
            )
        }

        private const val MAX_LAYERS = 256
        const val MAX_OVERRIDES = 4096
        private const val MAX_PREDICATES = 64
    }

}

data class ItemModelOverride(
    val predicate: ItemPredicate,
    val model: ResourceLocation,
)
