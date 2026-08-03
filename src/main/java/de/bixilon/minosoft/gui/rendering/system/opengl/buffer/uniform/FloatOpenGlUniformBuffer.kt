/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.gui.rendering.system.base.buffer.uniform.FloatUniformBuffer
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL15C.nglBufferSubData
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memFree
import java.nio.FloatBuffer

class FloatOpenGlUniformBuffer(
    system: OpenGlRenderSystem,
    bindingIndex: Int,
    override var data: FloatBuffer,
) : OpenGlUniformBuffer(system, bindingIndex), FloatUniformBuffer {
    // glBindBufferRange takes its range in bytes. The upload API below uses
    // float indices, so keep its bounds checks against data.limit() rather than
    // accidentally shrinking the bound uniform block to one quarter of its
    // storage.
    override val size get() = bindingSizeBytes(data.limit())

    override fun initialUpload() {
        data.position(0)
        system.work.uniformBufferUpload(size.toLong())
        gl { glBufferData(glType, data, GL_STREAM_DRAW) }
    }

    override fun upload() {
        check(initialSize == size) { "Can not change buffer size!" }
        bind()
        system.work.uniformBufferUpload(size.toLong())
        gl { glBufferSubData(glType, 0, data) }
        unbind()
    }

    override fun upload(start: Int, end: Int) {
        check(initialSize == size) { "Can not change buffer size!" }
        val bytes = uploadByteCount(start, end, data.limit())
        bind()
        system.work.uniformBufferUpload(bytes.toLong())
        gl { nglBufferSubData(glType, start.toLong() * Float.SIZE_BYTES, bytes.toLong(), MemoryUtil.memAddress(data, start)) }
        unbind()
    }

    override fun unsafeDrop() {
        if (data.isDirect) memFree(data)
        this::data.forceSet(null)
    }

    internal companion object {
        fun bindingSizeBytes(floatCount: Int): Int = Math.multiplyExact(floatCount, Float.SIZE_BYTES)

        fun uploadByteCount(start: Int, end: Int, limit: Int): Int {
            require(limit >= 0) { "Uniform buffer limit must not be negative" }
            if (start < 0 || end < start || end >= limit) {
                throw IndexOutOfBoundsException("Uniform upload range $start..$end is outside 0 until $limit")
            }
            return Math.multiplyExact(Math.addExact(Math.subtractExact(end, start), 1), Float.SIZE_BYTES)
        }
    }
}
