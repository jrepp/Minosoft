/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.kutil.exception.Broken
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.camera.fog.FogManager
import de.bixilon.minosoft.gui.rendering.camera.fog.FogState
import de.bixilon.minosoft.gui.rendering.font.manager.FontManager
import de.bixilon.minosoft.gui.rendering.font.types.dummy.DummyFontType
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferManager
import de.bixilon.minosoft.gui.rendering.framebuffer.world.MainWorldTarget
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.graph.RenderPhase
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.gui.atlas.textures.CodeTexturePart
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererManager
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.LayerSettings
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisParticleOrdering
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShadowDirectives
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisComputeDispatch
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisComputeProgramSource
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferClearColor
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferDescriptor
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferFilter
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferFormat
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferId
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferKind
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferPlan
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelinePlan
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRegistry
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderProgramPhase
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderProgramResourceUsage
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderProgramSource
import de.bixilon.minosoft.gui.rendering.shader.pipeline.WorldShaderPipeline
import de.bixilon.minosoft.gui.rendering.shader.types.FogShader
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import de.bixilon.minosoft.gui.rendering.system.base.layer.OpaqueLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.TranslucentLayer
import de.bixilon.minosoft.gui.rendering.system.base.settings.RenderSettings
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.system.dummy.DummyRenderSystem
import de.bixilon.minosoft.gui.rendering.system.dummy.buffer.DummyFramebuffer
import de.bixilon.minosoft.gui.rendering.system.dummy.shader.DummyNativeShader
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTexture
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureLoader
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureManager
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.Assert.expectThrows
import org.testng.annotations.Test

@Test(groups = ["rendering"])
class RendererPipelineTest {
    fun construct() {
        manager()
        renderer()
    }

    fun `register single layer`() {
        val manager = manager()
        val renderer = manager.register(renderer())

        renderer.layers.register(OpaqueLayer, null, renderer::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 1)
        assertEquals(elements[0].layer, OpaqueLayer)
    }

    fun `register 2 but same layer, check insertion order`() {
        val manager = manager()
        val renderer = manager.register(renderer())

        renderer.layers.register(OpaqueLayer, null, renderer::run)
        renderer.layers.register(layer("abc", OpaqueLayer.priority), null, renderer::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 2)
        assertEquals(elements[0].layer, OpaqueLayer)
        assertEquals(elements[1].layer.priority, OpaqueLayer.priority)
    }

    fun `register 2 but different layers priority, inserted correct order`() {
        val manager = manager()
        val renderer = manager.register(renderer())

        renderer.layers.register(OpaqueLayer, null, renderer::run)
        renderer.layers.register(layer("abc", 10), null, renderer::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 2)
        assertEquals(elements[0].layer, OpaqueLayer)
        assertEquals(elements[1].layer.priority, 10)
    }

    fun `register 2 but different layers priority, wrong insertion order`() {
        val manager = manager()
        val renderer = manager.register(renderer())

        renderer.layers.register(layer("abc", 10), null, renderer::run)
        renderer.layers.register(OpaqueLayer, null, renderer::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 2)
        assertEquals(elements[0].layer, OpaqueLayer)
        assertEquals(elements[1].layer.priority, 10)
    }

    fun `register 4 but different layers`() {
        val manager = manager()
        val renderer = manager.register(renderer())

        renderer.layers.register(layer("a", 10), null, renderer::run)
        renderer.layers.register(OpaqueLayer, null, renderer::run)
        renderer.layers.register(layer("b", -10), null, renderer::run)
        renderer.layers.register(layer("c", -10), null, renderer::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 4)
        assertEquals(elements[0].layer.unsafeCast<Identified>().identifier.path, "b")
        assertEquals(elements[1].layer.unsafeCast<Identified>().identifier.path, "c")
        assertEquals(elements[2].layer, OpaqueLayer)
        assertEquals(elements[3].layer.unsafeCast<Identified>().identifier.path, "a")
    }

