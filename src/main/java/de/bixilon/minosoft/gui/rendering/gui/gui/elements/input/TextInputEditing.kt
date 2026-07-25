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

object TextInputEditing {

    data class Edit(
        val value: String,
        val pointer: Int,
    )

    fun initialValue(value: String, maxLength: Int): String {
        require(maxLength >= 0) { "Maximum text length must not be negative." }
        return value.take(maxLength)
    }

    fun insert(
        value: String,
        pointer: Int,
        selectionStart: Int?,
        selectionEnd: Int?,
        inserted: String,
        maxLength: Int,
    ): Edit {
        require(maxLength >= 0) { "Maximum text length must not be negative." }
        require(pointer in 0..value.length) { "Text pointer is outside the value." }
        require((selectionStart == null) == (selectionEnd == null)) { "Selection bounds must both be present or absent." }

        var next = value
        var nextPointer = pointer
        if (selectionStart != null && selectionEnd != null) {
            require(selectionStart in 0..selectionEnd && selectionEnd <= value.length) { "Text selection is outside the value." }
            next = value.removeRange(selectionStart, selectionEnd)
            if (nextPointer > selectionStart) {
                nextPointer = selectionStart
            }
        }

        val sanitized = inserted.replace("\n", "").replace("\r", "").replace('§', '&')
        val available = (maxLength - next.length).coerceAtLeast(0)
        val accepted = sanitized.take(available)
        next = next.substring(0, nextPointer) + accepted + next.substring(nextPointer)

        return Edit(next, nextPointer + accepted.length)
    }
}
