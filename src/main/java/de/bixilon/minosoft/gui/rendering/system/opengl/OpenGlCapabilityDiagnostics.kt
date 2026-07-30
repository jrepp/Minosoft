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

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.GL11.GL_RENDERER
import org.lwjgl.opengl.GL11.GL_VENDOR
import org.lwjgl.opengl.GL11.GL_VERSION
import org.lwjgl.opengl.GL11.glGetString

data class OpenGlCapabilityDiagnostics(
    val version: String,
    val vendor: String,
    val renderer: String,
    val openGl40: Boolean,
    val openGl42: Boolean,
    val openGl43: Boolean,
    val openGl44: Boolean,
    val arbClearTexture: Boolean,
    val arbDrawBuffersBlend: Boolean,
) {
    val irisTessellation: Boolean get() = openGl40
    val irisPerBufferBlending: Boolean get() = openGl40 || arbDrawBuffersBlend
    val irisRenderTargetImages: Boolean get() = openGl42
    val irisCustomImages: Boolean get() = openGl42 && (openGl44 || arbClearTexture)
    val irisCompute: Boolean get() = openGl43
    val irisShaderStorageBuffers: Boolean get() = openGl43

    companion object {
        /**
         * Captures the exact Iris-relevant feature boundary from the current
         * OpenGL context. Call only on the owning render thread.
         */
        fun capture(): OpenGlCapabilityDiagnostics {
            val capabilities = GL.getCapabilities()
            return OpenGlCapabilityDiagnostics(
                version = glGetString(GL_VERSION) ?: "UNKNOWN",
                vendor = glGetString(GL_VENDOR) ?: "UNKNOWN",
                renderer = glGetString(GL_RENDERER) ?: "UNKNOWN",
                openGl40 = capabilities.OpenGL40,
                openGl42 = capabilities.OpenGL42,
                openGl43 = capabilities.OpenGL43,
                openGl44 = capabilities.OpenGL44,
                arbClearTexture = capabilities.GL_ARB_clear_texture,
                arbDrawBuffersBlend = capabilities.GL_ARB_draw_buffers_blend,
            )
        }
    }
}

internal val GLCapabilities.irisPerBufferBlending: Boolean
    get() = OpenGL40 || GL_ARB_draw_buffers_blend
