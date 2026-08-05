/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
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

import de.bixilon.kutil.profiler.stack.StackedProfiler.Companion.invoke
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.graph.RenderGraphBuilder
import de.bixilon.minosoft.gui.rendering.graph.RenderGraphGeneration
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPass
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.graph.RenderPhase
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererManager
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.WorldRenderPass
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferId
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderBufferKind
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderDepthSnapshot
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisParticleOrdering
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelinePlan
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderProgramPhase
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import de.bixilon.minosoft.gui.rendering.system.base.RenderSystem
import de.bixilon.minosoft.modding.loader.fabric.FabricClientEventPhase
import de.bixilon.minosoft.modding.loader.fabric.FabricClientEvents
import de.bixilon.minosoft.gui.rendering.terrain.scene.ProductionTerrainPipelineRegistry
import de.bixilon.minosoft.gui.rendering.terrain.scene.ProductionTerrainPipelineSelection

class RendererPipeline(private val renderer: RendererManager) : Drawable {
    private val other: MutableList<Drawable> = mutableListOf()
    private val system = renderer.context.system
    private val framebuffer = renderer.context.framebuffer

    private var worldElements: Array<WorldRenderPass> = emptyArray()
    private var graph = RenderGraphGeneration.empty<FrameGraphExecution>()
    private val terrainPipelines = ProductionTerrainPipelineRegistry(
        renderer.context,
        renderer,
        graphGeneration = { graph.number },
    )

    val generation: RenderGraphGeneration<FrameGraphExecution> get() = graph
    val elements: List<WorldRenderPass> get() = worldElements.toList()

    private fun RenderSystem.set(renderer: Renderer) {
        val framebuffer = renderer.framebuffer
        if (framebuffer == null) {
            this.framebuffer = null
            this.polygonMode = PolygonModes.DEFAULT
        } else {
            framebuffer.bind()
        }
    }

    private fun buildWorldElements(): List<WorldRenderPass> {
        val elements = mutableListOf<WorldRenderPass>()
        for (candidate in renderer) {
            if (candidate !is WorldRenderer) continue
            elements += candidate.passes.declarations
        }
        return elements.sortedWith(compareBy({ it.layer.priority }, WorldRenderPass::id))
    }

