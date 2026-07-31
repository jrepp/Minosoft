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

package de.bixilon.minosoft.gui.rendering.terrain.near.provider

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainInvalidationReason
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.TerrainSectionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkRendererNearTerrainBackendTest {
    @Test
    fun `configured provider delegates frame operations and rejects use after close`() {
        val core = RecordingNearTerrainCore()
        val descriptor = TerrainBackendDescriptor(
            owner = RenderOwnerId("minosoft:test-near-terrain"),
            implementation = "test-near-terrain",
            materials = TerrainMaterialClass.entries.toSet(),
            vertexLayout = BuiltInTerrainVertexLayout.VALUE,
            supportsAuxiliaryViews = true,
        )
        val backend = ChunkRendererNearTerrainBackend(core, descriptor)

        backend.prepare()
        backend.finishPreparation()
        backend.submit(RenderViewId.MAIN, TerrainMaterialClass.OPAQUE)
        backend.finishFrame()
        backend.close()
        backend.close()

        assertEquals(descriptor, backend.descriptor)
        assertEquals(
            listOf("prepare", "finishPreparation", "submit:minosoft:main:OPAQUE", "finishFrame"),
            core.calls,
        )
        assertFailsWith<IllegalStateException> { backend.prepare() }
    }

    private class RecordingNearTerrainCore : NearTerrainRenderCore {
        val calls = mutableListOf<String>()

        override fun prepare() {
            calls += "prepare"
        }

        override fun finishPreparation() {
            calls += "finishPreparation"
        }

        override fun submit(view: RenderViewId, material: TerrainMaterialClass) {
            calls += "submit:$view:$material"
        }

        override fun finishFrame() {
            calls += "finishFrame"
        }

        override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) {
            calls += "invalidate:${snapshot.position}:$reason"
        }
    }
}
