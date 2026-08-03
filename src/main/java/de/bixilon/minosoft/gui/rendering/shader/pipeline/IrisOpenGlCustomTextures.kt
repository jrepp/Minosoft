/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil.glFormat
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil.glType
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.readTexture
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_REPEAT
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_TEXTURE_1D
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH
import org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS
import org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glPixelStorei
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexImage1D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.GL_TEXTURE_3D
import org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL30.GL_RGB8
import org.lwjgl.opengl.GL30.GL_RGBA8
import org.lwjgl.opengl.GL30.GL_R8
import org.lwjgl.opengl.GL30.GL_RG
import org.lwjgl.opengl.GL30.GL_RG8
import org.lwjgl.opengl.GL30.GL_R16F
import org.lwjgl.opengl.GL30.GL_RG16F
import org.lwjgl.opengl.GL30.GL_RGB16F
import org.lwjgl.opengl.GL30.GL_RGBA16F
import org.lwjgl.opengl.GL30.GL_R32F
import org.lwjgl.opengl.GL30.GL_RG32F
import org.lwjgl.opengl.GL30.GL_RGB32F
import org.lwjgl.opengl.GL30.GL_RGBA32F
import org.lwjgl.opengl.GL30.GL_RED
import org.lwjgl.opengl.GL30.GL_HALF_FLOAT
import org.lwjgl.opengl.GL31.GL_TEXTURE_RECTANGLE
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Random

/**
 * Generation-owned realization of Iris shader-pack PNG/raw and noise textures.
 *
 * Resource-backed descriptors are retained by the plan but never uploaded:
 * an active reference must first be transformed to a source-native renderer
 * bridge. Writable custom images are realized separately by
 * [IrisOpenGlCustomResources].
 */
