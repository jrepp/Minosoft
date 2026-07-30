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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import org.lwjgl.opengl.ARBClearTexture
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_BYTE
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_RED
import org.lwjgl.opengl.GL11.GL_RGB
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SHORT
import org.lwjgl.opengl.GL11.GL_TEXTURE_1D
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetInteger
import org.lwjgl.opengl.GL11.glTexImage1D
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.GL_MAX_3D_TEXTURE_SIZE
import org.lwjgl.opengl.GL12.GL_TEXTURE_3D
import org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL
import org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.GL_HALF_FLOAT
import org.lwjgl.opengl.GL30.GL_R16F
import org.lwjgl.opengl.GL30.GL_R16I
import org.lwjgl.opengl.GL30.GL_R16UI
import org.lwjgl.opengl.GL30.GL_R32F
import org.lwjgl.opengl.GL30.GL_R32I
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_R8
import org.lwjgl.opengl.GL30.GL_R8I
import org.lwjgl.opengl.GL30.GL_R8UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL30.GL_RG
import org.lwjgl.opengl.GL30.GL_RG16F
import org.lwjgl.opengl.GL30.GL_RG16I
import org.lwjgl.opengl.GL30.GL_RG16UI
import org.lwjgl.opengl.GL30.GL_RG32F
import org.lwjgl.opengl.GL30.GL_RG32I
import org.lwjgl.opengl.GL30.GL_RG32UI
import org.lwjgl.opengl.GL30.GL_RG8
import org.lwjgl.opengl.GL30.GL_RG8I
import org.lwjgl.opengl.GL30.GL_RG8UI
import org.lwjgl.opengl.GL30.GL_RG_INTEGER
import org.lwjgl.opengl.GL30.GL_RGB16F
import org.lwjgl.opengl.GL30.GL_RGB16I
import org.lwjgl.opengl.GL30.GL_RGB16UI
import org.lwjgl.opengl.GL30.GL_RGB32F
import org.lwjgl.opengl.GL30.GL_RGB32I
import org.lwjgl.opengl.GL30.GL_RGB32UI
import org.lwjgl.opengl.GL30.GL_RGB8
import org.lwjgl.opengl.GL30.GL_RGB8I
import org.lwjgl.opengl.GL30.GL_RGB8UI
import org.lwjgl.opengl.GL30.GL_RGB_INTEGER
import org.lwjgl.opengl.GL30.GL_RGBA16F
import org.lwjgl.opengl.GL30.GL_RGBA16I
import org.lwjgl.opengl.GL30.GL_RGBA16UI
import org.lwjgl.opengl.GL30.GL_RGBA32F
import org.lwjgl.opengl.GL30.GL_RGBA32I
import org.lwjgl.opengl.GL30.GL_RGBA32UI
import org.lwjgl.opengl.GL30.GL_RGBA8
import org.lwjgl.opengl.GL30.GL_RGBA8I
import org.lwjgl.opengl.GL30.GL_RGBA8UI
import org.lwjgl.opengl.GL30.GL_RGBA_INTEGER
import org.lwjgl.opengl.GL30.GL_MAX_TEXTURE_SIZE
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL32.glGetInteger64
import org.lwjgl.opengl.GL42.GL_ALL_BARRIER_BITS
import org.lwjgl.opengl.GL42.GL_MAX_IMAGE_UNITS
import org.lwjgl.opengl.GL42.GL_READ_WRITE
import org.lwjgl.opengl.GL42.glBindImageTexture
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_DYNAMIC_COPY
import org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER
import org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE
import org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glBindBuffer
import org.lwjgl.opengl.GL43.glBufferData
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDeleteBuffers
import org.lwjgl.opengl.GL43.glDispatchComputeIndirect
import org.lwjgl.opengl.GL43.glGenBuffers
import org.lwjgl.opengl.GL44.glClearTexImage
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Generation-owned realization of Iris writable images and bufferObject SSBOs.
 *
 * Nothing is shared across generations. Capability and limit checks happen
 * before publication, partial allocations are deleted on failure, and every
 * binding is restored by the next ordinary pipeline bind.
 */
