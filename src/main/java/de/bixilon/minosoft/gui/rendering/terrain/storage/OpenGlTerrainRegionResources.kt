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

package de.bixilon.minosoft.gui.rendering.terrain.storage

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.Rendering
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.buffer.vertex.OpenGlVao
import de.bixilon.minosoft.gui.rendering.system.opengl.buffer.vertex.OpenGlVertexBuffer
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainSubmission
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionCompletion
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionState
import de.bixilon.minosoft.terrain.model.mesh.TerrainPrimitiveTopology
import de.bixilon.minosoft.terrain.runtime.storage.TerrainBufferArena
import de.bixilon.minosoft.terrain.runtime.storage.TerrainDrawBatch
import de.bixilon.minosoft.terrain.runtime.storage.TerrainDrawCommand
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionUploadDevice
import de.bixilon.minosoft.terrain.runtime.storage.TerrainUploadPlan
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL30.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED
import org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.GL_WAIT_FAILED
import org.lwjgl.opengl.GL32.glClientWaitSync
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glDrawElementsBaseVertex
import org.lwjgl.opengl.GL32.glFenceSync
import org.lwjgl.opengl.GL32.glMultiDrawElementsBaseVertex
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree

/** Shared ordinary-buffer OpenGL adapter for provider-owned terrain layouts. */
internal class OpenGlTerrainRegionDevice(
    private val system: OpenGlRenderSystem,
    struct: MeshStruct,
    override val deviceRuntime: TerrainDeviceRuntimeId,
    override val layoutGeneration: Long,
    override val vertexCapacityBytes: Int,
    override val indexCapacityBytes: Int,
    private val staging: OpenGlTerrainStagingBuffer,
) : TerrainRegionUploadDevice, AutoCloseable {
    private val vao = OpenGlVao(system, struct)
    private val vertexStrideBytes = struct.bytes
    private var vertexBuffer = -1
    private var indexBuffer = -1
    private var vaoInitialized = false
    private var initialized = false

    override fun upload(plan: TerrainUploadPlan) {
        require(plan.layoutGeneration == layoutGeneration)
        plan.operations.forEach { operation ->
            val capacity = when (operation.arena) {
                TerrainBufferArena.VERTEX -> vertexCapacityBytes
                TerrainBufferArena.INDEX -> indexCapacityBytes
            }
            require(operation.range.offset <= capacity - operation.range.length) {
                "Terrain upload exceeds the ${operation.arena} arena"
            }
            require(operation.range.length <= staging.capacityBytes) {
                "Terrain upload exceeds the staging capacity"
            }
        }
        ensureInitialized()
        for (operation in plan.operations) {
            val target = when (operation.arena) {
                TerrainBufferArena.VERTEX -> GL_ARRAY_BUFFER
                TerrainBufferArena.INDEX -> GL_ELEMENT_ARRAY_BUFFER
            }
            val handle = when (operation.arena) {
                TerrainBufferArena.VERTEX -> vertexBuffer
                TerrainBufferArena.INDEX -> indexBuffer
            }
            bind(target, handle)
            staging.upload(target, operation.range.offset.toLong(), operation.copyBytes())
        }
    }

    fun draw(batch: TerrainDrawBatch): Int {
        if (batch.commands.isEmpty()) return 0
        ensureInitialized()
        require(batch.commands.all {
            it.vertexStrideBytes == vertexStrideBytes && it.indexElementBytes == Int.SIZE_BYTES
        }) { "Terrain region batch uses an unsupported physical layout" }
        val total = batch.commands.sumOf(TerrainDrawCommand::indexCount)
        system.context.shaderPipeline.recordDraw(total)
        vao.bind()
        try {
            bind(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
            val groups = batch.commands.groupBy(TerrainDrawCommand::topology)
            for ((topology, commands) in groups) {
                val mode = OpenGlVertexBuffer.drawMode(
                    when (topology) {
                        TerrainPrimitiveTopology.TRIANGLES -> PrimitiveTypes.TRIANGLE
                        TerrainPrimitiveTopology.QUADS -> PrimitiveTypes.QUAD
                    },
                    commands.sumOf(TerrainDrawCommand::indexCount),
                    system.shader.activePatchVertices,
                )
                if (MULTI_DRAW && commands.size > 1) {
                    MemoryStack.stackPush().use { stack ->
                        val counts = stack.mallocInt(commands.size)
                        val offsets = stack.mallocPointer(commands.size)
                        val bases = stack.mallocInt(commands.size)
                        commands.forEachIndexed { index, command ->
                            counts.put(index, command.indexCount)
                            offsets.put(index, command.indexRange.offset.toLong())
                            bases.put(index, command.vertexRange.offset / command.vertexStrideBytes)
                        }
                        gl { glMultiDrawElementsBaseVertex(mode, counts, GL_UNSIGNED_INT, offsets, bases) }
                    }
                } else {
                    commands.forEach { command ->
                        gl {
                            glDrawElementsBaseVertex(
                                mode,
                                command.indexCount,
                                GL_UNSIGNED_INT,
                                command.indexRange.offset.toLong(),
                                command.vertexRange.offset / command.vertexStrideBytes,
                            )
                        }
                    }
                }
            }
            return groups.size
        } finally {
            vao.unbind()
        }
    }

    override fun close() {
        var failure: Throwable? = null
        if (vaoInitialized) {
            try {
                vao.unload()
                vaoInitialized = false
            } catch (error: Throwable) {
                failure = error
            }
        }
        try {
            deleteBuffer(vertexBuffer)
            vertexBuffer = -1
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            deleteBuffer(indexBuffer)
            indexBuffer = -1
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        initialized = false
        if (failure != null) throw failure
    }

    private fun ensureInitialized() {
        if (initialized) return
        try {
            vertexBuffer = createBuffer(GL_ARRAY_BUFFER, vertexCapacityBytes)
            bind(GL_ARRAY_BUFFER, vertexBuffer)
            vao.init()
            vaoInitialized = true
            indexBuffer = createBuffer(GL_ELEMENT_ARRAY_BUFFER, indexCapacityBytes)
            initialized = true
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun createBuffer(target: Int, capacity: Int): Int {
        val handle = gl { glGenBuffers() }
        system.resources.created(OpenGlResourceType.BUFFER, handle)
        try {
            bind(target, handle)
            gl { glBufferData(target, capacity.toLong(), GL_DYNAMIC_DRAW) }
            return handle
        } catch (failure: Throwable) {
            deleteBuffer(handle)
            throw failure
        }
    }

    private fun bind(target: Int, handle: Int) {
        if (system.boundBuffer[target] == handle) return
        gl { glBindBuffer(target, handle) }
        system.boundBuffer.put(target, handle)
    }

    private fun deleteBuffer(handle: Int) {
        if (handle <= 0) return
        gl { glDeleteBuffers(handle) }
        system.resources.deleted(OpenGlResourceType.BUFFER, handle)
        if (system.boundBuffer[GL_ARRAY_BUFFER] == handle) system.boundBuffer -= GL_ARRAY_BUFFER
        if (system.boundBuffer[GL_ELEMENT_ARRAY_BUFFER] == handle) system.boundBuffer -= GL_ELEMENT_ARRAY_BUFFER
    }

    private companion object {
        val MULTI_DRAW = !java.lang.Boolean.getBoolean("minosoft.terrain.region-conventional-draw")
    }
}

internal class OpenGlTerrainStagingBuffer(val capacityBytes: Int) : AutoCloseable {
    private var buffer = memAlloc(requirePositiveCapacity(capacityBytes))
    private var closed = false

    init {
        require(capacityBytes > 0) { "Terrain staging capacity must be positive" }
    }

    fun upload(target: Int, offset: Long, bytes: ByteArray) {
        check(!closed) { "Terrain staging buffer is closed" }
        require(bytes.size <= capacityBytes) { "Terrain upload exceeds staging capacity" }
        buffer.clear()
        buffer.put(bytes)
        buffer.flip()
        gl { glBufferSubData(target, offset, buffer) }
    }

    override fun close() {
        if (closed) return
        closed = true
        memFree(buffer)
    }

    private companion object {
        fun requirePositiveCapacity(capacity: Int): Int {
            require(capacity > 0) { "Terrain staging capacity must be positive" }
            return capacity
        }
    }
}

internal class OpenGlTerrainSubmissionCompletion(
    private val context: RenderContext,
    override val deviceRuntime: TerrainDeviceRuntimeId,
) : TerrainSubmissionCompletion, AutoCloseable {
    private val fences = LinkedHashMap<TerrainSubmissionSerial, Long>()
    private val failed = HashSet<TerrainSubmissionSerial>()
    private var completedThrough = 0L
    val pendingFences: Int get() = fences.size

    fun fence(serial: TerrainSubmissionSerial): TerrainSubmission {
        check(serial.value > completedThrough && serial !in fences && serial !in failed) {
            "Duplicate terrain submission serial $serial"
        }
        requireCurrentContext()
        val fence = gl { glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0) }
        if (fence == 0L) failed += serial else fences[serial] = fence
        return TerrainSubmission(deviceRuntime, serial)
    }

    /** Advances ordered fences even when no retired allocation asks for state. */
    fun collect() {
        val newest = fences.keys.lastOrNull() ?: return
        if (state(newest) != TerrainSubmissionState.PENDING) return
        val oldest = fences.keys.firstOrNull() ?: return
        if (oldest != newest) state(oldest)
    }

    override fun state(serial: TerrainSubmissionSerial): TerrainSubmissionState {
        if (serial in failed) return TerrainSubmissionState.FAILED
        if (serial.value <= completedThrough) return TerrainSubmissionState.COMPLETE
        val fence = fences[serial] ?: return TerrainSubmissionState.FAILED
        requireCurrentContext()
        val result = gl { glClientWaitSync(fence, 0, 0L) }
        val state = when (result) {
            GL_ALREADY_SIGNALED, GL_CONDITION_SATISFIED -> TerrainSubmissionState.COMPLETE
            GL_WAIT_FAILED -> TerrainSubmissionState.FAILED
            else -> TerrainSubmissionState.PENDING
        }
        if (state == TerrainSubmissionState.COMPLETE) {
            completedThrough = maxOf(completedThrough, serial.value)
            val iterator = fences.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.value > completedThrough) continue
                gl { glDeleteSync(entry.value) }
                iterator.remove()
            }
        } else if (state == TerrainSubmissionState.FAILED) {
            gl { glDeleteSync(fence) }
            fences.remove(serial)
            failed += serial
        }
        return state
    }

    override fun close() {
        if (fences.isNotEmpty()) requireCurrentContext()
        var failure: Throwable? = null
        val iterator = fences.iterator()
        while (iterator.hasNext()) {
            val fence = iterator.next()
            try {
                gl { glDeleteSync(fence.value) }
                iterator.remove()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (failure == null) {
            failed.clear()
            completedThrough = 0L
        } else {
            throw failure
        }
    }

    private fun requireCurrentContext() {
        check(Thread.currentThread() === context.thread && Rendering.currentContext === context) {
            "Terrain submission fences require the owning OpenGL render context"
        }
    }
}
