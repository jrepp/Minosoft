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

import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrisShaderPackPlannerTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `plans paired terrain shadow composite programs headlessly`() {
        val shaders = temporary.resolve("pack/shaders").createDirectories()
        write(shaders, "lib/common.glsl", "uniform float frameTimeCounter;")
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                #include "lib/common.glsl"
                in vec3 vaPosition;
                in vec2 vaUV0;
                in vec4 vaColor;
                in ivec2 vaUV2;
                uniform int mc_Entity;
                void main() { gl_Position = vec4(vaPosition, 1.0); }
            """.trimIndent(),
        )
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform sampler2D gtexture;
                out vec4 color;
                void main() { color = texture(gtexture, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(shaders, "composite.fsh", SIMPLE_FRAGMENT)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("pack"))

        assertEquals(IrisShaderPackPlanner.OWNER, plan.owner)
        assertEquals(setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW), plan.views)
        assertEquals(
            setOf(ShaderProgramPhase.TERRAIN, ShaderProgramPhase.SHADOW, ShaderProgramPhase.COMPOSITE),
            plan.programs.mapTo(mutableSetOf(), ShaderProgramSource::phase),
        )
        assertTrue(VertexSemantic.BLOCK_ID in plan.requiredTerrainSemantics)
        assertTrue(plan.programs.single { it.phase == ShaderProgramPhase.TERRAIN }.samplers.contains("gtexture"))
        assertTrue(plan.fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(2, plan.resources.targets.size)
    }

    @Test
    fun `missing composite rejects candidate`() {
        val shaders = temporary.resolve("missing/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)

        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("missing"))
        }
    }

    @Test
    fun `include cycles reject candidate`() {
        val shaders = temporary.resolve("cycle/shaders").createDirectories()
        write(shaders, "a.glsl", "#include \"b.glsl\"")
        write(shaders, "b.glsl", "#include \"a.glsl\"")
        write(shaders, "gbuffers_terrain.vsh", "#version 330 core\n#include \"a.glsl\"\nvoid main() {}")
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("cycle"))
        }
    }

    @Test
    fun `discovers bounded define options and applies validated overrides`() {
        val shaders = temporary.resolve("options/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                #define SHADOW_QUALITY 1 // [1 2 4]
                //#define WAVING_LEAVES
                void main() { gl_Position = vec4(float(SHADOW_QUALITY)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        val pack = temporary.resolve("options")

        assertEquals(
            listOf(
                ShaderPackOption("SHADOW_QUALITY", "1", listOf("1", "2", "4")),
                ShaderPackOption("WAVING_LEAVES", "false", listOf("false", "true")),
            ),
            IrisShaderPackPlanner.options(pack),
        )
        val plan = IrisShaderPackPlanner.plan(pack, mapOf("SHADOW_QUALITY" to "4", "WAVING_LEAVES" to "true"))
        val vertex = plan.programs.single { it.phase == ShaderProgramPhase.TERRAIN }.vertex
        assertTrue("#define SHADOW_QUALITY 4" in vertex)
        assertTrue("#define WAVING_LEAVES" in vertex)
        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(pack, mapOf("SHADOW_QUALITY" to "8"))
        }
    }

    @Test
    fun `project reference pack has stable executable contract`() {
        val plan = IrisShaderPackPlanner.plan(
            Path.of("src/integration-test/resources/render_substrate/iris-reference"),
        )

        assertEquals("iris-reference", plan.packName)
        assertEquals("1e7f90799bac295700a14c43021bda4d4e6cff9667df06ad25e729fce59048f6", plan.fingerprint)
        assertEquals(setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW), plan.views)
        assertEquals(
            setOf(ShaderProgramPhase.TERRAIN, ShaderProgramPhase.SHADOW, ShaderProgramPhase.FINAL),
            plan.programs.mapTo(mutableSetOf(), ShaderProgramSource::phase),
        )
        assertEquals(
            setOf(
                VertexSemantic.POSITION,
                VertexSemantic.TEXTURE_COORDINATE,
                VertexSemantic.TEXTURE_LAYER,
                VertexSemantic.PACKED_LIGHT_COLOR,
            ),
            plan.requiredTerrainSemantics,
        )
    }

    private fun write(root: Path, relative: String, value: String) {
        val target = root.resolve(relative)
        target.parent.createDirectories()
        Files.writeString(target, value)
    }

    private companion object {
        const val SIMPLE_VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }"
        const val SIMPLE_FRAGMENT = "#version 330 core\nout vec4 color;\nvoid main() { color = vec4(1.0); }"
    }
}
