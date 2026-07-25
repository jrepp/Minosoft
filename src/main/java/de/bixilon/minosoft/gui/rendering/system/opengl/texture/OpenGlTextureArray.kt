/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
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

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.cast.CastUtil.cast
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureArray
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureArrayUpdate
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureSlotDiagnostics
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureSlotOwnership
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.TextureArrayStates
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.TextureData
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil.glFormat
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureUtil.glType
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import org.lwjgl.opengl.GL13.*
import org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY
import java.nio.ByteBuffer

class OpenGlTextureArray(
    val system: OpenGlRenderSystem,
    async: Boolean,
    mipmaps: Int,
) : StaticTextureArray(system.context, async, mipmaps) {
    private var handles = IntArray(RESOLUTIONS.size) { -1 }
    private var indices = IntArray(RESOLUTIONS.size) { -1 }

    private val resolution = Array<MutableList<Texture>>(RESOLUTIONS.size) { mutableListOf() }
    private val ownership = StaticTextureSlotOwnership<Slot>()
    private var handlesCreated = 0L
    private var handlesDeleted = 0L


    override fun use(shader: TextureShader, name: String) {
        if (state != TextureArrayStates.UPLOADED) throw IllegalStateException("Texture array is not uploaded yet! Are you trying to load a shader in the init phase?")
        system.log { "Binding static textures to $shader" }
        shader.use()

        for (index in indices) {
            if (index == -1) continue

            shader.native.setTexture("$name[$index]", index)
        }
    }

    override fun findResolution(size: Vec2i): Vec2i {
        if (size.x >= MAX_RESOLUTION || size.y >= MAX_RESOLUTION) return Vec2i(MAX_RESOLUTION)
        val array = findArray(size)
        if (array < 0) return Vec2i(0, 0)
        return Vec2i(RESOLUTIONS[array])
    }

    private fun findArray(size: Vec2i): Int {
        for ((index, resolution) in RESOLUTIONS.withIndex()) {
            if (size.x > resolution || size.y > resolution) continue
            return index
        }

        return -1
    }

    private fun glUpload(texture: Texture, textureData: TextureData = texture.data) {
        val renderData = texture.renderData.cast<OpenGlTextureData>()

        for ((level, buffer) in textureData.collect().withIndex()) {
            if (level > this.mipmaps) break
            buffer.data.position(0)
            buffer.data.limit(buffer.data.capacity())
            gl { glTexSubImage3D(GL_TEXTURE_2D_ARRAY, level, 0, 0, renderData.index, buffer.size.x, buffer.size.y, 1, buffer.glFormat, buffer.glType, buffer.data) }
        }
    }


    private fun upload(
        active: Int,
        resolution: Int,
        textures: List<Texture>,
        assignRenderData: Boolean = true,
        releaseStaticData: Boolean = true,
        data: (Texture) -> TextureData = Texture::data,
    ): Int {
        system.log { "Uploading ${resolution}x${resolution} static textures" }
        val handle = OpenGlTextureUtil.createTextureArray(active, mipmaps)
        handlesCreated++
        try {
            for (level in 0..mipmaps) {
                gl { glTexImage3D(GL_TEXTURE_2D_ARRAY, level, GL_RGBA8, resolution shr level, resolution shr level, textures.size.coerceAtLeast(1), 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?) }
            }

            var textureId = 0
            for (texture in textures) {
                if (assignRenderData) {
                    val size = texture.size
                    val uvEnd = if (size.x == resolution && size.y == resolution) null else Vec2f(size) / resolution
                    texture.renderData = OpenGlTextureData(active, textureId, uvEnd)
                }
                textureId++

                glUpload(texture, data(texture))

                if (releaseStaticData && texture.animation == null) {
                    texture.data = TextureData.NULL
                }
            }
            return handle
        } catch (error: Throwable) {
            try {
                deleteHandle(handle)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun update(texture: Texture) {
        val data = texture.renderData.cast<OpenGlTextureData>()

        val handle = handles[indices.indexOf(data.array)]
        assert(handle >= 0)

        if (system.boundTexture != handle) {
            gl { glActiveTexture(GL_TEXTURE0 + data.array) }
            gl { glBindTexture(GL_TEXTURE_2D_ARRAY, handle) }
            system.boundTexture = handle
        }

        glUpload(texture)
    }


    override fun upload(textures: Collection<Texture>) {
        if (state != TextureArrayStates.LOADED) throw IllegalStateException("Not loaded!")

        for (texture in textures) {
            if (texture.size.x > MAX_RESOLUTION || texture.size.y > MAX_RESOLUTION) {
                Log.log(LogMessageType.LOADING, LogLevels.WARN) { "Texture $texture exceeds max resolution ($MAX_RESOLUTION): ${texture.size}" }
                continue
            }

            val arrayId = findArray(texture.size)
            if (arrayId == -1) {
                Log.log(LogMessageType.LOADING, LogLevels.WARN) { "Can not find texture array for $arrayId" }
                continue
            }

            this.resolution[arrayId] += texture
        }
    }

    override fun upload(latch: AbstractLatch?) {
        super.upload(latch)
        var total = 0
        try {
            for ((index, textures) in resolution.withIndex()) {
                val active = system.nextTextureIndex++
                require(active in 0 until SHADER_TEXTURE_ARRAY_SIZE) {
                    "Static texture arrays exceed the shader's $SHADER_TEXTURE_ARRAY_SIZE sampler slots."
                }
                indices[index] = active

                handles[index] = upload(active, RESOLUTIONS[index], textures)
                total += textures.size
            }
        } catch (error: Throwable) {
            for (index in handles.indices) {
                val handle = handles[index]
                if (handle < 0) continue
                try {
                    deleteHandle(handle)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                handles[index] = -1
            }
            throw error
        }

        Log.log(LogMessageType.RENDERING, LogLevels.VERBOSE) { "Loaded ${named.size} textures containing ${animator.size} animated ones, split into $total layers!" }

        animator.init()
        state = TextureArrayStates.UPLOADED
    }

    override fun beginUpdate(): StaticTextureArrayUpdate {
        check(state == TextureArrayStates.UPLOADED) { "Static texture array is not uploaded." }
        check(Thread.currentThread() === context.thread) {
            "Static texture updates must be prepared on the render thread."
        }
        val free = ownership.freeSlots()
        val removed = removeNamed { texture -> slot(texture) in free }
        removed.values.distinct().forEach(animator::remove)
        return Update(free)
    }

    override fun retainGeneration(
        textures: Collection<Texture>,
        reclaimable: Collection<Texture>,
    ): AutoCloseable {
        val slots = textures.mapTo(linkedSetOf(), ::slot)
        val reclaimableSlots = reclaimable.mapTo(linkedSetOf(), ::slot)
        return ownership.retain(slots, reclaimableSlots)
    }

    fun ownershipDiagnostics(): StaticTextureSlotDiagnostics = ownership.diagnostics()

    fun handleDiagnostics(): OpenGlTextureHandleDiagnostics = OpenGlTextureHandleDiagnostics(
        created = handlesCreated,
        deleted = handlesDeleted,
        live = handlesCreated - handlesDeleted,
        active = handles.count { it >= 0 },
    )

    private fun slot(texture: Texture): Slot {
        val data = texture.renderData as? OpenGlTextureData
            ?: throw IllegalArgumentException("Texture $texture has no static OpenGL coordinates.")
        val arrayId = indices.indexOf(data.array)
        require(arrayId >= 0) { "Texture $texture uses unknown static sampler ${data.array}." }
        return Slot(arrayId, data.index)
    }

    private inner class Update(private val initiallyFree: Set<Slot>) : StaticTextureArrayUpdate {
        private val replacements = Array<MutableMap<Int, Texture>>(RESOLUTIONS.size) { linkedMapOf() }
        private val appended = Array<MutableList<Texture>>(RESOLUTIONS.size) { mutableListOf() }
        private val candidates = linkedMapOf<ResourceLocation, Texture>()
        private val previousNames = linkedMapOf<ResourceLocation, Texture?>()
        private val previousBuckets = linkedMapOf<Int, MutableList<Texture>>()
        private val candidateHandles = IntArray(RESOLUTIONS.size) { -1 }
        private val sameSlotReplacements = linkedSetOf<Texture>()
        private val generationSlots = linkedSetOf<Slot>()
        private val reclaimableSlots = linkedSetOf<Slot>()
        private val free = Array<MutableSet<Int>>(RESOLUTIONS.size) { arrayId ->
            initiallyFree.asSequence()
                .filter { it.array == arrayId }
                .map { it.index }
                .sorted()
                .toCollection(linkedSetOf())
        }
        private var uploaded = false
        private var published = false
        private var completed = false
        private var closed = false

        override fun resolve(name: ResourceLocation, mipmaps: Boolean, loader: TextureLoader): Texture {
            check(!uploaded && !closed) { "Static texture update is no longer accepting textures." }
            candidates[name]?.let { return it }

            val previous = this@OpenGlTextureArray[name]
            val candidate = Texture(loader, if (mipmaps) this@OpenGlTextureArray.mipmaps else 0)
            try {
                candidate.load(context)
            } catch (error: Throwable) {
                animator.remove(candidate)
                throw error
            }

            val existingData = previous?.renderData as? OpenGlTextureData
            if (previous != null && previous.size == candidate.size && existingData != null) {
                val arrayId = indices.indexOf(existingData.array)
                require(arrayId >= 0 && existingData.index in resolution[arrayId].indices) {
                    "Existing static texture $name has invalid render coordinates."
                }
                replacements[arrayId][existingData.index] = candidate
                candidate.renderData = OpenGlTextureData(existingData.array, existingData.index, existingData.uvEnd)
                sameSlotReplacements += previous
                val slot = Slot(arrayId, existingData.index)
                generationSlots += slot
                if (ownership.isManaged(slot)) reclaimableSlots += slot
            } else {
                val arrayId = findArray(candidate.size)
                require(arrayId >= 0) {
                    "Static texture $name exceeds the supported ${MAX_RESOLUTION}x$MAX_RESOLUTION size: ${candidate.size}."
                }
                val previousSlot = existingData?.let { data ->
                    indices.indexOf(data.array)
                        .takeIf { it >= 0 }
                        ?.let { Slot(it, data.index) }
                }
                val inheritsPermanentSlot = previousSlot != null && !ownership.isManaged(previousSlot)
                val reused = if (inheritsPermanentSlot) null else free[arrayId].firstOrNull()
                val layer = reused ?: resolution[arrayId].size + appended[arrayId].size
                val bucketResolution = RESOLUTIONS[arrayId]
                val uvEnd = if (candidate.size.x == bucketResolution && candidate.size.y == bucketResolution) {
                    null
                } else {
                    Vec2f(candidate.size) / bucketResolution
                }
                candidate.renderData = OpenGlTextureData(indices[arrayId], layer, uvEnd)
                if (reused == null) {
                    appended[arrayId] += candidate
                } else {
                    free[arrayId].remove(reused)
                    replacements[arrayId][reused] = candidate
                    resolution[arrayId].getOrNull(reused)?.let(sameSlotReplacements::add)
                }
                val slot = Slot(arrayId, layer)
                generationSlots += slot
                if (!inheritsPermanentSlot) reclaimableSlots += slot
            }

            previousNames[name] = previous
            candidates[name] = candidate
            return candidate
        }

        override fun retain(texture: Texture): Texture {
            check(!uploaded && !closed) { "Static texture update is no longer accepting textures." }
            generationSlots += slot(texture)
            return texture
        }

        override fun upload() {
            check(!uploaded && !closed) { "Static texture update was already uploaded or closed." }
            try {
                for (arrayId in RESOLUTIONS.indices) {
                    val textures = candidateBucket(arrayId)
                    if (textures == resolution[arrayId]) continue
                    previousBuckets[arrayId] = resolution[arrayId]
                    candidateHandles[arrayId] = upload(
                        active = indices[arrayId],
                        resolution = RESOLUTIONS[arrayId],
                        textures = textures,
                        assignRenderData = false,
                        releaseStaticData = false,
                    ) { texture ->
                        if (texture in candidates.values || texture.animation != null) {
                            texture.data
                        } else {
                            val source = texture.loader.load(context).buffer
                            texture.createData(buffer = source)
                        }
                    }
                }
                restoreHandles(handles)
                uploaded = true
            } catch (error: Throwable) {
                deleteCandidateHandles(error)
                try {
                    restoreHandles(handles)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                closeSuppressing(error)
                throw error
            }
        }

        override fun publish() {
            check(uploaded && !published && !closed) { "Static texture update is not ready to publish." }
            published = true
            try {
                for ((arrayId, previous) in previousBuckets) {
                    val textures = candidateBucket(arrayId)
                    resolution[arrayId] = textures
                    bind(indices[arrayId], candidateHandles[arrayId])
                }
                publishNamed(candidates)
            } catch (error: Throwable) {
                closeSuppressing(error)
                throw error
            }
        }

        override fun complete(): AutoCloseable {
            check(published && !completed && !closed) { "Static texture update is not published." }
            completed = true
            closed = true
            for ((arrayId, _) in previousBuckets) {
                val previous = handles[arrayId]
                handles[arrayId] = candidateHandles[arrayId]
                candidateHandles[arrayId] = -1
                if (previous >= 0) {
                    try {
                        deleteHandle(previous)
                    } catch (error: Throwable) {
                        Log.log(LogMessageType.RENDERING, LogLevels.WARN) {
                            "Could not delete replaced static texture handle $previous: $error"
                        }
                    }
                }
            }
            for (texture in sameSlotReplacements) {
                animator.remove(texture)
            }
            for (texture in candidates.values) {
                if (texture.animation == null) texture.data = TextureData.NULL
            }
            val compacted = initiallyFree.filterTo(linkedSetOf()) { slot ->
                slot.index >= resolution[slot.array].size
            }
            ownership.forget(compacted)
            return ownership.retain(generationSlots, reclaimableSlots)
        }

        override fun close() {
            if (closed || completed) return
            if (published) {
                restoreHandles(handles)
                for ((arrayId, previous) in previousBuckets) {
                    resolution[arrayId] = previous
                }
                restoreNamed(previousNames)
            }
            var failure: Throwable? = null
            try {
                deleteCandidateHandles()
            } catch (error: Throwable) {
                failure = error
            }
            for (texture in candidates.values) {
                try {
                    animator.remove(texture)
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            closed = true
            failure?.let { throw it }
        }

        private fun deleteCandidateHandles(original: Throwable? = null) {
            var failure = original
            for (index in candidateHandles.indices) {
                val handle = candidateHandles[index]
                if (handle < 0) continue
                try {
                    deleteHandle(handle)
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                } finally {
                    candidateHandles[index] = -1
                }
            }
            if (original == null) failure?.let { throw it }
        }

        private fun closeSuppressing(original: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                original.addSuppressed(cleanup)
            }
        }

        private fun candidateBucket(arrayId: Int): MutableList<Texture> {
            val textures = resolution[arrayId].toMutableList()
            for ((index, texture) in replacements[arrayId]) {
                textures[index] = texture
            }
            textures += appended[arrayId]
            while (textures.isNotEmpty()) {
                val index = textures.lastIndex
                if (index !in free[arrayId]) break
                textures.removeAt(index)
            }
            return textures
        }
    }

    private fun restoreHandles(source: IntArray) {
        for (index in source.indices) {
            val handle = source[index]
            if (handle < 0) continue
            bind(indices[index], handle)
        }
    }

    private fun bind(index: Int, handle: Int) {
        gl { glActiveTexture(GL_TEXTURE0 + index) }
        gl { glBindTexture(GL_TEXTURE_2D_ARRAY, handle) }
        system.boundTexture = handle
    }

    private fun deleteHandle(handle: Int) {
        gl { glDeleteTextures(handle) }
        handlesDeleted++
    }

    private companion object {
        const val SHADER_TEXTURE_ARRAY_SIZE = 16
        const val MAX_RESOLUTION = 2048
        val RESOLUTIONS = intArrayOf(16, 32, 64, 128, 256, 512, 1024, MAX_RESOLUTION) // A 12x12 texture will be saved in texture id 0 (in 0 are only 16x16 textures). Animated textures get split
    }

    private data class Slot(val array: Int, val index: Int)
}

data class OpenGlTextureHandleDiagnostics(
    val created: Long,
    val deleted: Long,
    val live: Long,
    val active: Int,
)
