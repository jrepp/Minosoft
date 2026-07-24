/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.models.loader

import de.bixilon.kutil.cast.CastUtil.nullCast
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.assets.minecraft.MinecraftPackFormat
import de.bixilon.minosoft.assets.util.InputStreamUtil.readJsonObject
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.registries.item.items.block.BlockItem
import de.bixilon.minosoft.data.registries.item.items.block.legacy.PixLyzerBlockItem
import de.bixilon.minosoft.gui.rendering.models.item.ItemModel
import de.bixilon.minosoft.gui.rendering.models.item.ItemModelPrototype
import de.bixilon.minosoft.gui.rendering.models.item.ItemModelOverridePrototype
import de.bixilon.minosoft.gui.rendering.models.loader.ModelFixer.fixPrefix
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader.Companion.model
import de.bixilon.minosoft.gui.rendering.models.loader.legacy.CustomModel
import de.bixilon.minosoft.util.KUtil.toResourceLocation
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.Collections
import java.util.IdentityHashMap

class ItemLoader(private val loader: ModelLoader) {
    private val cache: MutableMap<ResourceLocation, ItemModel> = HashMap(loader.context.session.registries.item.size)
    private val loadingModels: MutableSet<ResourceLocation> = HashSet()
    private val entityItems: MutableSet<ResourceLocation> = mutableSetOf()
    private val entityFallbacks: MutableMap<ResourceLocation, ItemModelPrototype> = mutableMapOf()
    private var skippedOverrideCycles = 0
    private var skippedOverrideDepths = 0
    val assets = loader.context.session.assets
    val version = loader.context.session.version

    fun loadItem(name: ResourceLocation): ItemModel? = loadItem(name, 0)

    private fun loadItem(name: ResourceLocation, depth: Int): ItemModel? {
        if (name == BUILTIN_ENTITY) {
            return ItemModel(textures = null, builtinEntity = true)
        }
        if (depth > MAX_MODEL_DEPTH) {
            Log.log(LogMessageType.LOADING, LogLevels.WARN) { "Item model parent graph exceeds $MAX_MODEL_DEPTH levels at $name" }
            return null
        }
        val file = name.model()
        cache[file]?.let { return it }
        if (!loadingModels.add(file)) {
            Log.log(LogMessageType.LOADING, LogLevels.WARN) { "Ignoring cyclic item model parent at $name" }
            return null
        }
        try {
            val data = assets.getOrNull(file)?.readJsonObject()
            if (data == null) {
                Log.log(LogMessageType.LOADING, LogLevels.WARN) { "Can not find item model $name" }
                return null
            }

            val parent = data["parent"]?.toString()?.let { loadItem(it.toResourceLocation(), depth + 1) }

            val model = ItemModel.deserialize(parent, data)
            cache[file] = model
            return model
        } finally {
            loadingModels.remove(file)
        }
    }

    private fun loadItem(item: Item): ItemModel? {
        val file = (if (item is CustomModel) item.getModelName(version) else item.identifier.itemModel()) ?: return null

        return loadItem(file)
    }

    fun load(latch: AbstractLatch?) {
        for (item in loader.context.session.registries.item) {
            val isBlockItem = item is BlockItem<*> || item is PixLyzerBlockItem
            if (!isBlockItem && item.model != null) continue // already has a model set
            val model = loadItem(item) ?: continue

            if (model.builtinEntity) {
                entityItems += item.identifier
            }
            val prototype = loadPrototype(model, useParticleFallback = model.builtinEntity) ?: continue
            item.model = prototype
            if (model.builtinEntity) {
                entityFallbacks[item.identifier] = prototype
            }
        }
        if (skippedOverrideCycles > 0 || skippedOverrideDepths > 0) {
            Log.log(LogMessageType.LOADING, LogLevels.WARN) {
                "Ignored unsafe item model overrides: cycles=$skippedOverrideCycles depth=$skippedOverrideDepths"
            }
        }
    }