    fun rebuild() {
        val worldElements = buildWorldElements()
        val builder = RenderGraphBuilder<FrameGraphExecution>(graph.number + 1L)

        builder.add(
            RenderPass(
                id = RenderPassId("minosoft:frame/world-begin"),
                owner = SUBSTRATE_OWNER,
                phase = RenderPhase.FRAME_SETUP,
                draw = { execution ->
                    execution.context.shaderPipeline.withPipeline { pipeline ->
                        pipeline.bindViewTarget(RenderViewId.MAIN, framebuffer.main::bind)
                    }
                },
            ),
        )
        val shaderPlan = renderer.context.shaderPipeline.plan()
        fun addFullscreenPrograms(programPhase: ShaderProgramPhase, renderPhase: RenderPhase) {
            val hasGraphics = shaderPlan?.programs?.any { it.phase == programPhase } == true
            val hasCompute = shaderPlan?.computePrograms?.any { it.phase == programPhase } == true
            if (!hasGraphics && !hasCompute) return
            builder.add(
                RenderPass(
                    id = RenderPassId("iris:shaderpack/${programPhase.name.lowercase().replace('_', '-')}"),
                    owner = shaderPlan.owner,
                    phase = renderPhase,
                    draw = { execution ->
                        execution.context.shaderPipeline.withPipeline { pipeline ->
                            pipeline.executePrograms(programPhase, framebuffer.main.mesh::draw)
                        }
                    },
                ),
            )
        }
        addFullscreenPrograms(ShaderProgramPhase.BEGIN, RenderPhase.IRIS_BEGIN)
        if (shaderPlan != null && IrisShaderPackPlanner.SHADOW_VIEW in shaderPlan.views) {
            val shadowView = IrisShaderPackPlanner.SHADOW_VIEW
            val shadowBegin = RenderPassId("iris:shaderpack/shadow")
            val shadowDepth = RenderPassId("iris:depth/shadow-before-translucent")
            val shadowComplete = RenderPassId("iris:shaderpack/shadow-complete")
            builder.add(
                RenderPass(
                    id = shadowBegin,
                    owner = shaderPlan.owner,
                    view = shadowView,
                    phase = RenderPhase.SHADOW,
                    order = Int.MIN_VALUE,
                    draw = { execution ->
                        execution.beginView(shadowView)
                    },
                ),
            )
            val opaqueShadowPasses = linkedSetOf<RenderPassId>()
            val translucentShadowPasses = linkedSetOf<RenderPassId>()
            for (element in worldElements) {
                if (!element.supports(shadowView) || !shadowCasterEnabled(element.semantic, shaderPlan)) continue
                val passId = element.id.forView(shadowView)
                val translucentTerrain = element.semantic == PipelineSemantic.TERRAIN_TRANSLUCENT
                val dependencies = if (translucentTerrain) setOf(shadowDepth) else setOf(shadowBegin)
                builder.add(
                    RenderPass(
                        id = passId,
                        owner = element.owner?.invoke() ?: BUILT_IN_WORLD_OWNER,
                        view = shadowView,
                        phase = RenderPhase.SHADOW,
                        semantic = element.semantic.name.lowercase(),
                        order = if (translucentTerrain) Int.MAX_VALUE else element.layer.priority,
                        after = dependencies,
                        enabled = { element.isEnabled(shadowView) },
                        draw = { execution ->
                            execution.context.shaderPipeline.withScene(element.semantic) {
                                element.draw(shadowView, execution.context)
                            }
                        },
                    ),
                )
                if (translucentTerrain) translucentShadowPasses += passId else opaqueShadowPasses += passId
            }
            builder.add(
                RenderPass(
                    id = shadowDepth,
                    owner = shaderPlan.owner,
                    view = shadowView,
                    phase = RenderPhase.SHADOW,
                    order = Int.MAX_VALUE - 1,
                    after = opaqueShadowPasses.ifEmpty { setOf(shadowBegin) },
                    draw = { execution ->
                        execution.context.shaderPipeline.withPipeline { pipeline ->
                            pipeline.snapshotDepth(ShaderDepthSnapshot.SHADOW_BEFORE_TRANSLUCENT)
                        }
                    },
                ),
            )
            builder.add(
                RenderPass(
                    id = shadowComplete,
                    owner = shaderPlan.owner,
                    view = shadowView,
                    phase = RenderPhase.SHADOW,
                    order = Int.MAX_VALUE,
                    after = buildSet {
                        add(shadowDepth)
                        addAll(translucentShadowPasses)
                    },
                    draw = { execution -> execution.endView(shadowView) },
                ),
            )
        }
        addFullscreenPrograms(ShaderProgramPhase.SHADOW_COMPOSITE, RenderPhase.IRIS_SHADOW_COMPOSITE)
        addFullscreenPrograms(ShaderProgramPhase.PREPARE, RenderPhase.IRIS_PREPARE)
        addFullscreenPrograms(ShaderProgramPhase.DEFERRED, RenderPhase.IRIS_DEFERRED)
        addFullscreenPrograms(ShaderProgramPhase.COMPOSITE, RenderPhase.IRIS_COMPOSITE)
        if (shaderPlan?.buffers?.get(ShaderBufferId(ShaderBufferKind.DEPTHTEX, 1)) != null) {
            builder.add(
                RenderPass(
                    id = RenderPassId("iris:depth/before-translucent"),
                    owner = shaderPlan.owner,
                    phase = RenderPhase.DEPTH_BEFORE_TRANSLUCENT,
                    draw = { execution ->
                        execution.context.shaderPipeline.withPipeline { pipeline ->
                            pipeline.snapshotDepth(ShaderDepthSnapshot.BEFORE_TRANSLUCENT)
                        }
                    },
                ),
            )
        }
        if (shaderPlan?.buffers?.get(ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 1)) != null) {
            builder.add(
                RenderPass(
                    id = RenderPassId("iris:depth/distant-before-translucent"),
                    owner = shaderPlan.owner,
                    phase = RenderPhase.DISTANT_DEPTH_BEFORE_TRANSLUCENT,
                    draw = { execution ->
                        execution.context.shaderPipeline.withPipeline { pipeline ->
                            pipeline.snapshotDepth(ShaderDepthSnapshot.DISTANT_BEFORE_TRANSLUCENT)
                        }
                    },
                ),
            )
        }
        if (shaderPlan?.buffers?.get(ShaderBufferId(ShaderBufferKind.DEPTHTEX, 2)) != null) {
            builder.add(
                RenderPass(
                    id = RenderPassId("iris:depth/before-hand"),
                    owner = shaderPlan.owner,
                    phase = RenderPhase.HAND,
                    order = Int.MIN_VALUE,
                    draw = { execution ->
                        execution.context.shaderPipeline.withPipeline { pipeline ->
                            pipeline.snapshotDepth(ShaderDepthSnapshot.BEFORE_HAND)
                        }
                    },
                ),
            )
        }
        worldElements.forEach { element ->
            builder.add(
                RenderPass(
                    id = element.id,
                    owner = element.owner?.invoke() ?: BUILT_IN_WORLD_OWNER,
                    phase = element.phase(shaderPlan),
                    semantic = element.semantic.name.lowercase(),
                    order = element.layer.priority,
                    enabled = {
                        element.isEnabled(RenderViewId.MAIN) && mainGeometryEnabled(element.semantic, shaderPlan)
                    },
                    draw = { execution ->
                        execution.context.shaderPipeline.withScene(element.semantic) {
                            element.draw(RenderViewId.MAIN, execution.context)
                        }
                    },
                ),
            )
        }
        listOf(
            PipelineSemantic.WEATHER to RenderPassId("minosoft:scene/weather-overlay"),
            PipelineSemantic.WORLD_OVERLAY to RenderPassId("minosoft:scene/world-overlays"),
        ).forEach { (semantic, id) ->
            builder.add(
                RenderPass(
                    id = id,
                    owner = BUILT_IN_OVERLAY_OWNER,
                    phase = semantic.phase(),
                    semantic = semantic.name.lowercase(),
                    draw = { execution ->
                        execution.context.shaderPipeline.withScene(semantic) {
                            framebuffer.main.overlay.draw(semantic)
                        }
                    },
                ),
            )
        }
        builder.add(
            RenderPass(
                id = RenderPassId("minosoft:frame/world-complete"),
                owner = SUBSTRATE_OWNER,
                phase = RenderPhase.IRIS_COMPOSITE,
                order = Int.MAX_VALUE,
                draw = { execution ->
                    execution.worldEventOpen = false
                    FabricClientEvents.dispatch(FabricClientEventPhase.AFTER_WORLD_RENDER, execution.context)
                },
            ),
        )
        other.forEachIndexed { index, drawable ->
            val candidate = drawable as Renderer
            builder.add(
                RenderPass(
                    id = candidate.passId("hud"),
                    owner = BUILT_IN_SCENE_OWNER,
                    phase = RenderPhase.HUD,
                    order = index,
                    enabled = { !candidate.skip },
                    draw = {
                        system.set(candidate)
                        drawable.draw()
                    },
                ),
            )
        }
        builder.add(
            RenderPass(
                id = RenderPassId("minosoft:presentation/composite"),
                owner = renderer.context.shaderPipeline.selection().owner,
                phase = RenderPhase.COMPOSITE,
                draw = { framebuffer.draw() },
            ),
        )

