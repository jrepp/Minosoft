/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class IrisOpenGlRenderTargetsTest {
    @Test
    fun `load targets initialize once while clear targets reset every frame`() {
        assertEquals(true, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.LOAD, initializing = true))
        assertEquals(false, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.LOAD, initializing = false))
        assertEquals(true, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.CLEAR, initializing = true))
        assertEquals(true, IrisOpenGlRenderTargets.shouldClearTexture(RenderClearPolicy.CLEAR, initializing = false))
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
