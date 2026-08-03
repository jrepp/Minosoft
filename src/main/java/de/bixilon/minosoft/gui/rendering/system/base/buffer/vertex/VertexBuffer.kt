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

package de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex

import de.bixilon.minosoft.gui.rendering.system.base.buffer.GpuBuffer
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import java.nio.FloatBuffer

interface VertexBuffer : GpuBuffer {
    val vertices: Int
    val primitive: PrimitiveTypes
    val struct: MeshStruct

    fun draw()

    /**
     * Replaces vertex contents without changing the GPU buffer or vertex-array
     * identity. Implementations require the same float count as the original
     * allocation so callers cannot accidentally turn a sub-data update into
     * driver-side storage churn.
     */
    fun updateVertices(data: FloatBuffer) {
        throw UnsupportedOperationException("Dynamic vertex updates are not supported by ${this::class.java.name}")
    }

    /** Updates fixed-capacity storage while selecting a smaller used prefix. */
    fun updateVertices(data: FloatBuffer, usedVertices: Int) {
        updateVertices(data)
        setVertices(usedVertices)
    }

    fun setVertices(usedVertices: Int) {
        throw UnsupportedOperationException("A used vertex prefix is not supported by ${this::class.java.name}")
    }

    fun drop()
}
