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

package de.bixilon.minosoft.gui.rendering.system.opengl.buffer.uniform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FloatOpenGlUniformBufferTest {

    @Test
    fun `uniform binding range converts float elements to bytes`() {
        assertEquals(4096, FloatOpenGlUniformBuffer.bindingSizeBytes(64 * 16))
    }

    @Test
    fun `partial upload validates its inclusive float range before native access`() {
        assertEquals(16, FloatOpenGlUniformBuffer.uploadByteCount(start = 2, end = 5, limit = 8))
        assertThrows(IndexOutOfBoundsException::class.java) {
            FloatOpenGlUniformBuffer.uploadByteCount(start = 5, end = 4, limit = 8)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            FloatOpenGlUniformBuffer.uploadByteCount(start = -1, end = 4, limit = 8)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            FloatOpenGlUniformBuffer.uploadByteCount(start = 2, end = 8, limit = 8)
        }
    }
}