        this.worldElements = worldElements.toTypedArray()
        graph = builder.build()
    }

    override fun draw() = draw({}, {})

    /**
     * Runs producer preparation inside the same frame-pinned shader
     * generation that executes the graph. This is required for asynchronous
     * entity/chunk collectors to consume the exact shadow-culling snapshot
     * selected after BEFORE_WORLD_RENDER reload callbacks.
     */
    fun draw(prepare: () -> Unit, complete: () -> Unit) {
        val execution = FrameGraphExecution(renderer.context)
        // Dimension and shader-pack reload consumers must publish a complete
        // candidate before this frame acquires and pins its pipeline lease.
        FabricClientEvents.dispatch(FabricClientEventPhase.BEFORE_WORLD_RENDER, execution.context)
        execution.worldEventOpen = true
        try {
            terrainPipelines.withFrame(prepare) {
                graph.execute(execution) { pass, frame ->
                    // Iris fullscreen programs own finer-grained timers inside the aggregate graph pass.
                    if (pass.id.value.startsWith("iris:shaderpack/")) {
                        pass.draw(frame)
                    } else {
                        system.measureGpuPass("graph:${pass.id.value}") { pass.draw(frame) }
                    }
                }
                complete()
            }
        } catch (failure: Throwable) {
            try {
                execution.closeView()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            if (execution.worldEventOpen) {
                execution.worldEventOpen = false
                try {
                    FabricClientEvents.dispatch(FabricClientEventPhase.AFTER_WORLD_RENDER, execution.context)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    operator fun plusAssign(renderer: Renderer) {
        if (renderer is Drawable) {
            other += renderer
        }
        rebuild()
    }

    fun terrainSelection(): ProductionTerrainPipelineSelection = terrainPipelines.selection()

    fun close() = terrainPipelines.close()

    private fun Renderer.passId(group: String): RenderPassId {
        val producer = this::class.java.name
            .lowercase()
            .replace('.', '/')
            .replace('$', '-')
        return RenderPassId("minosoft:$group/$producer")
    }

    private fun RenderPassId.forView(view: RenderViewId): RenderPassId {
        val viewName = view.value.substringAfter(':').replace('/', '-')
        return RenderPassId("$value/$viewName")
    }

    private fun WorldRenderPass.phase(shaderPlan: ShaderPipelinePlan?): RenderPhase {
        return semantic.phase(layer.priority, shaderPlan)
    }

    private fun PipelineSemantic.phase(
        priority: Int = 0,
        shaderPlan: ShaderPipelinePlan? = null,
    ): RenderPhase {
        when (this) {
            PipelineSemantic.SKY -> return RenderPhase.SKY
            PipelineSemantic.TERRAIN_OPAQUE,
            PipelineSemantic.TERRAIN_CUTOUT,
            PipelineSemantic.TERRAIN_EMISSIVE,
            -> return RenderPhase.WORLD_OPAQUE
            PipelineSemantic.TERRAIN_TRANSLUCENT -> return RenderPhase.WORLD_TRANSLUCENT
            PipelineSemantic.DISTANT_TERRAIN -> return RenderPhase.DISTANT_TERRAIN
            PipelineSemantic.DISTANT_WATER -> return RenderPhase.DISTANT_WATER
            PipelineSemantic.ENTITIES -> return RenderPhase.ENTITIES
            PipelineSemantic.ENTITIES_TRANSLUCENT -> {
                return if (shaderPlan?.separateEntityDraws == true) {
                    RenderPhase.ENTITIES_TRANSLUCENT
                } else {
                    RenderPhase.ENTITIES_TRANSLUCENT_BEFORE_DEFERRED
                }
            }
            PipelineSemantic.BLOCK_ENTITIES -> return RenderPhase.BLOCK_ENTITIES
            PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT -> {
                return if (shaderPlan?.separateEntityDraws == true) {
                    RenderPhase.BLOCK_ENTITIES_TRANSLUCENT
                } else {
                    RenderPhase.BLOCK_ENTITIES_TRANSLUCENT_BEFORE_DEFERRED
                }
            }
            PipelineSemantic.PARTICLES_OPAQUE -> {
                val ordering = shaderPlan?.particlesOrdering ?: IrisParticleOrdering.AFTER
                return when (ordering) {
                    IrisParticleOrdering.BEFORE,
                    IrisParticleOrdering.MIXED,
                    -> RenderPhase.PARTICLES_OPAQUE_BEFORE_DEFERRED
                    IrisParticleOrdering.AFTER -> RenderPhase.PARTICLES
                }
            }
            PipelineSemantic.PARTICLES,
            PipelineSemantic.PARTICLES_TRANSLUCENT,
            -> {
                val ordering = shaderPlan?.particlesOrdering ?: IrisParticleOrdering.AFTER
                return if (ordering == IrisParticleOrdering.BEFORE) {
                    RenderPhase.PARTICLES_TRANSLUCENT_BEFORE_DEFERRED
                } else {
                    RenderPhase.PARTICLES
                }
            }
            PipelineSemantic.WEATHER -> return RenderPhase.WEATHER
            PipelineSemantic.HAND -> return RenderPhase.HAND
            PipelineSemantic.WORLD_OVERLAY -> return RenderPhase.WORLD_OVERLAY
            PipelineSemantic.AUTO -> {
                return if (priority >= TRANSLUCENT_PRIORITY) {
                    RenderPhase.WORLD_TRANSLUCENT
                } else {
                    RenderPhase.WORLD_OPAQUE
                }
            }
        }
    }

    companion object {
        private const val TRANSLUCENT_PRIORITY = 2000
        val SUBSTRATE_OWNER = RenderOwnerId("minosoft:render-substrate")
        val BUILT_IN_WORLD_OWNER = RenderOwnerId("minosoft:built-in-world")
        val BUILT_IN_OVERLAY_OWNER = RenderOwnerId("minosoft:built-in-overlays")
        val BUILT_IN_SCENE_OWNER = RenderOwnerId("minosoft:built-in-scene")
        fun shadowCasterEnabled(
            semantic: PipelineSemantic,
            plan: ShaderPipelinePlan,
        ): Boolean = when (semantic) {
            PipelineSemantic.TERRAIN_OPAQUE,
            PipelineSemantic.TERRAIN_CUTOUT,
            -> plan.shadowDirectives.terrain
            PipelineSemantic.TERRAIN_TRANSLUCENT ->
                plan.shadowDirectives.terrain && plan.shadowDirectives.translucentTerrain
            PipelineSemantic.ENTITIES,
            PipelineSemantic.ENTITIES_TRANSLUCENT,
            -> plan.shadowDirectives.entities || plan.shadowDirectives.player
            PipelineSemantic.BLOCK_ENTITIES ->
                plan.shadowDirectives.blockEntities || plan.shadowDirectives.lightBlockEntities
            PipelineSemantic.DISTANT_TERRAIN -> plan.shadowDirectives.terrain &&
                plan.programs.any { it.phase == ShaderProgramPhase.SHADOW && it.name == "dh_shadow" }
            else -> false
        }

        fun mainGeometryEnabled(
            semantic: PipelineSemantic,
            plan: ShaderPipelinePlan?,
        ): Boolean {
            if (plan?.skipAllRendering != true) return true
            return semantic !in SKIPPED_MAIN_GEOMETRY
        }

        private val SKIPPED_MAIN_GEOMETRY = setOf(
            PipelineSemantic.TERRAIN_OPAQUE,
            PipelineSemantic.TERRAIN_CUTOUT,
            PipelineSemantic.TERRAIN_TRANSLUCENT,
            PipelineSemantic.TERRAIN_EMISSIVE,
            PipelineSemantic.DISTANT_TERRAIN,
            PipelineSemantic.DISTANT_WATER,
            PipelineSemantic.ENTITIES,
            PipelineSemantic.ENTITIES_TRANSLUCENT,
            PipelineSemantic.BLOCK_ENTITIES,
            PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
        )
    }
}

class FrameGraphExecution(
    val context: RenderContext,
) {
    internal var worldEventOpen = false
    private var activeView: RenderViewId? = null

    internal fun beginView(view: RenderViewId) {
        check(activeView == null) { "Render view $activeView is already active" }
        context.shaderPipeline.withPipeline { it.beginView(view) }
        activeView = view
    }

    internal fun endView(view: RenderViewId) {
        check(activeView == view) { "Render view $view is not active" }
        try {
            context.shaderPipeline.withPipeline { it.endView(view) }
        } finally {
            activeView = null
        }
    }

    internal fun closeView() {
        val view = activeView ?: return
        endView(view)
    }
}