    private fun loadPrototype(
        model: ItemModel,
        useParticleFallback: Boolean,
        loading: MutableSet<ItemModel> = Collections.newSetFromMap(IdentityHashMap()),
        depth: Int = 0,
    ): ItemModelPrototype? {
        if (depth > MAX_MODEL_DEPTH) {
            skippedOverrideDepths++
            return null
        }
        if (!loading.add(model)) {
            skippedOverrideCycles++
            return null
        }
        try {
            val prototype = model.load(loader.context.textures, useParticleFallback) ?: return null
            val overrides = model.overrides.mapNotNull { override ->
                val target = loadItem(override.model) ?: return@mapNotNull null
                val targetPrototype = loadPrototype(target, false, loading, depth + 1) ?: return@mapNotNull null
                ItemModelOverridePrototype(override.predicate, targetPrototype)
            }
            return prototype.withOverrides(overrides)
        } finally {
            loading.remove(model)
        }
    }

    fun auditMissingBlockItemModels() {
        val fallback = entityFallbacks
            .filter { (identifier, prototype) -> loader.context.session.registries.item[identifier]?.model === prototype }
            .keys
        val precise = entityItems
            .filter { identifier ->
                val current = loader.context.session.registries.item[identifier]?.model
                current != null && current !== entityFallbacks[identifier]
            }
            .sortedBy { it.toString() }
        val unresolved = entityItems
            .filter { loader.context.session.registries.item[it]?.model == null }
            .sortedBy { it.toString() }

        auditEntityItems("BLOCK_ITEM_ENTITY_RENDER_AUDIT", precise, fallback, unresolved) {
            it is BlockItem<*> || it is PixLyzerBlockItem
        }
        auditEntityItems("SPECIAL_ITEM_ENTITY_RENDER_AUDIT", precise, fallback, unresolved) {
            it !is BlockItem<*> && it !is PixLyzerBlockItem
        }

        val missing = loader.context.session.registries.item
            .filter { it is BlockItem<*> || it is PixLyzerBlockItem }
            .filter { it.model == null }
            .map { it.identifier }
            .sortedBy { it.toString() }

        if (missing.isEmpty()) return
        Log.log(LogMessageType.LOADING, LogLevels.WARN) {
            "BLOCK_ITEM_RENDER_MISSING count=${missing.size} items=${missing.joinToString(",")}"
        }
    }

    private fun auditEntityItems(
        event: String,
        precise: Collection<ResourceLocation>,
        fallback: Collection<ResourceLocation>,
        unresolved: Collection<ResourceLocation>,
        filter: (Item) -> Boolean,
    ) {
        val preciseItems = precise.filter { loader.context.session.registries.item[it]?.let(filter) == true }
        val fallbackItems = fallback.filter { loader.context.session.registries.item[it]?.let(filter) == true }.sortedBy { it.toString() }
        val unresolvedItems = unresolved.filter { loader.context.session.registries.item[it]?.let(filter) == true }
        if (preciseItems.isNotEmpty() || fallbackItems.isNotEmpty() || unresolvedItems.isNotEmpty()) {
            Log.log(LogMessageType.LOADING, LogLevels.INFO) {
                "$event precise=${preciseItems.size} fallback=${fallbackItems.size} unresolved=${unresolvedItems.size} " +
                    "preciseItems=${preciseItems.joinToString(",")} fallbackItems=${fallbackItems.joinToString(",")} " +
                    "unresolvedItems=${unresolvedItems.joinToString(",")}"
            }
        }
    }

    fun bake(latch: AbstractLatch?) {
        for (item in loader.context.session.registries.item) { // TODO: ConcurrentIterator
            val prototype = item.model.nullCast<ItemModelPrototype>() ?: continue

            item.model = prototype.bake()
        }
    }

    fun cleanup() {
        this::cache.forceSet(null)
        this::entityItems.forceSet(null)
        this::entityFallbacks.forceSet(null)
    }

    fun fixTexturePath(name: ResourceLocation): ResourceLocation {
        return ResourceLocation(name.namespace, name.path.fixPrefix(loader.packFormat, MinecraftPackFormat.FLATTENING, "items/", "item/"))
    }

    private fun ResourceLocation.itemModel(): ResourceLocation {
        return this.prefix("item/").model()
    }

    private companion object {
        const val MAX_MODEL_DEPTH = 64
        val BUILTIN_ENTITY = "minecraft:builtin/entity".toResourceLocation()
    }
}
