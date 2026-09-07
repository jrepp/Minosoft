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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SceneReviewCaptureTest {
    @Test
    fun `texture resources map to content-forge targets`() {
        assertEquals(
            "minecraft:block/spruce_log",
            SceneReviewCapture.assetTarget(ResourceLocation("minecraft", "textures/block/spruce_log.png")),
        )
        assertEquals(
            "example:item/hammer",
            SceneReviewCapture.assetTarget(ResourceLocation("example", "textures/item/hammer.png")),
        )
    }

    @Test
    fun `non texture resources do not claim content-forge targets`() {
        assertNull(SceneReviewCapture.assetTarget(ResourceLocation("minecraft", "models/block/stone.json")))
        assertNull(SceneReviewCapture.assetTarget(ResourceLocation("minecraft", "textures/block/stone.jpg")))
    }
}
