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
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.opengl

import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenGlWorkCountersTest {
    @Test
    fun `texture state is independent by unit and target and invalidates retired names`() {
        val state = OpenGlTextureBindingState()

        assertFalse(state.needsActivation(0))
        assertTrue(state.needsBinding(0, GL_TEXTURE_2D, 7))
        state.recordBinding(0, GL_TEXTURE_2D, 7)
        assertFalse(state.needsBinding(0, GL_TEXTURE_2D, 7))
        assertTrue(state.needsBinding(1, GL_TEXTURE_2D, 7))
        state.recordBinding(1, GL_TEXTURE_2D, 7)
        assertTrue(state.needsBinding(0, GL_TEXTURE_2D_ARRAY, 7))
        state.recordBinding(0, GL_TEXTURE_2D_ARRAY, 7)
        assertTrue(state.needsBinding(0, GL_TEXTURE_2D, 8))
        state.recordBinding(0, GL_TEXTURE_2D, 8)
        assertFalse(state.needsBinding(0, GL_TEXTURE_2D, 8))

        state.invalidate(7)
        assertTrue(state.needsBinding(1, GL_TEXTURE_2D, 7))
        assertTrue(state.needsBinding(0, GL_TEXTURE_2D_ARRAY, 7))
        assertFalse(state.needsBinding(0, GL_TEXTURE_2D, 8))

        state.clear()
        assertTrue(state.needsActivation(0))
        state.recordActive(0)
        assertFalse(state.needsActivation(0))
        assertTrue(state.needsBinding(0, GL_TEXTURE_2D, 8))
    }

    @Test
    fun `snapshot separates requests from physical work`() {
        val counters = OpenGlWorkCounters()
        counters.drawArrays(4)
        counters.multiDrawElementsBaseVertex(commands = 3, vertices = 18)
        counters.programRequest(changed = true)
        counters.programRequest(changed = false)
        counters.textureBindRequest(changed = true, GL_TEXTURE_2D)
        counters.textureBindRequest(changed = false, GL_TEXTURE_2D)
        counters.textureBindRequest(changed = true, GL_TEXTURE_2D_ARRAY)
        counters.drawBufferChange()
        counters.readBufferChange()
        counters.samplerParameterChange()
        counters.matrixUniformUpload()
        counters.uniformBufferUpload(64L)

        val snapshot = counters.snapshot()
        assertEquals(2L, snapshot.physicalDrawCalls)
        assertEquals(4L, snapshot.logicalDrawCommands)
        assertEquals(22L, snapshot.submittedElements)
        assertEquals(2L, snapshot.programRequests)
        assertEquals(1L, snapshot.programChanges)
        assertEquals(3L, snapshot.textureBindRequests)
        assertEquals(2L, snapshot.textureBinds)
        assertEquals(1L, snapshot.drawBufferChanges)
        assertEquals(1L, snapshot.readBufferChanges)
        assertEquals(1L, snapshot.samplerParameterChanges)
        assertEquals(2L, snapshot.uniformUploads)
        assertEquals(64L, snapshot.uniformBufferUploadBytes)
    }
}