internal class IrisOpenGlCustomResources(
    context: RenderContext,
    private val plan: IrisCustomResourcePlan,
) : AutoCloseable {
    private val system = context.system as? OpenGlRenderSystem
        ?: throw IllegalArgumentException("Iris custom resources require the OpenGL backend")
    private var images = linkedMapOf<String, ImageHandle>()
    private var buffers = linkedMapOf<Int, BufferHandle>()
    private var size = Vec2i(-1, -1)
    private var initialized = false
    private var closed = false

    val physicalImageCount get() = images.size
    val physicalBufferCount get() = buffers.size

    fun prepare(framebufferSize: Vec2i) {
        check(!closed && !initialized) { "Iris custom resources are already prepared or closed" }
        if (plan.images.isEmpty() && plan.shaderStorageBuffers.isEmpty()) {
            initialized = true
            return
        }
        val capabilities = GL.getCapabilities()
        require(plan.images.isEmpty() || capabilities.OpenGL42) {
            "Iris custom images require OpenGL 4.2 image load/store"
        }
        require(plan.shaderStorageBuffers.isEmpty() || capabilities.OpenGL43) {
            "Iris bufferObject directives require OpenGL 4.3 shader-storage buffers"
        }
        require(plan.images.isEmpty() || capabilities.OpenGL44 || capabilities.GL_ARB_clear_texture) {
            "Iris custom images require OpenGL 4.4 or ARB_clear_texture for deterministic clears"
        }
        resize(framebufferSize)
        initialized = true
    }

    fun beginFrame(framebufferSize: Vec2i) {
        check(initialized && !closed) { "Iris custom resources are unavailable" }
        if (hasRelativeResources() && framebufferSize != size) resize(framebufferSize)
        images.values.filter { it.descriptor.clear }.forEach(::clear)
        bindShaderStorageBuffers()
    }

    fun bindImages(native: NativeShader, names: Set<String>, firstUnit: Int = 0): Int {
        require(firstUnit >= 0) { "First Iris image unit must be non-negative" }
        if (names.isEmpty()) return firstUnit
        val active = names.mapNotNull { name ->
            images[name]?.takeIf { native.hasUniform(name) }
        }
        val maximum = gl { glGetInteger(GL_MAX_IMAGE_UNITS) }
        require(firstUnit + active.size <= maximum) {
            "Shader requires ${firstUnit + active.size} Iris images but the driver exposes $maximum image units"
        }
        active.forEachIndexed { offset, image ->
            val unit = firstUnit + offset
            gl {
                glBindImageTexture(
                    unit,
                    image.texture,
                    0,
                    image.descriptor.target == IrisCustomImageTarget.TEXTURE_3D,
                    0,
                    GL_READ_WRITE,
                    image.descriptor.internalFormat.gl,
                )
            }
            native.setInt(image.descriptor.name, unit)
        }
        return firstUnit + active.size
    }

    fun bindSampler(name: String, unit: Int) {
        val image = requireNotNull(images.values.firstOrNull { it.descriptor.sampler == name }) {
            "Undeclared Iris custom-image sampler $name"
        }
        gl { glActiveTexture(GL_TEXTURE0 + unit) }
        gl { glBindTexture(image.descriptor.target.gl, image.texture) }
        system.boundTexture = image.texture
    }

    fun memoryBarrier(force: Boolean = false) {
        if (!force && images.isEmpty() && buffers.isEmpty()) return
        gl { glMemoryBarrier(GL_ALL_BARRIER_BITS) }
    }

    fun dispatchIndirect(pointer: IrisComputeDispatch.Indirect) {
        val buffer = requireNotNull(buffers[pointer.bufferIndex]) {
            "Iris indirect compute buffer ${pointer.bufferIndex} is unavailable"
        }
        require(pointer.offset <= buffer.bytes - INDIRECT_DISPATCH_BYTES) {
            "Iris indirect compute dispatch reads $INDIRECT_DISPATCH_BYTES bytes at offset " +
                "${pointer.offset} from ${buffer.bytes}-byte bufferObject.${pointer.bufferIndex}"
        }
        gl { glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, buffer.buffer) }
        gl { glDispatchComputeIndirect(pointer.offset) }
    }

    private fun resize(requested: Vec2i) {
        require(requested.x > 0 && requested.y > 0) {
            "Iris custom resources require a positive framebuffer size: $requested"
        }
        val candidateImages = linkedMapOf<String, ImageHandle>()
        val candidateBuffers = linkedMapOf<Int, BufferHandle>()
        try {
            validateLimits(requested)
            plan.images.forEach { descriptor ->
                candidateImages[descriptor.name] = createImage(descriptor, requested)
            }
            plan.shaderStorageBuffers.forEach { descriptor ->
                candidateBuffers[descriptor.index] = createBuffer(descriptor, requested)
            }
        } catch (failure: Throwable) {
            release(candidateImages, candidateBuffers, failure)
            throw failure
        }
        val oldImages = images
        val oldBuffers = buffers
        images = candidateImages
        buffers = candidateBuffers
        size = requested
        release(oldImages, oldBuffers)
        bindShaderStorageBuffers()
    }

    private fun validateLimits(requested: Vec2i) {
        val max2d = gl { glGetInteger(GL_MAX_TEXTURE_SIZE) }
        val max3d = gl { glGetInteger(GL_MAX_3D_TEXTURE_SIZE) }
        plan.images.forEach { descriptor ->
            val dimensions = descriptor.dimensions(requested)
            val limit = if (descriptor.target == IrisCustomImageTarget.TEXTURE_3D) max3d else max2d
            require(dimensions.x <= limit && dimensions.y <= limit && dimensions.z <= limit) {
                "Iris custom image ${descriptor.name} is ${dimensions.x}x${dimensions.y}x${dimensions.z}, " +
                    "driver limit is $limit"
            }
        }
        if (plan.shaderStorageBuffers.isNotEmpty()) {
            val maximumBindings = gl { glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) }
            require(plan.shaderStorageBuffers.all { it.index < maximumBindings }) {
                "Iris shader-storage binding exceeds driver limit $maximumBindings"
            }
            val maximumBytes = gl { glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE) }
            plan.shaderStorageBuffers.forEach { descriptor ->
                val bytes = descriptor.bytes(requested)
                require(bytes <= maximumBytes) {
                    "Iris shader-storage buffer ${descriptor.index} requires $bytes bytes, " +
                        "driver limit is $maximumBytes"
                }
            }
        }
    }

    private fun createImage(descriptor: IrisCustomImageDescriptor, requested: Vec2i): ImageHandle {
        val dimensions = descriptor.dimensions(requested)
        val texture = gl { glGenTextures() }
        system.resources.created(OpenGlResourceType.TEXTURE, texture)
        try {
            gl { glBindTexture(descriptor.target.gl, texture) }
            when (descriptor.target) {
                IrisCustomImageTarget.TEXTURE_1D -> gl {
                    glTexImage1D(
                        GL_TEXTURE_1D, 0, descriptor.internalFormat.gl, dimensions.x, 0,
                        descriptor.format.gl, descriptor.type.gl, null as ByteBuffer?,
                    )
                }
                IrisCustomImageTarget.TEXTURE_2D -> gl {
                    glTexImage2D(
                        GL_TEXTURE_2D, 0, descriptor.internalFormat.gl, dimensions.x, dimensions.y, 0,
                        descriptor.format.gl, descriptor.type.gl, null as ByteBuffer?,
                    )
                }
                IrisCustomImageTarget.TEXTURE_3D -> gl {
                    glTexImage3D(
                        GL_TEXTURE_3D, 0, descriptor.internalFormat.gl,
                        dimensions.x, dimensions.y, dimensions.z, 0,
                        descriptor.format.gl, descriptor.type.gl, null as ByteBuffer?,
                    )
                }
            }
            val filter = if (descriptor.internalFormat.name.endsWith("I") ||
                descriptor.internalFormat.name.endsWith("UI")) GL_NEAREST else GL_LINEAR
            gl { glTexParameteri(descriptor.target.gl, GL_TEXTURE_MIN_FILTER, filter) }
            gl { glTexParameteri(descriptor.target.gl, GL_TEXTURE_MAG_FILTER, filter) }
            gl { glTexParameteri(descriptor.target.gl, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE) }
            if (descriptor.target != IrisCustomImageTarget.TEXTURE_1D) {
                gl { glTexParameteri(descriptor.target.gl, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE) }
            }
            if (descriptor.target == IrisCustomImageTarget.TEXTURE_3D) {
                gl { glTexParameteri(descriptor.target.gl, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE) }
            }
            gl { glTexParameteri(descriptor.target.gl, GL_TEXTURE_MAX_LEVEL, 0) }
            return ImageHandle(descriptor, texture).also(::clear)
        } catch (failure: Throwable) {
            deleteTexture(texture, failure)
            throw failure
        }
    }

    private fun createBuffer(
        descriptor: IrisShaderStorageBufferDescriptor,
        requested: Vec2i,
    ): BufferHandle {
        val bytes = descriptor.bytes(requested)
        val buffer = gl { glGenBuffers() }
        system.resources.created(OpenGlResourceType.BUFFER, buffer)
        try {
            gl { glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer) }
            gl { glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_COPY) }
            gl {
                glClearBufferData(
                    GL_SHADER_STORAGE_BUFFER,
                    GL_R8I,
                    GL_RED,
                    GL_BYTE,
                    null as ByteBuffer?,
                )
            }
            gl { glBindBufferBase(GL_SHADER_STORAGE_BUFFER, descriptor.index, buffer) }
            return BufferHandle(descriptor, buffer, bytes)
        } catch (failure: Throwable) {
            deleteBuffer(buffer, failure)
            throw failure
        }
    }

    private fun bindShaderStorageBuffers() {
        buffers.values.forEach { buffer ->
            gl { glBindBufferBase(GL_SHADER_STORAGE_BUFFER, buffer.descriptor.index, buffer.buffer) }
        }
    }

    private fun clear(image: ImageHandle) {
        gl {
            if (GL.getCapabilities().OpenGL44) {
                glClearTexImage(
                    image.texture,
                    0,
                    image.descriptor.format.gl,
                    image.descriptor.type.gl,
                    null as ByteBuffer?,
                )
            } else {
                ARBClearTexture.glClearTexImage(
                    image.texture,
                    0,
                    image.descriptor.format.gl,
                    image.descriptor.type.gl,
                    null as ByteBuffer?,
                )
            }
        }
    }

    private fun hasRelativeResources() =
        plan.images.any { it.size is IrisCustomImageSize.Relative } ||
            plan.shaderStorageBuffers.any(IrisShaderStorageBufferDescriptor::relative)

    private fun release(
        imageHandles: MutableMap<String, ImageHandle>,
        bufferHandles: MutableMap<Int, BufferHandle>,
        original: Throwable? = null,
    ) {
        var failure = original
        imageHandles.values.forEach { image ->
            try {
                deleteTexture(image.texture)
            } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
            }
        }
        bufferHandles.values.forEach { buffer ->
            try {
                deleteBuffer(buffer.buffer)
            } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
            }
        }
        imageHandles.clear()
        bufferHandles.clear()
        if (original == null) failure?.let { throw it }
    }

    private fun deleteTexture(texture: Int, original: Throwable? = null) {
        try {
            gl { glDeleteTextures(texture) }
            system.resources.deleted(OpenGlResourceType.TEXTURE, texture)
            if (system.boundTexture == texture) system.boundTexture = -1
        } catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            original.addSuppressed(cleanup)
        }
    }

    private fun deleteBuffer(buffer: Int, original: Throwable? = null) {
        try {
            gl { glDeleteBuffers(buffer) }
            system.resources.deleted(OpenGlResourceType.BUFFER, buffer)
        } catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            original.addSuppressed(cleanup)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        release(images, buffers)
        initialized = false
    }

    private data class ImageHandle(
        val descriptor: IrisCustomImageDescriptor,
        val texture: Int,
    )

    private data class BufferHandle(
        val descriptor: IrisShaderStorageBufferDescriptor,
        val buffer: Int,
        val bytes: Long,
    )

    private data class Dimensions(val x: Int, val y: Int, val z: Int)

    private companion object {
        const val INDIRECT_DISPATCH_BYTES = 3L * Int.SIZE_BYTES
    }

    private fun IrisCustomImageDescriptor.dimensions(framebuffer: Vec2i): Dimensions = when (val value = size) {
        is IrisCustomImageSize.Absolute -> Dimensions(value.width, value.height, value.depth)
        is IrisCustomImageSize.Relative -> Dimensions(
            max(1, (framebuffer.x * value.widthScale).toInt()),
            max(1, (framebuffer.y * value.heightScale).toInt()),
            1,
        )
    }

    private fun IrisShaderStorageBufferDescriptor.bytes(framebuffer: Vec2i): Long {
        if (!relative) return bytesPerElement
        val width = max(1L, (framebuffer.x * widthScale).toLong())
        val height = max(1L, (framebuffer.y * heightScale).toLong())
        return Math.multiplyExact(Math.multiplyExact(width, height), bytesPerElement)
    }

    private val IrisCustomImageTarget.gl get() = when (this) {
        IrisCustomImageTarget.TEXTURE_1D -> GL_TEXTURE_1D
        IrisCustomImageTarget.TEXTURE_2D -> GL_TEXTURE_2D
        IrisCustomImageTarget.TEXTURE_3D -> GL_TEXTURE_3D
    }

    private val IrisCustomImageFormat.gl get() = when (this) {
        IrisCustomImageFormat.RED -> GL_RED
        IrisCustomImageFormat.RG -> GL_RG
        IrisCustomImageFormat.RGB -> GL_RGB
        IrisCustomImageFormat.RGBA -> GL_RGBA
        IrisCustomImageFormat.RED_INTEGER -> GL_RED_INTEGER
        IrisCustomImageFormat.RG_INTEGER -> GL_RG_INTEGER
        IrisCustomImageFormat.RGB_INTEGER -> GL_RGB_INTEGER
        IrisCustomImageFormat.RGBA_INTEGER -> GL_RGBA_INTEGER
    }

    private val IrisCustomImageType.gl get() = when (this) {
        IrisCustomImageType.BYTE -> GL_BYTE
        IrisCustomImageType.SHORT -> GL_SHORT
        IrisCustomImageType.INT -> org.lwjgl.opengl.GL11.GL_INT
        IrisCustomImageType.HALF_FLOAT -> GL_HALF_FLOAT
        IrisCustomImageType.FLOAT -> GL_FLOAT
        IrisCustomImageType.UNSIGNED_BYTE -> GL_UNSIGNED_BYTE
        IrisCustomImageType.UNSIGNED_SHORT -> GL_UNSIGNED_SHORT
        IrisCustomImageType.UNSIGNED_INT -> GL_UNSIGNED_INT
    }

    private val IrisCustomImageInternalFormat.gl get() = when (this) {
        IrisCustomImageInternalFormat.R8 -> GL_R8
        IrisCustomImageInternalFormat.RG8 -> GL_RG8
        IrisCustomImageInternalFormat.RGB8 -> GL_RGB8
        IrisCustomImageInternalFormat.RGBA8 -> GL_RGBA8
        IrisCustomImageInternalFormat.R16F -> GL_R16F
        IrisCustomImageInternalFormat.RG16F -> GL_RG16F
        IrisCustomImageInternalFormat.RGB16F -> GL_RGB16F
        IrisCustomImageInternalFormat.RGBA16F -> GL_RGBA16F
        IrisCustomImageInternalFormat.R32F -> GL_R32F
        IrisCustomImageInternalFormat.RG32F -> GL_RG32F
        IrisCustomImageInternalFormat.RGB32F -> GL_RGB32F
        IrisCustomImageInternalFormat.RGBA32F -> GL_RGBA32F
        IrisCustomImageInternalFormat.R8I -> GL_R8I
        IrisCustomImageInternalFormat.RG8I -> GL_RG8I
        IrisCustomImageInternalFormat.RGB8I -> GL_RGB8I
        IrisCustomImageInternalFormat.RGBA8I -> GL_RGBA8I
        IrisCustomImageInternalFormat.R8UI -> GL_R8UI
        IrisCustomImageInternalFormat.RG8UI -> GL_RG8UI
        IrisCustomImageInternalFormat.RGB8UI -> GL_RGB8UI
        IrisCustomImageInternalFormat.RGBA8UI -> GL_RGBA8UI
        IrisCustomImageInternalFormat.R16I -> GL_R16I
        IrisCustomImageInternalFormat.RG16I -> GL_RG16I
        IrisCustomImageInternalFormat.RGB16I -> GL_RGB16I
        IrisCustomImageInternalFormat.RGBA16I -> GL_RGBA16I
        IrisCustomImageInternalFormat.R16UI -> GL_R16UI
        IrisCustomImageInternalFormat.RG16UI -> GL_RG16UI
        IrisCustomImageInternalFormat.RGB16UI -> GL_RGB16UI
        IrisCustomImageInternalFormat.RGBA16UI -> GL_RGBA16UI
        IrisCustomImageInternalFormat.R32I -> GL_R32I
        IrisCustomImageInternalFormat.RG32I -> GL_RG32I
        IrisCustomImageInternalFormat.RGB32I -> GL_RGB32I
        IrisCustomImageInternalFormat.RGBA32I -> GL_RGBA32I
        IrisCustomImageInternalFormat.R32UI -> GL_R32UI
        IrisCustomImageInternalFormat.RG32UI -> GL_RG32UI
        IrisCustomImageInternalFormat.RGB32UI -> GL_RGB32UI
        IrisCustomImageInternalFormat.RGBA32UI -> GL_RGBA32UI
    }
}
