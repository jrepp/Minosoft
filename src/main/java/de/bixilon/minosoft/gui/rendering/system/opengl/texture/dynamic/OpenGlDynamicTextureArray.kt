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

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.ShaderUniforms
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureArray
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureState
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.error.MemoryLeakException
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
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
    private var handle = -1
    private var publishedCapacity = 0

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
            reload(newResolution)
            return
        }
        if (index >= publishedCapacity) reload()

        if (system.boundTexture != handle) {
            bind(handle)
        }

        unsafeUpload(index, texture, resolution)
        texture.state = DynamicTextureState.LOADED
    }

    private fun unsafeUpload(index: Int, texture: DynamicTexture, resolution: Int) {
        val data = texture.data ?: throw IllegalArgumentException("No texture data?")
        for ((level, buffer) in data.collect().withIndex()) {
            val targetSize = Vec2i((resolution shr level).coerceAtLeast(1))
            val upload = buffer.scaleTo(targetSize)
            upload.data.position(0)
            upload.data.limit(upload.data.capacity())
            gl {
                glTexSubImage3D(
                    GL_TEXTURE_2D_ARRAY,
                    level,
                    0,
                    0,
                    index,
                    targetSize.x,
                    targetSize.y,
                    1,
                    upload.glFormat,
                    upload.glType,
                    upload.data,
                )
            }
        }
    }

    /**
     * Every layer in a texture array has the same physical dimensions, while
     * dynamic sources such as player skins and capes do not. Normalized model
     * UVs address the complete source image, so uploading a smaller source
     * only into the lower-left corner makes most samples read cleared black
     * pixels after any larger dynamic texture grows the array.
     */
    private fun TextureBuffer.scaleTo(targetSize: Vec2i): TextureBuffer {
        if (size == targetSize) return this
        val source = this
        return create(targetSize).also { target ->
            for (y in 0 until targetSize.y) {
                val sourceY = y * source.size.y / targetSize.y
                for (x in 0 until targetSize.x) {
                    val sourceX = x * source.size.x / targetSize.x
                    target.setRGBA(x, y, source.getRGBA(sourceX, sourceY))
                }
            }
        }
    }

    override fun upload() {
        if (handle >= 0) throw MemoryLeakException("Texture was not unloaded!")
        publish(prepare(resolution))
    }

    override fun reload() {
        check(handle >= 0) { "Dynamic texture array is not uploaded." }
        publish(prepare(resolution))
    }

    private fun reload(candidateResolution: Int) {
        check(handle >= 0) { "Dynamic texture array is not uploaded." }
        publish(prepare(candidateResolution))
    }

    private fun prepare(candidateResolution: Int): Prepared {
        check(Thread.currentThread() === context.thread) {
            "Dynamic texture generations must be prepared on the render thread."
        }
        system.log { "Preparing ${candidateResolution}x$candidateResolution dynamic textures" }
        val snapshot = textureSnapshot()
        val candidate = OpenGlTextureUtil.createTextureArray(system, index, mipmaps)
        try {
            for (level in 0..mipmaps) {
                gl {
                    glTexImage3D(
                        GL_TEXTURE_2D_ARRAY,
                        level,
                        GL_RGBA,
                        candidateResolution shr level,
                        candidateResolution shr level,
                        snapshot.size,
                        0,
                        GL_RGBA,
                        GL_UNSIGNED_BYTE,
                        null as ByteBuffer?,
                    )
                }
            }

            val loaded = mutableListOf<DynamicTexture>()
            for ((layer, textureReference) in snapshot.withIndex()) {
                val texture = textureReference?.get() ?: continue
                if (texture.data == null) continue
                unsafeUpload(layer, texture, candidateResolution)
                loaded += texture
            }
            return Prepared(candidate, candidateResolution, snapshot.size, loaded)
        } catch (error: Throwable) {
            try {
                delete(candidate)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            restoreActiveHandle(error)
            throw error
        }
    }

    private fun publish(candidate: Prepared) {
        val previous = handle
        try {
            for (shader in shaders) {
                use(shader, candidate.handle)
            }
        } catch (error: Throwable) {
            try {
                delete(candidate.handle)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            restoreActiveHandle(error)
            throw error
        }
        handle = candidate.handle
        resolution = candidate.resolution
        publishedCapacity = candidate.capacity
        storageGeneration++
        for (texture in candidate.loaded) {
            texture.state = DynamicTextureState.LOADED
        }
        if (previous < 0) return
        try {
            delete(previous)
        } catch (error: Throwable) {
            Log.log(LogMessageType.RENDERING, LogLevels.WARN) {
                "Could not retire replaced dynamic texture handle $previous: $error"
            }
        }
    }

    override fun unsafeUse(shader: TextureShader, name: String) {
        use(shader, handle, name)
    }

    private fun use(shader: TextureShader, handle: Int, name: String = ShaderUniforms.TEXTURES) {
        if (handle <= 0) throw IllegalStateException("Texture array is not uploaded yet! Are you trying to load a shader in the init phase?")
        system.log { "Binding dynamic textures to $shader" }
        shader.use()
        val native = shader.uniformTarget().unsafeCast<OpenGlNativeShader>()

        val uniform = "$name[$index]"
        if (native.hasUniform(uniform)) native.setTexture(uniform, index)
    }

    override fun unload() {
        if (handle < 0) return
        val previous = handle
        try {
            delete(previous)
        } finally {
            if (handle == previous) handle = -1
            publishedCapacity = 0
        }
    }

    private fun restoreActiveHandle(error: Throwable) {
        if (handle < 0) return
        try {
            bind(handle)
        } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
        }
    }

    private fun bind(handle: Int) {
        gl { glActiveTexture(GL_TEXTURE0 + index) }
        gl { glBindTexture(GL_TEXTURE_2D_ARRAY, handle) }
        system.boundTexture = handle
    }

    private fun delete(handle: Int) {
        gl { glDeleteTextures(handle) }
        system.resources.deleted(OpenGlResourceType.TEXTURE, handle)
        if (system.boundTexture == handle) system.boundTexture = -1
    }

    private fun createShaderIdentifier(array: Int = this.index, index: Int): Int {
        check(array >= 0 && index >= 0) { "Array not initialized or index < 0" }
        return (array shl 28) or (index shl 12) or 0
    }

    override fun createTexture(identifier: Any, index: Int): DynamicTexture {
        return OpenGlDynamicTexture(identifier, createShaderIdentifier(index = index))
    }

    private data class Prepared(
        val handle: Int,
        val resolution: Int,
        val capacity: Int,
        val loaded: List<DynamicTexture>,
    )

    private companion object {
        const val MAX_DYNAMIC_TEXTURE_RESOLUTION = 4_096
    }
}
