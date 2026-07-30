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

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorageBlockEntityCanaryTypeTest {
    @Test
    fun `request names resolve only the bounded storage producer catalog`() {
        assertEquals(
            mapOf(
                "chest" to ResourceLocation.of("minecraft:chest"),
                "trapped_chest" to ResourceLocation.of("minecraft:trapped_chest"),
                "ender_chest" to ResourceLocation.of("minecraft:ender_chest"),
                "shulker_box" to ResourceLocation.of("minecraft:shulker_box"),
            ),
            StorageBlockEntityCanaryType.entries.associate { it.requestName to it.block },
        )
        assertNull(StorageBlockEntityCanaryType.fromRequest("barrel"))
        assertNull(StorageBlockEntityCanaryType.fromRequest("../chest"))
    }
}
