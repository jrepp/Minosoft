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

package de.bixilon.minosoft.gui.rendering.entities.feature.flame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityFlameGeometryTest {
    @Test
    fun `vanilla layers use exact width height depth and alternating textures`() {
        val layout = EntityFlameGeometry.layout(width = 1.0f, height = 1.0f)

        assertEquals(layout.scale, 1.4f)
        assertEquals(layout.normalizedHeight, 1.0f / 1.4f)
        assertEquals(layout.zOffset, -0.3f)
        assertEquals(layout.quads.size, 2)

        val first = layout.quads[0]
        assertEquals(first.texture, 0)
        assertEquals(first.vertices[0].position.x, 0.5f)
        assertEquals(first.vertices[0].position.y, 0.0f, 0.0f)
        assertEquals(first.vertices[0].position.z, 0.0f, 0.0f)
        assertEquals(first.vertices[0].uv.x, 0.0f)
        assertEquals(first.vertices[1].uv.x, 1.0f)
        assertEquals(first.vertices[2].position.y, 1.4f)

        val second = layout.quads[1]
        assertEquals(second.texture, 1)
        assertEquals(second.vertices[0].position.x, 0.45f)
        assertEquals(second.vertices[0].position.y, 0.45f)
        assertEquals(second.vertices[0].position.z, 0.03f)
        assertEquals(second.vertices[2].position.y, 1.85f, 0.000001f)
    }

    @Test
    fun `vanilla horizontal flip changes every two layers`() {
        val layout = EntityFlameGeometry.layout(width = 1.0f, height = 3.0f)

        assertEquals(layout.quads.size, 5)
        assertEquals(layout.zOffset, -0.26f, 0.000001f)
        assertEquals(layout.quads[1].vertices[0].uv.x, 0.0f)
        assertEquals(layout.quads[2].vertices[0].uv.x, 1.0f)
        assertEquals(layout.quads[3].vertices[0].uv.x, 1.0f)
        assertEquals(layout.quads[4].vertices[0].uv.x, 0.0f)
    }

    @Test
    fun `invalid dimensions produce no geometry`() {
        assertTrue(EntityFlameGeometry.layout(0.0f, 1.0f).quads.isEmpty())
        assertTrue(EntityFlameGeometry.layout(1.0f, Float.NaN).quads.isEmpty())
    }
}
