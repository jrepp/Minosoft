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

import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderPipelineRegistryTest {
    private val terrain = TerrainBackendDescriptor(
        owner = RenderOwnerId("minosoft:test-terrain"),
        implementation = "test",
        materials = TerrainMaterialClass.entries.toSet(),
        vertexLayout = BuiltInTerrainVertexLayout.VALUE,
        supportsAuxiliaryViews = true,
    )

    @Test
    fun `accepted pipeline is selected and registration restores built in`() {
        val registry = ShaderPipelineRegistry()
        val pipeline = pipeline(required = setOf(VertexSemantic.POSITION))

        val registration = registry.replace(terrain) { pipeline }

        assertEquals(pipeline.owner, registry.selection().owner)
        assertEquals(pipeline.plan!!.fingerprint, registry.selection().fingerprint)
        registration.close()
        registration.close()
        assertEquals(BuiltInWorldShaderPipeline.owner, registry.selection().owner)
        assertTrue(pipeline.closed)
        registry.close()
    }

    @Test
    fun `semantic negotiation rejects and cleans candidate without publication`() {
        val registry = ShaderPipelineRegistry()
        val pipeline = pipeline(required = setOf(VertexSemantic.TANGENT))

        assertThrows<IllegalArgumentException> {
            registry.replace(terrain) { pipeline }
        }

        assertTrue(pipeline.closed)
        assertEquals(BuiltInWorldShaderPipeline.owner, registry.selection().owner)
        assertEquals(0L, registry.selection().generation)
        registry.close()
    }

    @Test
    fun `replacement retires old pipeline after active callback lease`() {
        val registry = ShaderPipelineRegistry()
        val first = pipeline(required = setOf(VertexSemantic.POSITION))
        registry.replace(terrain) { first }
        var closedDuringCallback = true

        registry.withPipeline {
            registry.replace(terrain) { pipeline(required = setOf(VertexSemantic.POSITION)) }
            closedDuringCallback = first.closed
        }

        assertFalse(closedDuringCallback)
        assertTrue(first.closed)
        registry.close()
    }

    private fun pipeline(required: Set<VertexSemantic>) = RecordingPipeline(
        ShaderPipelinePlan(
            owner = IrisShaderPackPlanner.OWNER,
            packName = "test-pack",
            fingerprint = "1".repeat(64),
            views = setOf(RenderViewId.MAIN),
            resources = RenderResourcePlan(emptyList(), emptyList()),
            programs = listOf(
                ShaderProgramSource(
                    name = "terrain",
                    phase = ShaderProgramPhase.TERRAIN,
                    vertex = "void main() {}",
                    fragment = "void main() {}",
                    uniforms = emptySet(),
                    samplers = emptySet(),
                ),
            ),
            requiredTerrainSemantics = required,
        ),
    )

    private class RecordingPipeline(
        override val plan: ShaderPipelinePlan,
    ) : WorldShaderPipeline {
        override val owner get() = plan.owner
        var closed = false

        override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
        override fun composite(fallback: FramebufferShader): FramebufferShader = fallback
        override fun close() {
            closed = true
        }
    }
}
