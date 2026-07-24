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

package de.bixilon.minosoft.gui.rendering.gui.gui.dragged.elements.item

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RightDragDistributionTest {

    @Test
    fun `each crossed slot is accepted once`() {
        val distribution = RightDragDistribution()

        distribution.start(4)
        assertFalse(distribution.add(4))
        assertTrue(distribution.add(5))
        assertFalse(distribution.add(5))
        assertTrue(distribution.add(6))
        assertEquals(listOf(4, 5, 6), distribution.finish())
        assertFalse(distribution.active)
    }

    @Test
    fun `inactive gesture does not accept slots`() {
        val distribution = RightDragDistribution()

        assertFalse(distribution.add(4))
        assertEquals(emptyList<Int>(), distribution.finish())
    }

    @Test
    fun `new gesture forgets the previous path`() {
        val distribution = RightDragDistribution()

        distribution.start(4)
        distribution.add(5)
        distribution.cancel()
        distribution.start(5)

        assertTrue(distribution.add(4))
        assertEquals(listOf(5, 4), distribution.finish())
    }
}
