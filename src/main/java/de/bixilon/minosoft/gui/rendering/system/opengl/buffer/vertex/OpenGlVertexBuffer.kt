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

package de.bixilon.minosoft.gui.rendering.system.opengl.buffer.vertex

import de.bixilon.minosoft.config.DebugOptions.EMPTY_BUFFERS
import de.bixilon.minosoft.gui.rendering.system.base.buffer.GpuBufferStates
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.VertexBuffer
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRegistry
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.buffer.FloatOpenGlBuffer
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL40.GL_PATCHES
import java.nio.FloatBuffer

class OpenGlVertexBuffer(
    private val system: OpenGlRenderSystem,
    override val primitive: PrimitiveTypes,
    override val struct: MeshStruct,
    val data: FloatOpenGlBuffer,
    val index: OpenGlIndexBuffer?,
) : VertexBuffer {
    override var state = GpuBufferStates.PREPARING
        private set
    private val vao = OpenGlVao(system, struct)
    private var allocatedVertices = -1
    private var requestedUsedVertices: Int? = null
    override var vertices = -1
        private set


    private fun prepareInit() {
        assert(state == GpuBufferStates.PREPARING)
        this.state = GpuBufferStates.INITIALIZING

        allocatedVertices = when {
            EMPTY_BUFFERS -> 0
            index != null -> index.data.limit()
            else -> data.data.limit() / struct.floats
        }
        vertices = if (EMPTY_BUFFERS) 0 else requestedUsedVertices ?: allocatedVertices
        require(vertices in 0..allocatedVertices) {
            "Used vertex count $vertices exceeds allocated capacity $allocatedVertices"
        }

        if (vertices == 0) {
            Log.log(LogMessageType.RENDERING, LogLevels.WARN) { "Empty vertex buffer: $this" }
        }
    }

    override fun init() {
        prepareInit()

        data.init()
        data.bind()

        vao.init()

        index?.init()

        unbind()
        state = GpuBufferStates.INITIALIZED
    }

    private fun unbind() {
        data.unbind()
        vao.unbind()
        index?.unbind()
    }


    override fun draw() {
        check(state == GpuBufferStates.INITIALIZED) { "Vertex buffer is not uploaded: $state" }
        val drawMode = drawMode(primitive, vertices, system.shader.activePatchVertices)
        val pipeline: ShaderPipelineRegistry? = system.context.shaderPipeline
        pipeline?.recordDraw(vertices)

        vao.bind()

        if (index == null) {
            system.work.drawArrays(vertices)
            gl { glDrawArrays(drawMode, 0, vertices) }
        } else {
            index.bind()
            system.work.drawElements(vertices)
            gl { glDrawElements(drawMode, vertices, GL_UNSIGNED_INT, 0) }
            index.unbind()
        }

        vao.unbind()
    }

    override fun updateVertices(data: FloatBuffer) {
        check(state == GpuBufferStates.INITIALIZED) { "Vertex buffer is not uploaded: $state" }
        this.data.update(data)
    }

    override fun updateVertices(data: FloatBuffer, usedVertices: Int) {
        updateVertices(data)
        setVertices(usedVertices)
    }

    override fun setVertices(usedVertices: Int) {
        if (state == GpuBufferStates.PREPARING) {
            require(usedVertices >= 0) { "Used vertex count must not be negative" }
            requestedUsedVertices = usedVertices
            return
        }
        check(state == GpuBufferStates.INITIALIZED) { "Vertex buffer is not uploaded: $state" }
        require(usedVertices in 0..allocatedVertices) {
            "Used vertex count $usedVertices exceeds allocated capacity $allocatedVertices"
        }
        vertices = usedVertices
    }

    override fun drop() {
        assert(state == GpuBufferStates.PREPARING)
        data.drop()
        index?.drop()

        state = GpuBufferStates.UNLOADED
    }

    override fun unload() {
        check(state == GpuBufferStates.INITIALIZED) { "Vertex buffer is not uploaded: $state" }
        data.unload()
        vao.unload()
        index?.unload()

        state = GpuBufferStates.UNLOADED
    }

    override fun toString() = "OpenGlVertexBuffer(vertices=$vertices, state=$state)"

    internal companion object {
        fun drawMode(primitive: PrimitiveTypes, vertices: Int, patchVertices: Int?): Int {
            if (patchVertices == null) return primitive.gl
            require(primitive.vertices == patchVertices) {
                "Tessellation shader requires $patchVertices-vertex patches but buffer uses $primitive"
            }
            require(vertices % patchVertices == 0) {
                "Tessellation draw contains $vertices vertices, not a multiple of patch size $patchVertices"
            }
            return GL_PATCHES
        }

        private val PrimitiveTypes.gl: Int
            get() = when (this) {
                PrimitiveTypes.POINT -> GL_POINTS
                PrimitiveTypes.LINE -> GL_LINES
                PrimitiveTypes.TRIANGLE -> GL_TRIANGLES
                PrimitiveTypes.QUAD -> GL_QUADS
            }
    }
}
