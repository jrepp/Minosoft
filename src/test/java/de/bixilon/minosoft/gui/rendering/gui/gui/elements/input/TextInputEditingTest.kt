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

package de.bixilon.minosoft.gui.rendering.gui.gui.elements.input

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TextInputEditingTest {

    @Test
    fun `initial value respects maximum length`() {
        assertEquals("mino", TextInputEditing.initialValue("minosoft", 4))
        assertEquals("", TextInputEditing.initialValue("minosoft", 0))
    }

    @Test
    fun `insert keeps input single-line and neutralizes formatting marker`() {
        assertEquals(
            TextInputEditing.Edit("a&bc", 4),
            TextInputEditing.insert("a", 1, null, null, "§b\nc\r", 16),
        )
    }

    @Test
    fun `insert replaces selection and places pointer after replacement`() {
        assertEquals(
            TextInputEditing.Edit("hello Minosoft", 14),
            TextInputEditing.insert("hello world", 11, 6, 11, "Minosoft", 32),
        )
    }

    @Test
    fun `insert is truncated to remaining capacity`() {
        assertEquals(
            TextInputEditing.Edit("abcde", 5),
            TextInputEditing.insert("abc", 3, null, null, "def", 5),
        )
    }

    @Test
    fun `invalid limits and edit positions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextInputEditing.initialValue("text", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextInputEditing.insert("text", 5, null, null, "x", 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextInputEditing.insert("text", 2, 1, null, "x", 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextInputEditing.insert("text", 2, 1, 5, "x", 8)
        }
    }
}
