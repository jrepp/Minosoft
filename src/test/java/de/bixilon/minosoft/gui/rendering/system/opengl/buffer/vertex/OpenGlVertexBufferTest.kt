/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.system.opengl.buffer.vertex

import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import org.junit.jupiter.api.assertThrows
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL40.GL_PATCHES
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenGlVertexBufferTest {
    @Test
    fun `tessellation draw mode requires complete matching patches`() {
        assertEquals(
            GL_TRIANGLES,
            OpenGlVertexBuffer.drawMode(PrimitiveTypes.TRIANGLE, 6, null),
        )
        assertEquals(
            GL_PATCHES,
            OpenGlVertexBuffer.drawMode(PrimitiveTypes.TRIANGLE, 6, 3),
        )
        assertThrows<IllegalArgumentException> {
            OpenGlVertexBuffer.drawMode(PrimitiveTypes.TRIANGLE, 5, 3)
        }
        assertThrows<IllegalArgumentException> {
            OpenGlVertexBuffer.drawMode(PrimitiveTypes.LINE, 6, 3)
        }
    }
}
