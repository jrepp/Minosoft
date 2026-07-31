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

package de.bixilon.minosoft.gui.rendering.system.opengl.buffer

import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.config.DebugOptions.EMPTY_BUFFERS
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL15C
import org.lwjgl.system.MemoryUtil.memAddress
import org.lwjgl.system.MemoryUtil.memFree
import java.nio.FloatBuffer

class FloatOpenGlBuffer(
    system: OpenGlRenderSystem,
    val data: FloatBuffer,
    val free: Boolean,
) : OpenGlGpuBuffer(system) {
    private val floats = data.remaining()

    override val glType get() = GL_ARRAY_BUFFER


    override fun initialUpload() {
        gl { nglBufferData(glType, data, if (EMPTY_BUFFERS) 0 else floats, GL_STATIC_DRAW) }
        unsafeDrop()
    }

    fun update(data: FloatBuffer) {
        check(state == de.bixilon.minosoft.gui.rendering.system.base.buffer.GpuBufferStates.INITIALIZED) {
            "Cannot update a vertex buffer in state $state"
        }
        require(data.remaining() == floats) {
            "Dynamic vertex update has ${data.remaining()} floats, expected $floats"
        }
        bind()
        if (!EMPTY_BUFFERS) {
            if (data.isDirect) {
                gl { GL15C.nglBufferSubData(glType, 0L, Integer.toUnsignedLong(floats) * Float.SIZE_BYTES, memAddress(data)) }
            } else {
                gl { glBufferSubData(glType, 0L, data.copyRemaining(floats)) }
            }
        }
    }

    override fun unsafeDrop() {
        if (free && data.isDirect) memFree(this.data)
        this::data.forceSet(null)
    }


    private fun nglBufferData(target: Int, buffer: FloatBuffer, length: Int, usage: Int) {
        if (buffer.isDirect) {
            gl { GL15C.nglBufferData(target, Integer.toUnsignedLong(length) * Float.SIZE_BYTES, memAddress(buffer), usage) }
        } else {
            val array = buffer.copyRemaining(length)
            gl { glBufferData(target, array, usage) }
        }
    }

    private fun FloatBuffer.copyRemaining(length: Int): FloatArray {
        if (length == 0) return FloatArray(0)
        require(remaining() >= length) { "Float buffer has fewer than $length remaining elements" }
        return FloatArray(length).also { duplicate().get(it) }
    }
}
