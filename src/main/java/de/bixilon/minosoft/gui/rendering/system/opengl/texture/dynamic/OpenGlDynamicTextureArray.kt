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

package de.bixilon.minosoft.gui.rendering.system.opengl.texture.dynamic

import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureArray
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureState
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.error.MemoryLeakException
import de.bixilon.minosoft.gui.rendering.system.opengl.shader.OpenGlNativeShader
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureSizing
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil.glFormat
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil.glType
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL12.glTexSubImage3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY
import java.nio.ByteBuffer

class OpenGlDynamicTextureArray(
    val system: OpenGlRenderSystem,
    val index: Int = system.nextTextureIndex++,
    initialSize: Int = 32,
    resolution: Int,
    mipmaps: Int,
) : DynamicTextureArray(system.context, initialSize, mipmaps) {
    var resolution = resolution.also {
        require(it in 1..MAX_DYNAMIC_TEXTURE_RESOLUTION) {
            "Dynamic texture resolution must be between 1 and $MAX_DYNAMIC_TEXTURE_RESOLUTION: $it"
        }
    }
        private set
    private var empty = IntArray(Math.multiplyExact(resolution, resolution))
    private var handle = -1

    override fun upload(index: Int, texture: DynamicTexture) {
        if (Thread.currentThread() != context.thread) {
            context.queue += { upload(index, texture) }
            return
        }

        val data = texture.data ?: throw IllegalArgumentException("No texture data?")
        val requiredResolution = maxOf(data.size.x, data.size.y)
        if (requiredResolution > resolution) {
            val newResolution = OpenGlTextureSizing.growPowerOfTwo(resolution, requiredResolution)
            val maximumSize = gl { glGetInteger(GL_MAX_TEXTURE_SIZE) }
            if (newResolution > maximumSize || newResolution > MAX_DYNAMIC_TEXTURE_RESOLUTION) {
                Log.log(LogMessageType.LOADING, LogLevels.WARN) {
                    "Dynamic texture $texture requires ${data.size}, exceeding the supported maximum ${minOf(maximumSize, MAX_DYNAMIC_TEXTURE_RESOLUTION)}"
                }
                texture.state = DynamicTextureState.ERROR
                return
            }
            Log.log(LogMessageType.LOADING) { "Growing dynamic texture array from ${resolution}x$resolution to ${newResolution}x$newResolution for $texture" }
            resolution = newResolution
            empty = IntArray(resolution * resolution)
            reload()
            return
        }

        if (system.boundTexture != handle) {
            gl { glBindTexture(GL_TEXTURE_2D_ARRAY, handle) }
            system.boundTexture = handle
        }

        unsafeUpload(index, texture)
        texture.state = DynamicTextureState.LOADED
    }

    private fun unsafeUpload(index: Int, texture: DynamicTexture) {
        val data = texture.data ?: throw IllegalArgumentException("No texture data?")
        for ((level, buffer) in data.collect().withIndex()) {
            if (data.size.x != resolution || data.size.y != resolution) {
                // clear first
                gl { glTexSubImage3D(GL_TEXTURE_2D_ARRAY, level, 0, 0, index, resolution shr level, resolution shr level, 1, GL_RGBA, GL_UNSIGNED_BYTE, empty) }
            }
            buffer.data.position(0)
            buffer.data.limit(buffer.data.capacity())
            gl { glTexSubImage3D(GL_TEXTURE_2D_ARRAY, level, 0, 0, index, buffer.size.x, buffer.size.y, 1, buffer.glFormat, buffer.glType, buffer.data) }
        }
    }

    override fun upload() {
        if (handle >= 0) throw MemoryLeakException("Texture was not unloaded!")
        system.log { "Uploading dynamic textures" }
        val handle = OpenGlTextureUtil.createTextureArray(index, mipmaps)

        for (level in 0..mipmaps) {
            gl { glTexImage3D(GL_TEXTURE_2D_ARRAY, level, GL_RGBA, resolution shr level, resolution shr level, textures.size, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?) }
        }

        for ((index, textureReference) in textures.withIndex()) {
            val texture = textureReference?.get() ?: continue
            if (texture.data == null) continue
            unsafeUpload(index, texture)
            texture.state = DynamicTextureState.LOADED
        }
        this.handle = handle

        for (shader in shaders) {
            unsafeUse(shader)
        }
    }

    override fun unsafeUse(shader: TextureShader, name: String) {
        if (handle <= 0) throw IllegalStateException("Texture array is not uploaded yet! Are you trying to load a shader in the init phase?")
        system.log { "Binding dynamic textures to $shader" }
        val native = shader.native.unsafeCast<OpenGlNativeShader>()
        shader.use()

        native.setTexture("$name[$index]", index)
    }

    override fun unload() {
        if (handle < 0) throw IllegalStateException("Not loaded!")
        gl { glActiveTexture(GL_TEXTURE0 + index) }
        gl { glDeleteTextures(handle) }
        this.handle = -1
    }

    private fun createShaderIdentifier(array: Int = this.index, index: Int): Int {
        check(array >= 0 && index >= 0) { "Array not initialized or index < 0" }
        return (array shl 28) or (index shl 12) or 0
    }

    override fun createTexture(identifier: Any, index: Int): DynamicTexture {
        return OpenGlDynamicTexture(identifier, createShaderIdentifier(index = index))
    }

    private companion object {
        const val MAX_DYNAMIC_TEXTURE_RESOLUTION = 4_096
    }
}
