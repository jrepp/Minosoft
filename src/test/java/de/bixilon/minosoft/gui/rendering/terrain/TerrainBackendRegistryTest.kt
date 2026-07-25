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

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainBackendRegistryTest {
    @Test
    fun `exactly one backend is selected and close restores built in`() {
        val builtIn = backend("minosoft:built-in")
        val registry = TerrainBackendRegistry(builtIn)
        val sodium = backend("minosoft:sodium")

        val registration = registry.replace { sodium }

        assertEquals("minosoft:sodium", registry.selection().owner.value)
        registration.close()
        registration.close()
        assertEquals("minosoft:built-in", registry.selection().owner.value)
        assertTrue(sodium.closed)
        assertFalse(builtIn.closed)

        registry.close()
        assertTrue(builtIn.closed)
    }

    @Test
    fun `backend retirement waits for outstanding frame lease`() {
        val registry = TerrainBackendRegistry(backend("minosoft:built-in"))
        val sodium = backend("minosoft:sodium")
        val registration = registry.replace { sodium }
        val lease = registry.acquire()

        registration.close()

        assertFalse(sodium.closed)
        assertEquals("minosoft:sodium", lease.backend.descriptor.owner.value)
        assertEquals(1, registry.stats().resources.retiredAwaitingLeases)

        lease.close()
        assertTrue(sodium.closed)
        assertEquals(0, registry.stats().resources.retiredAwaitingLeases)
        registry.close()
    }

    @Test
    fun `one backend generation owns the complete prepared frame`() {
        val builtIn = backend("minosoft:built-in")
        val registry = TerrainBackendRegistry(builtIn)
        val sodium = backend("minosoft:sodium")
        val registration = registry.replace { sodium }

        registry.prepare()
        registration.close()
        assertFalse(sodium.closed)

        registry.finishPreparation()
        registry.submit(RenderViewId.MAIN, TerrainMaterialClass.OPAQUE)
        registry.finishFrame()

        assertEquals(listOf("prepare", "finishPreparation", "submit:minosoft:main:OPAQUE", "finishFrame"), sodium.calls)
        assertTrue(sodium.closed)
        assertTrue(builtIn.calls.isEmpty())

        registry.prepare()
        registry.finishFrame()
        assertEquals(listOf("prepare", "finishFrame"), builtIn.calls)
        registry.close()
    }

    @Test
    fun `one material may be submitted once per view and frame`() {
        val registry = TerrainBackendRegistry(backend("minosoft:built-in"))

        registry.prepare()
        registry.submit(RenderViewId.MAIN, TerrainMaterialClass.OPAQUE)
        registry.submit(RenderViewId("minosoft:shadow"), TerrainMaterialClass.OPAQUE)
        assertThrows<IllegalArgumentException> {
            registry.submit(RenderViewId.MAIN, TerrainMaterialClass.OPAQUE)
        }
        registry.finishFrame()

        registry.prepare()
        registry.submit(RenderViewId.MAIN, TerrainMaterialClass.OPAQUE)
        registry.finishFrame()

        assertEquals(2L, registry.stats().preparedFrames)
        assertEquals(3L, registry.stats().submittedBatches)
        registry.close()
    }

    @Test
    fun `invalid candidate preserves active backend and is cleaned`() {
        val builtIn = backend("minosoft:built-in")
        val registry = TerrainBackendRegistry(builtIn)
        val invalid = object : TerrainBackend {
            var closed = false
            override val descriptor: TerrainBackendDescriptor
                get() = throw IllegalArgumentException("invalid candidate")

            override fun prepare() = Unit
            override fun finishPreparation() = Unit
            override fun submit(view: RenderViewId, material: TerrainMaterialClass) = Unit
            override fun finishFrame() = Unit
            override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) = Unit
            override fun close() {
                closed = true
            }
        }

        assertThrows<IllegalArgumentException> { registry.replace { invalid } }

        assertTrue(invalid.closed)
        assertEquals("minosoft:built-in", registry.selection().owner.value)
        assertEquals(0L, registry.selection().generation)
        registry.close()
    }

    private fun backend(owner: String) = RecordingBackend(owner)

    private class RecordingBackend(
        owner: String,
    ) : TerrainBackend {
        override val descriptor = TerrainBackendDescriptor(
            owner = RenderOwnerId(owner),
            implementation = owner,
            materials = TerrainMaterialClass.entries.toSet(),
            vertexLayout = BuiltInTerrainVertexLayout.VALUE,
            supportsAuxiliaryViews = false,
        )
        var closed = false
        val calls = mutableListOf<String>()

        override fun prepare() {
            calls += "prepare"
        }
        override fun finishPreparation() {
            calls += "finishPreparation"
        }
        override fun submit(view: RenderViewId, material: TerrainMaterialClass) {
            calls += "submit:${view.value}:$material"
        }
        override fun finishFrame() {
            calls += "finishFrame"
        }
        override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) = Unit
        override fun close() {
            closed = true
        }
    }
}
