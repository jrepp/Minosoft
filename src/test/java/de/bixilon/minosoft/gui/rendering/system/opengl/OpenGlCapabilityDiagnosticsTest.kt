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

package de.bixilon.minosoft.gui.rendering.system.opengl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenGlCapabilityDiagnosticsTest {
    @Test
    fun `OpenGL 4_4 exposes every implemented Iris capability`() {
        val capabilities = diagnostics(
            openGl40 = true,
            openGl42 = true,
            openGl43 = true,
            openGl44 = true,
        )

        assertTrue(capabilities.irisTessellation)
        assertTrue(capabilities.irisPerBufferBlending)
        assertTrue(capabilities.irisRenderTargetImages)
        assertTrue(capabilities.irisCustomImages)
        assertTrue(capabilities.irisCompute)
        assertTrue(capabilities.irisShaderStorageBuffers)
    }

    @Test
    fun `clear texture extension completes custom images on OpenGL 4_3`() {
        val capabilities = diagnostics(
            openGl40 = true,
            openGl42 = true,
            openGl43 = true,
            openGl44 = false,
            arbClearTexture = true,
        )

        assertTrue(capabilities.irisCustomImages)
        assertTrue(capabilities.irisCompute)
        assertTrue(capabilities.irisShaderStorageBuffers)
    }

    @Test
    fun `Apple OpenGL 4_1 exposes tessellation but not image compute or storage`() {
        val capabilities = diagnostics(
            openGl40 = true,
            openGl42 = false,
            openGl43 = false,
            openGl44 = false,
        )

        assertTrue(capabilities.irisTessellation)
        assertTrue(capabilities.irisPerBufferBlending)
        assertFalse(capabilities.irisRenderTargetImages)
        assertFalse(capabilities.irisCustomImages)
        assertFalse(capabilities.irisCompute)
        assertFalse(capabilities.irisShaderStorageBuffers)
    }

    @Test
    fun `draw buffers blend extension exposes per-buffer blending without OpenGL 4_0`() {
        val capabilities = diagnostics(
            openGl40 = false,
            openGl42 = false,
            openGl43 = false,
            openGl44 = false,
            arbDrawBuffersBlend = true,
        )

        assertTrue(capabilities.irisPerBufferBlending)
        assertFalse(capabilities.irisTessellation)
    }

    private fun diagnostics(
        openGl40: Boolean,
        openGl42: Boolean,
        openGl43: Boolean,
        openGl44: Boolean,
        arbClearTexture: Boolean = false,
        arbDrawBuffersBlend: Boolean = false,
    ) = OpenGlCapabilityDiagnostics(
        version = "test",
        vendor = "test",
        renderer = "test",
        openGl40 = openGl40,
        openGl42 = openGl42,
        openGl43 = openGl43,
        openGl44 = openGl44,
        arbClearTexture = arbClearTexture,
        arbDrawBuffersBlend = arbDrawBuffersBlend,
    )
}
