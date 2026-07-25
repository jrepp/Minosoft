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

package de.bixilon.minosoft.gui.rendering.gui.elements.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollViewportStateTest {
    @Test
    fun `scroll clamps to content bounds`() {
        val state = ScrollViewportState()
        state.update(contentHeight = 300.0f, viewportHeight = 100.0f)

        assertEquals(200.0f, state.scrollBy(500.0f))
        assertEquals(0.0f, state.scrollBy(-500.0f))
    }

    @Test
    fun `content shrink clamps an existing offset`() {
        val state = ScrollViewportState()
        state.update(300.0f, 100.0f)
        state.scrollTo(180.0f)

        assertEquals(20.0f, state.update(120.0f, 100.0f))
        assertEquals(20.0f, state.maximumOffset)
    }

    @Test
    fun `visibility includes partial rows and excludes touching edges`() {
        val state = ScrollViewportState()
        state.update(300.0f, 100.0f)
        state.scrollTo(50.0f)

        assertTrue(state.visible(40.0f, 60.0f, 100.0f))
        assertTrue(state.visible(140.0f, 160.0f, 100.0f))
        assertFalse(state.visible(0.0f, 50.0f, 100.0f))
        assertFalse(state.visible(150.0f, 180.0f, 100.0f))
    }

    @Test
    fun `non finite scroll is ignored`() {
        val state = ScrollViewportState()
        state.update(300.0f, 100.0f)
        state.scrollTo(50.0f)

        assertEquals(50.0f, state.scrollBy(Float.NaN))
    }
}
