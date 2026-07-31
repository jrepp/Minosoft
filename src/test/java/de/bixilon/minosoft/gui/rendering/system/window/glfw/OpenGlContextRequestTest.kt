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

package de.bixilon.minosoft.gui.rendering.system.window.glfw

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenGlContextRequestTest {
    @Test
    fun `desktop negotiation requests the full Iris substrate before the baseline`() {
        assertEquals(
            listOf(
                OpenGlContextRequest(4, 3, coreProfile = true),
                OpenGlContextRequest(3, 3, coreProfile = true),
            ),
            OpenGlContextRequest.candidates(isMac = false, preferQuads = false),
        )
    }

    @Test
    fun `Apple negotiation requests its maximum context before the baseline`() {
        assertEquals(
            listOf(
                OpenGlContextRequest(4, 1, coreProfile = true),
                OpenGlContextRequest(3, 3, coreProfile = true),
            ),
            OpenGlContextRequest.candidates(isMac = true, preferQuads = false),
        )
    }

    @Test
    fun `legacy quad preference retains a compatibility context`() {
        assertEquals(
            listOf(OpenGlContextRequest(3, 0, coreProfile = false)),
            OpenGlContextRequest.candidates(isMac = false, preferQuads = true),
        )
    }
}