    fun `opaque transparent translucent`() {
        val manager = manager()
        val renderer = manager.register(renderer())

        renderer.layers.register(TransparentLayer, null, renderer::run)
        renderer.layers.register(TranslucentLayer, null, renderer::run)
        renderer.layers.register(OpaqueLayer, null, renderer::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 3)
        assertEquals(elements[0].layer, OpaqueLayer)
        assertEquals(elements[1].layer, TransparentLayer)
        assertEquals(elements[2].layer, TranslucentLayer)
    }

    fun `different renderer`() {
        val manager = manager()

        val first = manager.register(renderer())
        val second = manager.register(renderer())

        first.layers.register(OpaqueLayer, null, first::run)
        second.layers.register(OpaqueLayer, null, second::run)
        second.layers.register(TranslucentLayer, null, second::run)

        manager.pipeline.rebuild()

        val elements = manager.pipeline.elements
        assertEquals(elements.size, 3)
        assertEquals(elements[0].layer, OpaqueLayer)
        assertEquals(elements[1].layer, OpaqueLayer)
        assertEquals(elements[2].layer, TranslucentLayer)
    }

    fun `correct render draw order`() {
        val manager = manager()

        val list: MutableList<String> = mutableListOf()

        val first = manager.register(renderer())
        val second = manager.register(renderer())

        first.layers.register(OpaqueLayer, null, { list += "a" })
        second.layers.register(OpaqueLayer, null, { list += "b" })
        second.layers.register(TranslucentLayer, null, { list += "c" })

        manager.pipeline.rebuild()

        val execution = FrameGraphExecution(manager.context)
        manager.pipeline.generation.passes
            .filter { it.owner.value == "minosoft:built-in-world" }
            .forEach { it.draw(execution) }

        assertEquals(list, listOf("a", "b", "c"))
    }

    fun `rebuild publishes an immutable graph generation without consuming layers`() {
        val manager = manager()
        val renderer = manager.register(renderer())
        renderer.layers.register(OpaqueLayer, null, renderer::run)

        manager.pipeline.rebuild()
        val first = manager.pipeline.generation
        renderer.layers.register(TranslucentLayer, null, renderer::run)

        assertEquals(first.passes.count { it.owner.value == "minosoft:built-in-world" }, 1)
        assertEquals(manager.pipeline.generation.number, first.number)

        manager.pipeline.rebuild()

        assertEquals(manager.pipeline.generation.passes.count { it.owner.value == "minosoft:built-in-world" }, 2)
        assertEquals(manager.pipeline.generation.number, first.number + 1L)
        assertEquals(renderer.layers.elements.size, 2)
    }

