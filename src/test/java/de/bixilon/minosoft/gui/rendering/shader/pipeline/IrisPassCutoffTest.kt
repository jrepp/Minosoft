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

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class IrisPassCutoffTest {
    @Test
    fun `stage cutoffs admit only their completed fullscreen families`() {
        assertFalse(IrisPassCutoff.Shadow.allows(ShaderProgramPhase.PREPARE))
        assertTrue(IrisPassCutoff.Geometry.allows(ShaderProgramPhase.PREPARE))
        assertFalse(IrisPassCutoff.Geometry.allows(ShaderProgramPhase.DEFERRED))
        assertTrue(IrisPassCutoff.Deferred.allows(ShaderProgramPhase.DEFERRED))
        assertFalse(IrisPassCutoff.Deferred.allows(ShaderProgramPhase.COMPOSITE))
        assertTrue(IrisPassCutoff.Final.allows(ShaderProgramPhase.COMPOSITE))
    }

    @Test
    fun `composite cutoff executes through the named pass inclusively`() {
        val cutoff = IrisPassCutoff.Composite("composite3")
        val programs = listOf("composite", "composite1", "composite3", "composite4")

        assertEquals(
            listOf("composite", "composite1", "composite3"),
            cutoff.programs(ShaderProgramPhase.COMPOSITE, programs),
        )
        assertEquals(programs, cutoff.programs(ShaderProgramPhase.DEFERRED, programs))
    }

    @Test
    fun `cutoff follows final presentation buffer only after its producer`() {
        val colortex0 = ShaderBufferId(ShaderBufferKind.COLORTEX, 0)
        val colortex3 = ShaderBufferId(ShaderBufferKind.COLORTEX, 3)
        val programs = listOf(
            fullscreen("composite", ShaderBufferId(ShaderBufferKind.COLORTEX, 7)),
            fullscreen("composite1", colortex0),
            fullscreen("composite4", colortex3),
            fullscreen("composite5", colortex3),
        )

        assertEquals(colortex0, IrisPassCutoff.Composite("composite").presentationBuffer(colortex3, programs))
        assertEquals(colortex0, IrisPassCutoff.Composite("composite1").presentationBuffer(colortex3, programs))
        assertEquals(colortex3, IrisPassCutoff.Composite("composite4").presentationBuffer(colortex3, programs))
        assertEquals(colortex3, IrisPassCutoff.Composite("composite5").presentationBuffer(colortex3, programs))
        assertEquals(colortex0, IrisPassCutoff.Deferred.presentationBuffer(colortex3, programs))
    }

    @Test
    fun `cutoff parser rejects unbounded names before formatting plan diagnostics`() {
        val plan = ShaderPipelinePlan(
            owner = RenderOwnerId("minosoft:test"),
            packName = "test",
            fingerprint = "0".repeat(64),
            views = setOf(RenderViewId.MAIN),
            resources = RenderResourcePlan(emptyList(), emptyList()),
            programs = listOf(fullscreen("composite", ShaderBufferId(ShaderBufferKind.COLORTEX, 0))),
            requiredTerrainSemantics = emptySet(),
        )

        assertFailsWith<IllegalArgumentException> {
            IrisPassCutoff.parse("x".repeat(257), plan)
        }
    }

    private fun fullscreen(name: String, output: ShaderBufferId) = ShaderProgramSource(
        name = name,
        phase = ShaderProgramPhase.COMPOSITE,
        vertex = "void main() {}",
        fragment = "void main() {}",
        uniforms = emptySet(),
        samplers = emptySet(),
        resourceUsage = ShaderProgramResourceUsage(colorWrites = listOf(output)),
    )
}
