/*
 * Minosoft
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

package de.bixilon.minosoft.gui.rendering.chunk.mesh

import de.bixilon.minosoft.gui.rendering.system.base.query.QueryStates
import de.bixilon.minosoft.gui.rendering.system.base.query.QueryTypes
import de.bixilon.minosoft.gui.rendering.system.base.query.RenderQuery
import de.bixilon.minosoft.gui.rendering.system.dummy.buffer.DummyVertexBuffer
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["chunk_renderer"])
class ChunkMeshOcclusionTest {
    fun `pending query is not collected`() {
        val query = Query(isReady = false, result = 0)
        val mesh = ChunkMesh(DummyVertexBuffer(ChunkMeshBuilder.ChunkMeshStruct), query)

        mesh.updateOcclusion()

        assertEquals(query.collections, 0)
        assertEquals(mesh.occlusion, ChunkMesh.OcclusionStates.MAYBE)
    }

    fun `ready query updates occlusion`() {
        val query = Query(isReady = true, result = 11)
        val mesh = ChunkMesh(DummyVertexBuffer(ChunkMeshBuilder.ChunkMeshStruct), query)

        mesh.updateOcclusion()

        assertEquals(query.collections, 1)
        assertEquals(mesh.occlusion, ChunkMesh.OcclusionStates.VISIBLE)
    }

    private class Query(
        override val isReady: Boolean,
        override val result: Int,
    ) : RenderQuery {
        override val recordings = 1
        override val type = QueryTypes.FRAGMENTS
        override val state = QueryStates.INITIALIZED
        var collections = 0
            private set

        override fun collect() {
            collections++
        }

        override fun init() = Unit
        override fun destroy() = Unit
        override fun begin() = Unit
        override fun end() = Unit
    }
}
