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
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.ShaderPipelineScope
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
        assertEquals(pipeline.plan.fingerprint, registry.selection().fingerprint)
        registration.close()
        registration.close()
        assertEquals(BuiltInWorldShaderPipeline.owner, registry.selection().owner)
        assertTrue(pipeline.closed)
        registry.close()
    }

    @Test
    fun `semantic negotiation rejects and cleans candidate without publication`() {
        val registry = ShaderPipelineRegistry()
        val pipeline = pipeline(required = setOf(VertexSemantic.MATERIAL_ID))

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

    @Test
    fun `frame pins one pipeline generation across replacement and nested lookups`() {
        val registry = ShaderPipelineRegistry()
        val first = pipeline(required = setOf(VertexSemantic.POSITION))
        registry.replace(terrain) { first }
        val observed = mutableListOf<WorldShaderPipeline>()
        lateinit var second: RecordingPipeline

        registry.withFramePipeline { frame ->
            observed += frame
            assertEquals(1, registry.stats().activeLeases)
            second = pipeline(required = setOf(VertexSemantic.POSITION))
            registry.replace(terrain) { second }
            registry.withPipeline {
                observed += it
                assertEquals(0, registry.stats().activeLeases)
                assertEquals(1, registry.stats().retiredAwaitingLeases)
            }
            assertFalse(first.closed)
        }

        registry.withPipeline { observed += it }
        assertEquals(listOf<WorldShaderPipeline>(first, first, second), observed)
        assertTrue(first.closed)
        assertFalse(second.closed)
        registry.close()
    }

    @Test
    fun `failed frame clears thread binding and releases generation`() {
        val registry = ShaderPipelineRegistry()
        val first = pipeline(required = setOf(VertexSemantic.POSITION))
        registry.replace(terrain) { first }

        assertThrows<IllegalStateException> {
            registry.withFramePipeline {
                throw IllegalStateException("frame failed")
            }
        }
        val second = pipeline(required = setOf(VertexSemantic.POSITION))
        registry.replace(terrain) { second }

        assertTrue(first.closed)
        registry.withFramePipeline { assertEquals(second, it) }
        registry.close()
    }

    @Test
    fun `graphics program requires paired tessellation stages`() {
        val base = pipeline(required = setOf(VertexSemantic.POSITION)).plan.programs.single()
        val control = "#version 400 core\nlayout(vertices = 3) out; void main() {}"
        val evaluation =
            "#version 400 core\nlayout(triangles) in; void main() { gl_Position = gl_in[0].gl_Position; }"

        val staged = base.copy(
            tessellationControl = control,
            tessellationEvaluation = evaluation,
            inspectionTessellationControl = control,
            inspectionTessellationEvaluation = evaluation,
            tessellationPatchVertices = 3,
        )

        assertEquals(control, staged.tessellationControl)
        assertEquals(evaluation, staged.tessellationEvaluation)
        assertThrows<IllegalArgumentException> {
            base.copy(tessellationControl = control)
        }
        assertThrows<IllegalArgumentException> {
            base.copy(inspectionTessellationEvaluation = evaluation)
        }
    }

    @Test
    fun `scene and internal target boundaries restore shader pack draw state`() {
        val registry = ShaderPipelineRegistry()
        val pipeline = pipeline(required = setOf(VertexSemantic.POSITION))
        registry.replace(terrain) { pipeline }

        registry.withScene(PipelineSemantic.ENTITIES) { }
        registry.withInternalTarget { }

        assertEquals(2, pipeline.hostStateRestores)
        registry.close()
    }

    @Test
    fun `failed scene restoration clears thread local state`() {
        val registry = ShaderPipelineRegistry()
        val pipeline = pipeline(required = setOf(VertexSemantic.POSITION))
        registry.replace(terrain) { pipeline }
        pipeline.failHostStateRestore = true

        assertThrows<IllegalStateException> {
            registry.withScene(PipelineSemantic.ENTITIES) { }
        }
        pipeline.failHostStateRestore = false
        registry.withScene(PipelineSemantic.ENTITIES) { }

        registry.close()
    }

    @Test
    fun `selected frame rejects scene geometry outside a semantic pass`() {
        val registry = ShaderPipelineRegistry()
        registry.replace(terrain) { pipeline(required = setOf(VertexSemantic.POSITION)) }

        registry.withFramePipeline {
            val failure = assertThrows<IllegalStateException> {
                registry.requireFrameSceneSemantic(ShaderPipelineScope.SCENE_GEOMETRY)
            }
            assertTrue(failure.message.orEmpty().contains("outside a semantic render pass"))

            registry.withScene(PipelineSemantic.ENTITIES) {
                registry.requireFrameSceneSemantic(ShaderPipelineScope.SCENE_GEOMETRY)
            }
            registry.withInternalTarget {
                registry.requireFrameSceneSemantic(ShaderPipelineScope.SCENE_GEOMETRY)
            }
            registry.requireFrameSceneSemantic(ShaderPipelineScope.INTERNAL_COMPOSITE)
        }

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
        var hostStateRestores = 0
        var failHostStateRestore = false

        override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
        override fun restoreHostState() {
            hostStateRestores++
            check(!failHostStateRestore) { "restore failed" }
        }
        override fun composite(fallback: FramebufferShader): FramebufferShader = fallback
        override fun close() {
            closed = true
        }
    }
}