internal class IrisOpenGlCustomTextures(
    context: RenderContext,
    private val plan: IrisTexturePlan,
) : AutoCloseable {
    private val system = context.system as? OpenGlRenderSystem
        ?: throw IllegalArgumentException("Iris custom textures require the OpenGL backend")
    private val handles = linkedMapOf<IrisTextureId, Handle>()
    private var initialized = false
    private var closed = false

    val physicalTextureCount: Int get() = handles.size

    fun prepare() {
        check(!closed) { "Iris custom textures are closed" }
        check(!initialized) { "Iris custom textures are already prepared" }
        try {
            plan.custom.forEach { binding ->
                if (binding.texture is IrisCustomTextureDescriptor.Resource) return@forEach
                require(binding.texture.id !in handles) {
                    "Duplicate Iris custom texture ID ${binding.texture.id}"
                }
                handles[binding.texture.id] = create(binding.texture)
            }
            val noise = when (val descriptor = plan.noise) {
                is IrisNoiseTextureDescriptor.Custom -> create(descriptor.texture)
                is IrisNoiseTextureDescriptor.Generated -> createNoise(descriptor.resolution)
            }
            require(handles.put(IrisTextureId("noise"), noise) == null) {
                "Iris custom texture plan reserves the noise texture ID"
            }
            initialized = true
        } catch (failure: Throwable) {
            release(failure)
            throw failure
        }
    }

    fun bind(id: IrisTextureId, unit: Int) {
        check(initialized && !closed) { "Iris custom textures are unavailable" }
        val handle = requireNotNull(handles[id]) { "Undeclared Iris custom texture $id" }
        system.bindTexture(unit, handle.target, handle.texture)
    }

    private fun create(descriptor: IrisCustomTextureDescriptor): Handle = when (descriptor) {
        is IrisCustomTextureDescriptor.Png -> create(descriptor.value)
        is IrisCustomTextureDescriptor.Raw -> create(descriptor.value)
        is IrisCustomTextureDescriptor.Resource ->
            throw IllegalArgumentException(
                "Iris resource texture ${descriptor.path} requires a source-native bridge",
            )
    }

    private fun create(descriptor: IrisPngTextureDescriptor): Handle {
        val buffer = try {
            ByteArrayInputStream(descriptor.content.copy()).readTexture()
        } catch (failure: Throwable) {
            throw IllegalArgumentException("Could not decode Iris PNG texture ${descriptor.path}", failure)
        }
        require(buffer.size.x == descriptor.width && buffer.size.y == descriptor.height) {
            "Decoded Iris PNG dimensions changed for ${descriptor.path}: ${buffer.size}"
        }
        buffer.data.rewind()
        val format = buffer.glFormat
        return createTexture(
            target = GL_TEXTURE_2D,
            width = descriptor.width,
            height = descriptor.height,
            depth = 1,
            internal = if (format == GL_RGBA) GL_RGBA8 else GL_RGB8,
            format = format,
            type = buffer.glType,
            data = buffer.data,
            linear = descriptor.blur,
            clamp = descriptor.clamp,
        )
    }

    private fun create(descriptor: IrisRawTextureDescriptor): Handle {
        val target = when (descriptor.target) {
            IrisRawTextureTarget.TEXTURE_1D -> GL_TEXTURE_1D
            IrisRawTextureTarget.TEXTURE_2D -> GL_TEXTURE_2D
            IrisRawTextureTarget.TEXTURE_3D -> GL_TEXTURE_3D
            IrisRawTextureTarget.TEXTURE_RECTANGLE -> GL_TEXTURE_RECTANGLE
        }
        val internal = when (descriptor.internalFormat) {
            "R8" -> GL_R8
            "RG8" -> GL_RG8
            "RGB8" -> GL_RGB8
            "RGBA8" -> GL_RGBA8
            "R16F" -> GL_R16F
            "RG16F" -> GL_RG16F
            "RGB16F" -> GL_RGB16F
            "RGBA16F" -> GL_RGBA16F
            "R32F" -> GL_R32F
            "RG32F" -> GL_RG32F
            "RGB32F" -> GL_RGB32F
            "RGBA32F" -> GL_RGBA32F
            else -> throw IllegalArgumentException("Unsupported Iris raw internal format ${descriptor.internalFormat}")
        }
        val format = when (descriptor.format) {
            IrisRawTextureFormat.RED -> GL_RED
            IrisRawTextureFormat.RG -> GL_RG
            IrisRawTextureFormat.RGB -> org.lwjgl.opengl.GL11.GL_RGB
            IrisRawTextureFormat.RGBA -> GL_RGBA
        }
        val type = when (descriptor.type) {
            IrisRawTextureType.UNSIGNED_BYTE -> GL_UNSIGNED_BYTE
            IrisRawTextureType.HALF_FLOAT -> GL_HALF_FLOAT
            IrisRawTextureType.FLOAT -> GL_FLOAT
        }
        return createTexture(
            target = target,
            width = descriptor.width,
            height = descriptor.height,
            depth = descriptor.depth,
            internal = internal,
            format = format,
            type = type,
            data = ByteBuffer.allocateDirect(descriptor.content.size).apply {
                put(descriptor.content.copy())
                flip()
            },
            linear = descriptor.blur,
            clamp = descriptor.clamp,
        )
    }

    private fun createNoise(resolution: Int): Handle {
        val data = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(resolution, resolution), 4))
        val random = Random(0L)
        repeat(Math.multiplyExact(resolution, resolution)) {
            val value = random.nextInt()
            data.put((value and 0xFF).toByte())
            data.put((value ushr 8 and 0xFF).toByte())
            data.put((value ushr 16 and 0xFF).toByte())
            data.put(0xFF.toByte())
        }
        data.flip()
        return createTexture(
            target = GL_TEXTURE_2D,
            width = resolution,
            height = resolution,
            depth = 1,
            internal = GL_RGBA8,
            format = GL_RGBA,
            type = GL_UNSIGNED_BYTE,
            data = data,
            linear = true,
            clamp = false,
        )
    }

    private fun createTexture(
        target: Int,
        width: Int,
        height: Int,
        depth: Int,
        internal: Int,
        format: Int,
        type: Int,
        data: ByteBuffer,
        linear: Boolean,
        clamp: Boolean,
    ): Handle {
        val texture = gl { glGenTextures() }
        system.resources.created(OpenGlResourceType.TEXTURE, texture)
        try {
            system.bindTexture(system.framebufferTextureIndex, target, texture)
            gl {
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
                glPixelStorei(GL_UNPACK_SKIP_ROWS, 0)
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
                // Pack descriptors contain exactly width * height * depth *
                // pixel-size bytes. Alignment 1 is therefore required for
                // narrow R/RG/RGB rows and is also valid for RGBA input.
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            }
            try {
                if (target == GL_TEXTURE_3D) {
                    gl { glTexImage3D(target, 0, internal, width, height, depth, 0, format, type, data) }
                } else if (target == GL_TEXTURE_1D) {
                    gl { glTexImage1D(target, 0, internal, width, 0, format, type, data) }
                } else {
                    gl { glTexImage2D(target, 0, internal, width, height, 0, format, type, data) }
                }
            } finally {
                gl { glPixelStorei(GL_UNPACK_ALIGNMENT, 4) }
            }
            system.textureParameter { glTexParameteri(target, GL_TEXTURE_MIN_FILTER, if (linear) GL_LINEAR else GL_NEAREST) }
            system.textureParameter { glTexParameteri(target, GL_TEXTURE_MAG_FILTER, if (linear) GL_LINEAR else GL_NEAREST) }
            val wrap = if (clamp) GL_CLAMP_TO_EDGE else GL_REPEAT
            system.textureParameter { glTexParameteri(target, GL_TEXTURE_WRAP_S, wrap) }
            if (target != GL_TEXTURE_1D) system.textureParameter { glTexParameteri(target, GL_TEXTURE_WRAP_T, wrap) }
            if (target == GL_TEXTURE_3D) system.textureParameter { glTexParameteri(target, org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R, wrap) }
            system.textureParameter { glTexParameteri(target, GL_TEXTURE_MAX_LEVEL, 0) }
            return Handle(texture, target)
        } catch (failure: Throwable) {
            delete(texture, failure)
            throw failure
        }
    }

    private fun release(original: Throwable? = null) {
        var failure = original
        handles.values.map(Handle::texture).toSet().forEach { texture ->
            try {
                delete(texture)
            } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
            }
        }
        handles.clear()
        initialized = false
        if (original == null) failure?.let { throw it }
    }

    private fun delete(texture: Int, original: Throwable? = null) {
        try {
            gl { glDeleteTextures(texture) }
            system.resources.deleted(OpenGlResourceType.TEXTURE, texture)
            system.invalidateTexture(texture)
        } catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            original.addSuppressed(cleanup)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        release()
    }

    private data class Handle(val texture: Int, val target: Int)
}
