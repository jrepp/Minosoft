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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_NEAREST
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrisOpenGlRenderTargetsTest {
    @Test
    fun `identity caches suppress only committed matching Iris state`() {
        val shaderA = String(charArrayOf('a'))
        val equalButDistinctShader = String(charArrayOf('a'))
        val bindingCache = IrisIdentityBindingCache<Any, String>()

        assertEquals(shaderA, equalButDistinctShader)
        assertFalse(bindingCache.matches(shaderA, "state"))
        bindingCache.record(shaderA, "state")
        assertTrue(bindingCache.matches(shaderA, "state"))
        assertFalse(bindingCache.matches(equalButDistinctShader, "state"))
        bindingCache.invalidate(shaderA)
        assertFalse(bindingCache.matches(shaderA, "state"))

        val source = Any()
        val target = Any()
        val revisions = IrisIdentityRevisionCache<Any, Any>()
        assertTrue(revisions.requiresSync(source, target, revision = 0L, uploadInProgress = false))
        revisions.record(source, target, 0L)
        assertFalse(revisions.requiresSync(source, target, revision = 0L, uploadInProgress = false))
        assertFalse(revisions.requiresSync(source, target, revision = 1L, uploadInProgress = true))
        assertTrue(revisions.requiresSync(source, target, revision = 1L, uploadInProgress = false))
        revisions.record(source, target, 1L)
        assertFalse(revisions.requiresSync(source, target, revision = 1L, uploadInProgress = false))
        assertTrue(revisions.requiresSync(source, Any(), revision = 1L, uploadInProgress = false))
    }

    @Test
    fun `framebuffer binding state suppresses exact repeats and notices target flips`() {
        val state = IrisFramebufferBindingState()

        assertTrue(state.matchesColors(0) { error("No color expected") })
        assertFalse(state.matchesDepth(30))
        assertFalse(state.matchesSequentialDrawBuffers(0, 100))

        state.record(intArrayOf(), depth = 30, drawBuffers = intArrayOf(), readBuffer = 0)
        assertTrue(state.matchesDepth(30))
        assertTrue(state.matchesSequentialDrawBuffers(0, 100))
        assertTrue(state.matchesReadBuffer(0))

        state.record(intArrayOf(10, 20), depth = 30, drawBuffers = intArrayOf(100, 101), readBuffer = 100)
        assertTrue(state.matchesColors(2) { intArrayOf(10, 20)[it] })
        assertTrue(state.matchesSequentialDrawBuffers(2, 100))
        assertFalse(state.matchesColors(2) { intArrayOf(10, 21)[it] })
        assertFalse(state.matchesDepth(31))
        assertFalse(state.matchesReadBuffer(0))

        state.clear()
        assertFalse(state.matchesSequentialDrawBuffers(0, 100))
        assertFalse(state.matchesReadBuffer(100))
    }

    @Test
    fun `load targets initialize once while clear targets reset every frame`() {
        assertEquals(true, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.LOAD, initializing = true))
        assertEquals(false, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.LOAD, initializing = false))
        assertEquals(true, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.CLEAR, initializing = true))
        assertEquals(true, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.CLEAR, initializing = false))
    }

    @Test
    fun `mipmap filtering is scoped to the program requesting generation`() {
        assertEquals(GL_LINEAR, IrisOpenGlRenderTargets.minificationFilter(ShaderBufferFilter.LINEAR, mipmaps = false))
        assertEquals(
            GL_LINEAR_MIPMAP_LINEAR,
            IrisOpenGlRenderTargets.minificationFilter(ShaderBufferFilter.LINEAR, mipmaps = true),
        )
        assertEquals(GL_NEAREST, IrisOpenGlRenderTargets.minificationFilter(ShaderBufferFilter.NEAREST, mipmaps = false))
        assertEquals(
            GL_NEAREST_MIPMAP_NEAREST,
            IrisOpenGlRenderTargets.minificationFilter(ShaderBufferFilter.NEAREST, mipmaps = true),
        )
        assertEquals(
            GL_LINEAR,
            IrisOpenGlRenderTargets.minificationFilter(ShaderBufferFilter.SHADOW_COMPARE, mipmaps = false),
        )
    }

    @Test
    fun `depth snapshots preserve independent main and shadow pre-translucent depth`() {
        assertEquals(
            ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 0) to
                ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 1),
            IrisOpenGlRenderTargets.depthSnapshotBuffers(ShaderDepthSnapshot.DISTANT_BEFORE_TRANSLUCENT),
        )
        assertEquals(
            ShaderBufferId(ShaderBufferKind.DEPTHTEX, 0) to
                ShaderBufferId(ShaderBufferKind.DEPTHTEX, 1),
            IrisOpenGlRenderTargets.depthSnapshotBuffers(ShaderDepthSnapshot.BEFORE_TRANSLUCENT),
        )
        assertEquals(
            ShaderBufferId(ShaderBufferKind.DEPTHTEX, 0) to
                ShaderBufferId(ShaderBufferKind.DEPTHTEX, 2),
            IrisOpenGlRenderTargets.depthSnapshotBuffers(ShaderDepthSnapshot.BEFORE_HAND),
        )
        assertEquals(
            ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0) to
                ShaderBufferId(ShaderBufferKind.SHADOWTEX, 1),
            IrisOpenGlRenderTargets.depthSnapshotBuffers(ShaderDepthSnapshot.SHADOW_BEFORE_TRANSLUCENT),
        )
    }

    @Test
    fun `fullscreen passes reuse low units while scene passes preserve host samplers`() {
        assertEquals(
            0,
            IrisOpenGlRenderTargets.firstIrisSamplerUnit(ShaderProgramPhase.DEFERRED, hostUnits = 11),
        )
        assertEquals(
            0,
            IrisOpenGlRenderTargets.firstIrisSamplerUnit(ShaderProgramPhase.FINAL, hostUnits = 11),
        )
        assertEquals(
            11,
            IrisOpenGlRenderTargets.firstIrisSamplerUnit(ShaderProgramPhase.TERRAIN, hostUnits = 11),
        )
    }

    @Test
    fun `scene samplers fill sparse units around compact host arrays`() {
        assertEquals(
            listOf(0, 1, 2, 11, 12, 13, 14),
            IrisOpenGlRenderTargets.allocateSamplerUnits(
                maximum = 16,
                required = 7,
                reserved = (3..10).toSet(),
            ),
        )
        assertEquals(
            8,
            IrisOpenGlRenderTargets.allocateSamplerUnits(
                maximum = 16,
                required = 9,
                reserved = (3..10).toSet(),
            ).size,
        )
    }

    @Test
    fun `logical history buffers retain the last fullscreen write across frames`() {
        val state = IrisBufferFlipState()

        assertEquals(10, state.read(primary = 10, alternate = 20))
        assertEquals(20, state.write(primary = 10, alternate = 20, alternateWrite = true))
        state.flip()

        // The next frame samples the previous fullscreen result and writes the
        // other side. No frame-boundary reset is permitted for non-cleared
        // temporal buffers.
        assertEquals(20, state.read(primary = 10, alternate = 20))
        assertEquals(10, state.write(primary = 10, alternate = 20, alternateWrite = true))
        state.flip()
        assertEquals(10, state.read(primary = 10, alternate = 20))
    }

    @Test
    fun `single buffered and direct writes retain their current texture`() {
        val state = IrisBufferFlipState()
        state.flip()

        assertEquals(10, state.read(primary = 10, alternate = -1))
        assertEquals(10, state.write(primary = 10, alternate = -1, alternateWrite = true))
        assertEquals(20, state.write(primary = 10, alternate = 20, alternateWrite = false))
    }

    @Test
    fun `pass texture access rejects direct and stale flip feedback before submission`() {
        val state = IrisBufferFlipState()
        IrisOpenGlRenderTargets.validatePassTextureAccess(
            "safe-first",
            attachedTextures = listOf(state.write(10, 20, alternateWrite = true)),
            sampledTextures = mapOf("history" to state.read(10, 20)),
        )
        state.flip()
        IrisOpenGlRenderTargets.validatePassTextureAccess(
            "safe-flipped",
            attachedTextures = listOf(state.write(10, 20, alternateWrite = true)),
            sampledTextures = mapOf("history" to state.read(10, 20)),
        )

        assertFailsWith<IllegalArgumentException> {
            IrisOpenGlRenderTargets.validatePassTextureAccess(
                "direct-feedback",
                attachedTextures = listOf(state.write(10, 20, alternateWrite = false)),
                sampledTextures = mapOf("history" to state.read(10, 20)),
            )
        }
        IrisOpenGlRenderTargets.validatePassTextureAccess(
            "declared-feedback",
            attachedTextures = listOf(20),
            sampledTextures = mapOf("history" to 20),
            explicitlyPermittedTextures = setOf(20),
        )
    }

    @Test
    fun `per-buffer blend state inherits the program override without leaking to explicit outputs`() {
        val inherited = BlendFunctionState(
            BlendingFunctions.SOURCE_ALPHA,
            BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
            BlendingFunctions.ONE,
            BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
        )
        val override = IrisProgramBlendOverride(
            program = IrisBlendMode.Enabled(inherited),
            buffers = mapOf(
                ShaderBufferId(ShaderBufferKind.COLORTEX, 4) to IrisBlendMode.Off,
            ),
        )

        assertEquals(
            listOf(
                IrisResolvedBlendMode(true, inherited),
                IrisResolvedBlendMode(false, BlendFunctionState.DEFAULT),
                IrisResolvedBlendMode(true, inherited),
            ),
            override.resolve(
                listOf(
                    ShaderBufferId(ShaderBufferKind.COLORTEX, 0),
                    ShaderBufferId(ShaderBufferKind.COLORTEX, 4),
                    ShaderBufferId(ShaderBufferKind.COLORTEX, 8),
                ),
                hostEnabled = false,
                hostFunction = BlendFunctionState.DEFAULT,
            ),
        )
    }
}
