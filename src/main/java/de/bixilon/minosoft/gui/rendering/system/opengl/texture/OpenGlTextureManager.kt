/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager
import de.bixilon.minosoft.gui.rendering.system.base.shader.ShaderUniforms
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.shader.OpenGlNativeShader
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.dynamic.OpenGlDynamicTextureArray

class OpenGlTextureManager(system: OpenGlRenderSystem) : TextureManager() {
    private val config = system.context.session.profiles.rendering.textures
    override val static = OpenGlTextureArray(system, true, config.mipmaps)
    override val dynamic = OpenGlDynamicTextureArray(system, resolution = 64, mipmaps = config.mipmaps)
    override val font = OpenGlFontTextureArray(system, config.fontCompression)

    override fun use(shader: TextureShader, name: String) {
        val native = shader.native.unsafeCast<OpenGlNativeShader>()
        shader.use()

        // The shader's switch-based texture lookup keeps every sampler-array
        // element active on Apple's OpenGL implementation. Sampler uniforms
        // default to texture unit 0, which is reserved for the 2D framebuffer
        // and has no 2D-array binding. Point every otherwise-unused slot at the
        // always-allocated dynamic array before the real arrays override their
        // assigned indices.
        for (index in 0 until SHADER_TEXTURE_ARRAY_SIZE) {
            native.setTexture("$name[$index]", dynamic.index)
        }

        super.use(shader, name)
    }

    private companion object {
        const val SHADER_TEXTURE_ARRAY_SIZE = 16
    }
}
