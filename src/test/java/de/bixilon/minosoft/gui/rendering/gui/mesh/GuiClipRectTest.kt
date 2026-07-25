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

package de.bixilon.minosoft.gui.rendering.gui.mesh

import de.bixilon.kmath.vec.vec2.f.Vec2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuiClipRectTest {
    @Test
    fun `axis aligned quad clips positions and interpolates uv`() {
        val clip = GuiClipRect(Vec2f(0.0f, 0.0f), Vec2f(10.0f, 10.0f))
        val result = clip.clip(
            listOf(
                GuiClipVertex(-10.0f, 0.0f, 0.0f, 0.0f),
                GuiClipVertex(10.0f, 0.0f, 1.0f, 0.0f),
                GuiClipVertex(10.0f, 10.0f, 1.0f, 1.0f),
                GuiClipVertex(-10.0f, 10.0f, 0.0f, 1.0f),
            ),
        )

        assertEquals(4, result.size)
        assertTrue(result.all { it.x in 0.0f..10.0f && it.y in 0.0f..10.0f })
        assertTrue(result.filter { it.x == 0.0f }.all { it.u == 0.5f })
    }

    @Test
    fun `fully outside polygon is removed`() {
        val clip = GuiClipRect(Vec2f(0.0f, 0.0f), Vec2f(10.0f, 10.0f))
        val result = clip.clip(
            listOf(
                GuiClipVertex(-4.0f, -4.0f, 0.0f, 0.0f),
                GuiClipVertex(-2.0f, -4.0f, 1.0f, 0.0f),
                GuiClipVertex(-2.0f, -2.0f, 1.0f, 1.0f),
            ),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `non finite polygon is removed`() {
        val clip = GuiClipRect(Vec2f(0.0f, 0.0f), Vec2f(10.0f, 10.0f))

        assertTrue(
            clip.clip(
                listOf(
                    GuiClipVertex(Float.NaN, 0.0f, 0.0f, 0.0f),
                    GuiClipVertex(1.0f, 0.0f, 1.0f, 0.0f),
                    GuiClipVertex(1.0f, 1.0f, 1.0f, 1.0f),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `invalid clip rectangles are rejected`() {
        assertFailsWith<IllegalArgumentException> { GuiClipRect(Vec2f(2.0f, 0.0f), Vec2f(1.0f, 1.0f)) }
        assertFailsWith<IllegalArgumentException> { GuiClipRect(Vec2f(Float.NaN, 0.0f), Vec2f(1.0f, 1.0f)) }
    }
}
