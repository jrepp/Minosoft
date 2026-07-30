/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug

import de.bixilon.minosoft.data.entities.block.container.storage.ChestBlockEntity
import de.bixilon.minosoft.data.entities.block.container.storage.EnderChestBlockEntity
import de.bixilon.minosoft.data.entities.block.container.storage.TrappedChestBlockEntity
import de.bixilon.minosoft.data.entities.block.container.storage.ShulkerBoxBlockEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

internal enum class StorageBlockEntityCanaryType(
    val requestName: String,
    val block: ResourceLocation,
) {
    CHEST("chest", ChestBlockEntity.identifier),
    TRAPPED_CHEST("trapped_chest", TrappedChestBlockEntity.identifier),
    ENDER_CHEST("ender_chest", EnderChestBlockEntity.identifier),
    SHULKER_BOX("shulker_box", ShulkerBoxBlockEntity.identifier),
    ;

    companion object {
        fun fromRequest(value: String): StorageBlockEntityCanaryType? =
            entries.firstOrNull { it.requestName == value }
    }
}