    fun `scene pass routes layer and nested shader binds through selected pipeline`() {
        val manager = manager()
        val fallback = object : Shader(DummyNativeShader(manager.context)) {
            override val sceneContract = SceneShaderContract(
                SceneProgramFamily.BASIC,
                SceneVertexAbi.POSITION_COLOR,
                SceneStateAbi.COLOR,
            )
            var exposure by uniform("uExposure", 0.75f)
            var ignored by uniform("uIgnored", 0.0f)
        }
        val nested = object : Shader(DummyNativeShader(manager.context)) {
            override val sceneContract = SceneShaderContract(
                SceneProgramFamily.BASIC,
                SceneVertexAbi.POSITION_COLOR,
                SceneStateAbi.COLOR,
            )
        }
        val copiedUniforms = mutableListOf<Pair<String, Float>>()
        val selectedNative = object : DummyNativeShader(manager.context) {
            override fun setFloat(uniform: String, value: Float) {
                copiedUniforms += uniform to value
            }
        }
        val selected = object : Shader(selectedNative) {}
        val internalComposite = FramebufferShader(DummyNativeShader(manager.context))
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            fallback,
            renderer = {
                assertTrue(!fallback.acceptsUniform("uIgnored"))
                nested.use()
                assertSame(nested.uniformTarget(), selected.native)
                // A different host binding is now current. The setter must
                // select fallback before deciding whether this uniform is
                // accepted by the selected shader-pack program.
                fallback.ignored = 1.0f
                internalComposite.use()
            },
            semantic = PipelineSemantic.ENTITIES,
            passId = RenderPassId("minosoft:test/scene-routing"),
        )
        val routed = mutableListOf<Pair<PipelineSemantic, Shader>>()
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = pipelinePlan()

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) {
                error("Terrain binding is outside this scene-routing test")
            }

            override fun bindScene(
                semantic: PipelineSemantic,
                contract: SceneShaderContract,
                fallback: Shader,
            ): Shader {
                routed += semantic to fallback
                return selected
            }

            override fun sceneUniforms(fallback: Shader, selected: Shader) = setOf("uExposure")

            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        val terrain = TerrainBackendDescriptor(
            owner = RenderOwnerId("minosoft:test-terrain"),
            implementation = "test",
            materials = TerrainMaterialClass.entries.toSet(),
            vertexLayout = BuiltInTerrainVertexLayout.VALUE,
            supportsAuxiliaryViews = true,
        )
        manager.context.shaderPipeline.replace(terrain) { pipeline }
        manager.pipeline.rebuild()
        val pass = manager.pipeline.generation.passes.single { it.id.value == "minosoft:test/scene-routing" }

        manager.context.shaderPipeline.withFramePipeline {
            pass.draw(FrameGraphExecution(manager.context))
        }

        assertEquals(
            routed.map { it.first },
            listOf(PipelineSemantic.ENTITIES, PipelineSemantic.ENTITIES, PipelineSemantic.ENTITIES),
        )
        assertSame(routed[0].second, fallback)
        assertSame(routed[1].second, nested)
        assertSame(routed[2].second, fallback)
        assertEquals(listOf("uExposure" to 0.75f, "uExposure" to 0.75f), copiedUniforms)
        assertSame(manager.context.system.shader.shader, internalComposite)
    }

    fun `internal target keeps scene geometry on host shader and restores routing`() {
        val manager = manager()
        val fallbackWrites = mutableListOf<Float>()
        val fallback = object : Shader(object : DummyNativeShader(manager.context) {
            override fun setFloat(uniform: String, value: Float) {
                fallbackWrites += value
            }
        }) {
            override val sceneContract = SceneShaderContract(
                SceneProgramFamily.ENTITY,
                SceneVertexAbi.SKELETAL,
                SceneStateAbi.SKELETAL_TINTED,
            )
            var outline by uniform("uOutline", 0.0f)
        }
        val selectedWrites = mutableListOf<Float>()
        val selected = object : Shader(object : DummyNativeShader(manager.context) {
            override fun setFloat(uniform: String, value: Float) {
                selectedWrites += value
            }
        }) {}
        var routed = 0
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = pipelinePlan()

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) {
                error("Terrain binding is outside this internal-target test")
            }

            override fun bindScene(
                semantic: PipelineSemantic,
                contract: SceneShaderContract,
                fallback: Shader,
            ): Shader {
                routed++
                return selected
            }

            override fun sceneUniforms(fallback: Shader, selected: Shader) = setOf("uOutline")
            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.context.shaderPipeline.withFramePipeline {
            manager.context.shaderPipeline.withScene(PipelineSemantic.WORLD_OVERLAY) {
                fallback.use()
                assertSame(manager.context.system.shader.shader, selected)
                manager.context.shaderPipeline.withInternalTarget {
                    fallback.outline = 0.75f
                    fallback.use()
                    assertSame(manager.context.system.shader.shader, fallback)
                }
                fallback.use()
                assertSame(manager.context.system.shader.shader, selected)
            }
        }

        assertEquals(routed, 2)
        assertEquals(fallbackWrites, listOf(0.75f))
        assertEquals(selectedWrites, listOf(0.0f, 0.75f))
    }

    fun `retained fog synchronization uploads to the selected native program`() {
        val manager = manager()
        val fallbackWrites = mutableListOf<String>()
        val selectedWrites = mutableListOf<String>()
        fun recordingNative(writes: MutableList<String>) = object : DummyNativeShader(manager.context) {
            override fun setFloat(uniform: String, value: Float) {
                writes += uniform
            }

            override fun setUInt(uniform: String, value: Int) {
                writes += uniform
            }
        }
        val fogManager = FogManager::class.java.allocate().apply {
            this::state.forceSet(FogState())
        }
        val fallback = object : Shader(recordingNative(fallbackWrites)), FogShader {
            override var cameraPosition by cameraPosition()
            val retainedFog = fog(fogManager)
            override var fog: FogManager by retainedFog
        }

        fallback.retainedFog.uploadTo(recordingNative(selectedWrites))

        assertEquals(selectedWrites, listOf("uFogStart", "uFogDistance", "uFogFlags"))
        assertTrue(fallbackWrites.isEmpty())
    }

    fun `selected shader pipeline rejects unclassified scene geometry`() {
        val manager = manager()
        val unclassified = object : Shader(DummyNativeShader(manager.context)) {}
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            shader = null,
            renderer = unclassified::use,
            semantic = PipelineSemantic.ENTITIES,
            passId = RenderPassId("minosoft:test/unclassified-scene-shader"),
        )
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = pipelinePlan()

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) {
                error("Terrain binding is outside this scene-contract test")
            }

            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }
        manager.pipeline.rebuild()
        val pass = manager.pipeline.generation.passes.single {
            it.id == RenderPassId("minosoft:test/unclassified-scene-shader")
        }

        val failure = expectThrows(IllegalStateException::class.java) {
            manager.context.shaderPipeline.withFramePipeline {
                pass.draw(FrameGraphExecution(manager.context))
            }
        }

        assertTrue(failure.message!!.contains("has no render-pipeline contract"))
        assertTrue(failure.message!!.contains("scene-routing"))
    }

    fun `scene family override selects one specialized program without changing ABI`() {
        val manager = manager()
        val fallback = object : Shader(DummyNativeShader(manager.context)) {
            override val sceneContract = SceneShaderContract(
                SceneProgramFamily.ENTITY,
                SceneVertexAbi.SKELETAL,
                SceneStateAbi.SKELETAL_TINTED,
            )
        }
        val routed = mutableListOf<SceneShaderContract>()
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            shader = null,
            renderer = { fallback.withProgramFamily(SceneProgramFamily.ENTITY_EYES) { Unit } },
            semantic = PipelineSemantic.ENTITIES,
            passId = RenderPassId("minosoft:test/specialized-scene-routing"),
        )
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = pipelinePlan()

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) {
                error("Terrain binding is outside this specialized scene-routing test")
            }

            override fun bindScene(
                semantic: PipelineSemantic,
                contract: SceneShaderContract,
                fallback: Shader,
            ): Shader {
                assertEquals(PipelineSemantic.ENTITIES, semantic)
                routed += contract
                return fallback
            }

            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.pipeline.rebuild()
        val pass = manager.pipeline.generation.passes.single {
            it.id == RenderPassId("minosoft:test/specialized-scene-routing")
        }
        manager.context.shaderPipeline.withFramePipeline {
            pass.draw(FrameGraphExecution(manager.context))
        }

        assertEquals(
            listOf(
                fallback.sceneContract.copy(family = SceneProgramFamily.ENTITY_EYES),
            ),
            routed,
        )
        assertEquals(SceneProgramFamily.ENTITY, fallback.sceneContract.family)
    }

    fun `hand and overlays remain inside the world composite boundary`() {
        val manager = manager()
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.HAND,
            passId = RenderPassId("minosoft:test/hand"),
        )

        manager.pipeline.rebuild()
        val passes = manager.pipeline.generation.passes
        val positions = passes.mapIndexed { index, pass -> pass.id.value to index }.toMap()
        val hand = passes.single { it.id.value == "minosoft:test/hand" }
        val weather = passes.single { it.id.value == "minosoft:scene/weather-overlay" }
        val overlays = passes.single { it.id.value == "minosoft:scene/world-overlays" }

        assertEquals(hand.phase, RenderPhase.HAND)
        assertEquals(hand.semantic, "hand")
        assertEquals(weather.phase, RenderPhase.WEATHER)
        assertEquals(weather.semantic, "weather")
        assertEquals(overlays.phase, RenderPhase.WORLD_OVERLAY)
        assertEquals(overlays.semantic, "world_overlay")
        assertTrue(positions.getValue(hand.id.value) < positions.getValue(overlays.id.value))
        assertTrue(positions.getValue(weather.id.value) < positions.getValue(overlays.id.value))
        assertTrue(positions.getValue(overlays.id.value) < positions.getValue("minosoft:frame/world-complete"))
        assertTrue(
            positions.getValue("minosoft:frame/world-complete") <
                positions.getValue("minosoft:presentation/composite"),
        )
    }

    fun `Iris fullscreen families occupy their ordered graph boundaries`() {
        val manager = manager()
        val phases = listOf(
            ShaderProgramPhase.BEGIN,
            ShaderProgramPhase.SHADOW_COMPOSITE,
            ShaderProgramPhase.PREPARE,
            ShaderProgramPhase.DEFERRED,
            ShaderProgramPhase.COMPOSITE,
        )
        val calls = mutableListOf<ShaderProgramPhase>()
        val base = pipelinePlan()
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = base.copy(
                programs = base.programs + phases.filterNot { it == ShaderProgramPhase.PREPARE }.map { phase ->
                    ShaderProgramSource(
                        name = phase.name.lowercase(),
                        phase = phase,
                        vertex = "void main() {}",
                        fragment = "void main() {}",
                        uniforms = emptySet(),
                        samplers = emptySet(),
                    )
                },
                computePrograms = listOf(
                    IrisComputeProgramSource(
                        name = "prepare",
                        phase = ShaderProgramPhase.PREPARE,
                        source = "void main() {}",
                        uniforms = emptySet(),
                        samplers = emptySet(),
                        dispatch = IrisComputeDispatch.Absolute(1, 1, 1),
                        resourceUsage = ShaderProgramResourceUsage(),
                    ),
                ),
            )

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
            override fun executePrograms(phase: ShaderProgramPhase, drawFullscreen: () -> Unit) {
                calls += phase
            }
            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.pipeline.rebuild()
        val irisPasses = manager.pipeline.generation.passes.filter { it.id.value.startsWith("iris:shaderpack/") }
        assertEquals(
            irisPasses.map { it.phase },
            listOf(
                RenderPhase.IRIS_BEGIN,
                RenderPhase.IRIS_SHADOW_COMPOSITE,
                RenderPhase.IRIS_PREPARE,
                RenderPhase.IRIS_DEFERRED,
                RenderPhase.IRIS_COMPOSITE,
            ),
        )
        manager.context.shaderPipeline.withFramePipeline {
            irisPasses.forEach { it.draw(FrameGraphExecution(manager.context)) }
        }
        assertEquals(calls, phases)
    }

    fun `Iris mixed particle ordering places only opaque particles before deferred`() {
        val manager = manager()
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.PARTICLES_OPAQUE,
            passId = RenderPassId("minosoft:test/particles-opaque"),
        )
        producer.layers.registerSemantic(
            TranslucentLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.PARTICLES_TRANSLUCENT,
            passId = RenderPassId("minosoft:test/particles-translucent"),
        )
        val base = pipelinePlan()
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = base.copy(
                particlesOrdering = IrisParticleOrdering.MIXED,
                programs = base.programs + ShaderProgramSource(
                    name = "deferred",
                    phase = ShaderProgramPhase.DEFERRED,
                    vertex = "void main() {}",
                    fragment = "void main() {}",
                    uniforms = emptySet(),
                    samplers = emptySet(),
                ),
            )

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.pipeline.rebuild()
        val passes = manager.pipeline.generation.passes.map { it.id }
        assertTrue(passes.indexOf(RenderPassId("minosoft:test/particles-opaque")) < passes.indexOf(RenderPassId("iris:shaderpack/deferred")))
        assertTrue(passes.indexOf(RenderPassId("iris:shaderpack/deferred")) < passes.indexOf(RenderPassId("minosoft:test/particles-translucent")))
    }

    fun `Iris separate entity draws move translucent producers after deferred`() {
        val manager = manager()
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.ENTITIES,
            passId = RenderPassId("minosoft:test/entities-opaque"),
        )
        producer.layers.registerSemantic(
            TranslucentLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.ENTITIES_TRANSLUCENT,
            passId = RenderPassId("minosoft:test/entities-translucent"),
        )
        producer.layers.registerSemantic(
            TranslucentLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
            passId = RenderPassId("minosoft:test/block-entities-translucent"),
        )
        val base = pipelinePlan()
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = base.copy(
                separateEntityDraws = true,
                programs = base.programs + ShaderProgramSource(
                    name = "deferred",
                    phase = ShaderProgramPhase.DEFERRED,
                    vertex = "void main() {}",
                    fragment = "void main() {}",
                    uniforms = emptySet(),
                    samplers = emptySet(),
                ),
            )

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.pipeline.rebuild()
        val passes = manager.pipeline.generation.passes
        val positions = passes.mapIndexed { index, pass -> pass.id to index }.toMap()
        val deferred = positions.getValue(RenderPassId("iris:shaderpack/deferred"))
        val opaque = passes.single { it.id == RenderPassId("minosoft:test/entities-opaque") }
        val entities = passes.single { it.id == RenderPassId("minosoft:test/entities-translucent") }
        val blocks = passes.single { it.id == RenderPassId("minosoft:test/block-entities-translucent") }

        assertEquals(opaque.phase, RenderPhase.ENTITIES)
        assertEquals(entities.phase, RenderPhase.ENTITIES_TRANSLUCENT)
        assertEquals(blocks.phase, RenderPhase.BLOCK_ENTITIES_TRANSLUCENT)
        assertTrue(positions.getValue(opaque.id) < deferred)
        assertTrue(deferred < positions.getValue(entities.id))
        assertTrue(positions.getValue(entities.id) < positions.getValue(blocks.id))
    }

    fun `Iris depthtex1 snapshot is a barrier before deferred and translucent producers`() {
        val manager = manager()
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        producer.layers.registerSemantic(
            OpaqueLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.ENTITIES,
            passId = RenderPassId("minosoft:test/depth-opaque"),
        )
        producer.layers.registerSemantic(
            TranslucentLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.ENTITIES_TRANSLUCENT,
            passId = RenderPassId("minosoft:test/depth-entities-translucent"),
        )
        producer.layers.registerSemantic(
            TranslucentLayer,
            shader = null,
            renderer = {},
            semantic = PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
            passId = RenderPassId("minosoft:test/depth-block-entities-translucent"),
        )
        val base = pipelinePlan()
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = base.copy(
                separateEntityDraws = true,
                buffers = ShaderBufferPlan(
                    listOf(
                        ShaderBufferDescriptor(
                            id = ShaderBufferId(ShaderBufferKind.DEPTHTEX, 1),
                            format = ShaderBufferFormat.Depth(RenderDepthFormat.DEPTH24),
                            size = RenderTargetSize.Relative(1.0f),
                            clear = RenderClearPolicy.CLEAR,
                            clearColor = ShaderBufferClearColor.Fog,
                            filter = ShaderBufferFilter.NEAREST,
                            mipmapped = false,
                            doubleBuffered = false,
                        ),
                    ),
                ),
                programs = base.programs + ShaderProgramSource(
                    name = "deferred",
                    phase = ShaderProgramPhase.DEFERRED,
                    vertex = "void main() {}",
                    fragment = "void main() {}",
                    uniforms = emptySet(),
                    samplers = emptySet(),
                ),
            )

            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.pipeline.rebuild()
        val positions = manager.pipeline.generation.passes
            .mapIndexed { index, pass -> pass.id to index }
            .toMap()
        val opaque = positions.getValue(RenderPassId("minosoft:test/depth-opaque"))
        val depth = positions.getValue(RenderPassId("iris:depth/before-translucent"))
        val deferred = positions.getValue(RenderPassId("iris:shaderpack/deferred"))
        val entities = positions.getValue(RenderPassId("minosoft:test/depth-entities-translucent"))
        val blocks = positions.getValue(RenderPassId("minosoft:test/depth-block-entities-translucent"))

        assertTrue(opaque < depth)
        assertTrue(depth < deferred)
        assertTrue(deferred < entities)
        assertTrue(entities < blocks)
    }

    fun `Iris skip all rendering suppresses only main geometry producers`() {
        val manager = manager()
        val producer = manager.register(object : WorldRenderer {
            override val context = manager.context
            override val layers = LayerSettings()
            override fun registerLayers() = Unit
        })
        val semantics = listOf(
            PipelineSemantic.TERRAIN_OPAQUE,
            PipelineSemantic.TERRAIN_CUTOUT,
            PipelineSemantic.TERRAIN_TRANSLUCENT,
            PipelineSemantic.TERRAIN_EMISSIVE,
            PipelineSemantic.ENTITIES,
            PipelineSemantic.ENTITIES_TRANSLUCENT,
            PipelineSemantic.BLOCK_ENTITIES,
            PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
            PipelineSemantic.PARTICLES_OPAQUE,
            PipelineSemantic.PARTICLES_TRANSLUCENT,
            PipelineSemantic.SKY,
            PipelineSemantic.WEATHER,
            PipelineSemantic.HAND,
            PipelineSemantic.WORLD_OVERLAY,
        )
        semantics.forEachIndexed { index, semantic ->
            producer.layers.registerSemantic(
                OpaqueLayer,
                shader = null,
                renderer = {},
                semantic = semantic,
                passId = RenderPassId("minosoft:test/skip-$index"),
            )
        }
        val base = pipelinePlan()
        val pipeline = object : WorldShaderPipeline {
            override val owner = IrisShaderPackPlanner.OWNER
            override val plan = base.copy(skipAllRendering = true)
            override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = Unit
            override fun composite(fallback: FramebufferShader) = fallback
            override fun close() = Unit
        }
        manager.context.shaderPipeline.replace(
            TerrainBackendDescriptor(
                owner = RenderOwnerId("minosoft:test-terrain"),
                implementation = "test",
                materials = TerrainMaterialClass.entries.toSet(),
                vertexLayout = BuiltInTerrainVertexLayout.VALUE,
                supportsAuxiliaryViews = true,
            ),
        ) { pipeline }

        manager.pipeline.rebuild()
        val execution = FrameGraphExecution(manager.context)
        val enabledBySemantic = semantics.mapIndexed { index, semantic ->
            semantic to manager.pipeline.generation.passes
                .single { it.id == RenderPassId("minosoft:test/skip-$index") }
                .enabled(execution)
        }.toMap()

        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.TERRAIN_OPAQUE))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.TERRAIN_CUTOUT))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.TERRAIN_TRANSLUCENT))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.TERRAIN_EMISSIVE))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.ENTITIES))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.ENTITIES_TRANSLUCENT))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.BLOCK_ENTITIES))
        assertTrue(!enabledBySemantic.getValue(PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT))
        assertTrue(enabledBySemantic.getValue(PipelineSemantic.PARTICLES_OPAQUE))
        assertTrue(enabledBySemantic.getValue(PipelineSemantic.PARTICLES_TRANSLUCENT))
        assertTrue(enabledBySemantic.getValue(PipelineSemantic.SKY))
        assertTrue(enabledBySemantic.getValue(PipelineSemantic.WEATHER))
        assertTrue(enabledBySemantic.getValue(PipelineSemantic.HAND))
        assertTrue(enabledBySemantic.getValue(PipelineSemantic.WORLD_OVERLAY))
        assertTrue(RendererPipeline.shadowCasterEnabled(PipelineSemantic.ENTITIES, pipeline.plan))
        assertTrue(RendererPipeline.shadowCasterEnabled(PipelineSemantic.BLOCK_ENTITIES, pipeline.plan))
    }

    fun `Iris shadow directives produce an exact caster submission plan`() {
        val plan = pipelinePlan().copy(
            shadowDirectives = IrisShadowDirectives(
                terrain = false,
                entities = true,
                blockEntities = false,
            ),
        )

        assertEquals(RendererPipeline.shadowTerrainMaterials(plan), emptyList<TerrainMaterialClass>())
        assertTrue(RendererPipeline.shadowCasterEnabled(PipelineSemantic.ENTITIES, plan))
        assertTrue(!RendererPipeline.shadowCasterEnabled(PipelineSemantic.BLOCK_ENTITIES, plan))
        assertTrue(!RendererPipeline.shadowCasterEnabled(PipelineSemantic.PARTICLES_OPAQUE, plan))

        val playerOnly = plan.copy(
            shadowDirectives = plan.shadowDirectives.copy(entities = false, player = true),
        )
        assertTrue(RendererPipeline.shadowCasterEnabled(PipelineSemantic.ENTITIES, playerOnly))

        val noEntities = plan.copy(
            shadowDirectives = plan.shadowDirectives.copy(entities = false, player = false),
        )
        assertTrue(!RendererPipeline.shadowCasterEnabled(PipelineSemantic.ENTITIES, noEntities))

        val lightBlockEntitiesOnly = plan.copy(
            shadowDirectives = plan.shadowDirectives.copy(
                blockEntities = false,
                lightBlockEntities = true,
            ),
        )
        assertTrue(RendererPipeline.shadowCasterEnabled(PipelineSemantic.BLOCK_ENTITIES, lightBlockEntitiesOnly))

        val terrainPlan = plan.copy(shadowDirectives = plan.shadowDirectives.copy(terrain = true))
        assertEquals(
            RendererPipeline.shadowTerrainMaterials(terrainPlan),
            listOf(
                TerrainMaterialClass.OPAQUE,
                TerrainMaterialClass.CUTOUT,
                TerrainMaterialClass.TRANSLUCENT,
            ),
        )
        assertEquals(
            RendererPipeline.shadowTerrainMaterials(
                terrainPlan.copy(
                    shadowDirectives = terrainPlan.shadowDirectives.copy(translucentTerrain = false),
                ),
            ),
            listOf(TerrainMaterialClass.OPAQUE, TerrainMaterialClass.CUTOUT),
        )
    }

    private fun context(): RenderContext {
        val context = RenderContext::class.java.allocate()
        context.font = FontManager(DummyFontType)
        context::thread.forceSet(Thread.currentThread())
        context::system.forceSet(DummyRenderSystem(context))
        context::textures.forceSet(DummyTextureManager(context))
        context::shaderPipeline.forceSet(ShaderPipelineRegistry())

        val framebuffer = FramebufferManager::class.java.allocate()
        framebuffer::main.forceSet(MainWorldTarget::class.java.allocate().apply {
            this::polygonMode.forceSet(PolygonModes.FILL)
            this::size.forceSet(Vec2i(1, 1)._0)
            this::scale.forceSet(1.0f)
            this::framebuffer.forceSet(DummyFramebuffer())

            this::context.forceSet(context)
        })
        context::framebuffer.forceSet(framebuffer)

        context.textures::whiteTexture.forceSet(CodeTexturePart(DummyTexture(), size = Vec2i(16, 16)))

        return context
    }


    private fun manager(): RendererManager {
        val context = context()
        return RendererManager(context)
    }

    private fun renderer() = object : WorldRenderer {
        override val context get() = Broken()
        override val framebuffer get() = null

        override val layers = LayerSettings()
        override fun registerLayers() = Unit


        fun run(): Unit = TODO()
    }

    private fun layer(name: String, priority: Int) = object : RenderLayer, Identified {
        override val identifier = minosoft(name)
        override val settings = RenderSettings.DEFAULT
        override val priority = priority
    }

    private fun pipelinePlan() = ShaderPipelinePlan(
        owner = IrisShaderPackPlanner.OWNER,
        packName = "scene-routing",
        fingerprint = "2".repeat(64),
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
        requiredTerrainSemantics = emptySet(),
    )

    private object TransparentLayer : RenderLayer {
        override val settings = OpaqueLayer.settings
        override val priority get() = 1000
    }
}
