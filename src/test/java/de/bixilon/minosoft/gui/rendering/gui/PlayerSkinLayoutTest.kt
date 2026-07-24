/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.gui

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.entities.entities.player.properties.textures.metadata.SkinModel
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory.PlayerSkinLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSkinLayoutTest {
    @Test
    fun `wide skin preview fills inventory area without escaping it`() {
        val area = Vec2f(49.0f, 70.0f)
        val quads = PlayerSkinLayout.build(area, SkinModel.WIDE)

        assertEquals(12, quads.size)
        assertTrue(quads.all { it.start.x >= 0.0f && it.start.y >= 0.0f })
        assertTrue(quads.all { it.end.x <= area.x && it.end.y <= area.y })
        assertTrue(quads.all { it.uvStart.x >= 0.0f && it.uvStart.y >= 0.0f })
        assertTrue(quads.all { it.uvEnd.x <= 1.0f && it.uvEnd.y <= 1.0f })
    }

    @Test
    fun `slim arms use three source pixels while preserving twelve layers`() {
        val quads = PlayerSkinLayout.build(Vec2f(49.0f, 70.0f), SkinModel.SLIM)

        assertEquals(12, quads.size)
        assertEquals(3.0f / 64.0f, quads[2].uvEnd.x - quads[2].uvStart.x)
        assertEquals(3.0f / 64.0f, quads[8].uvEnd.x - quads[8].uvStart.x)
    }
}
