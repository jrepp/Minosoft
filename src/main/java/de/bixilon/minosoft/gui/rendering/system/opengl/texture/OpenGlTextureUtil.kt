/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.system.opengl.texture

import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGB8Buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL
import org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY

object OpenGlTextureUtil {

    fun createTextureArray(system: OpenGlRenderSystem, index: Int, mipmaps: Int): Int {
        require(index >= 0) { "Texture array index must not be negative: $index" }
        val textureId = gl { glGenTextures() }
        system.resources.created(OpenGlResourceType.TEXTURE, textureId)

        try {
            system.bindTexture(index, GL_TEXTURE_2D_ARRAY, textureId)

            system.textureParameter { glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, if (mipmaps == 0) GL_NEAREST else GL_NEAREST_MIPMAP_NEAREST) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, mipmaps) }

            return textureId
        } catch (error: Throwable) {
            try {
                gl { glDeleteTextures(textureId) }
                system.resources.deleted(OpenGlResourceType.TEXTURE, textureId)
                system.invalidateTexture(textureId)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    val TextureBuffer.glFormat: Int
        get() = when (this) {
            is RGBA8Buffer -> GL_RGBA
            is RGB8Buffer -> GL_RGB
            // is RGBA2Buffer -> GL_RGBA
            else -> throw IllegalArgumentException("Can not get glFormat of $this")
        }
    val TextureBuffer.glType: Int
        get() = when (this) {
            is RGBA8Buffer -> GL_UNSIGNED_BYTE
            is RGB8Buffer -> GL_UNSIGNED_BYTE
            //  is RGBA2Buffer -> GL_UNSIGNED_BYTE
            else -> throw IllegalArgumentException("Can not get glFormat of $this")
        }
}
