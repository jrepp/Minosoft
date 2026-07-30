/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Immutable material-ID mappings parsed from Iris/OptiFine property files.
 *
 * Iris uses `0` when an entire entity/item property file is absent and `-1`
 * for an unmapped identifier when that file exists.
 */
data class IrisIdTable(
    val values: Map<ResourceLocation, Int> = emptyMap(),
    val missing: Int = 0,
) {
    operator fun get(identifier: ResourceLocation?): Int =
        if (identifier == null) 0 else values[identifier] ?: missing
}

data class IrisBlockIdRule(
    val id: Int,
    val identifier: ResourceLocation,
    val tag: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(id in Short.MIN_VALUE..Short.MAX_VALUE) {
            "Iris block material ID must fit the pinned signed-short ABI: $id"
        }
    }

    fun matches(
        state: BlockState,
        tagMatcher: (ResourceLocation, BlockState) -> Boolean,
    ): Boolean {
        if (tag) {
            if (!tagMatcher(identifier, state)) return false
        } else if (state.block.identifier != identifier) {
            return false
        }
        if (properties.isEmpty()) return true
        val actual = state.properties.entries.associate { (property, value) ->
            property.name to value.toString().lowercase()
        }
        return properties.all { (name, value) -> actual[name] == value }
    }
}

data class IrisIdMaps(
    val items: IrisIdTable = IrisIdTable(),
    val entities: IrisIdTable = IrisIdTable(),
    val blocks: List<IrisBlockIdRule> = emptyList(),
) {
    fun block(
        state: BlockState?,
        missing: Int,
        tagMatcher: (ResourceLocation, BlockState) -> Boolean,
    ): Int {
        if (state == null) return 0
        return blocks.firstOrNull { it.matches(state, tagMatcher) }?.id ?: missing
    }

    companion object {
        val EMPTY = IrisIdMaps()
    }
}
