/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.tint.sampler

import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import kotlin.test.Test
import kotlin.test.assertEquals

class TerrainTintCacheTest {
    @Test
    fun `terrain tint is bilinear across four cached block samples`() {
        val colors = mapOf(
            0 to RGBColor(255, 0, 0),
            1 to RGBColor(0, 255, 0),
            2 to RGBColor(0, 0, 255),
            3 to RGBColor(255, 255, 255),
        )

        val center = TerrainTintCache.bilinear(0.5, 0.5) { x, z -> colors.getValue(x + z * 2) }

        assertEquals(RGBColor(127, 127, 127), center)
    }
}
