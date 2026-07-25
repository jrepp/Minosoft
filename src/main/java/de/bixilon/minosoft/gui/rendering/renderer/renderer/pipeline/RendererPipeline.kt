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
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.graph.RenderGraphBuilder
import de.bixilon.minosoft.gui.rendering.graph.RenderGraphGeneration
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPass
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.graph.RenderPhase
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererManager
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineElement
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import de.bixilon.minosoft.gui.rendering.system.base.RenderSystem
import de.bixilon.minosoft.modding.loader.fabric.FabricClientEventPhase
import de.bixilon.minosoft.modding.loader.fabric.FabricClientEvents
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass

class RendererPipeline(private val renderer: RendererManager) : Drawable {
    private data class WorldElement(
        val id: RenderPassId,
        val element: PipelineElement,
    )

    private val other: MutableList<Drawable> = mutableListOf()
    private val system = renderer.context.system
    private val framebuffer = renderer.context.framebuffer

    private var worldElements: Array<PipelineElement> = emptyArray()
    private var graph = RenderGraphGeneration.empty<FrameGraphExecution>()

    val generation: RenderGraphGeneration<FrameGraphExecution> get() = graph
    val elements: List<PipelineElement> get() = worldElements.toList()

    private fun RenderSystem.set(renderer: Renderer) {
        val framebuffer = renderer.framebuffer
        if (framebuffer == null) {
            this.framebuffer = null
            this.polygonMode = PolygonModes.DEFAULT
        } else {
            framebuffer.bind()
        }
    }

    private fun buildWorldElements(): List<WorldElement> {
        val elements = mutableListOf<WorldElement>()
        for ((rendererIndex, candidate) in renderer.withIndex()) {
            if (candidate !is WorldRenderer) continue
            for ((elementIndex, element) in candidate.layers.elements.withIndex()) {
                elements += WorldElement(
                    id = element.passId ?: RenderPassId("minosoft:world/producer-$rendererIndex/layer-$elementIndex"),
                    element = element,
                )
            }
        }
        return elements.sortedWith(compareBy({ it.element.layer.priority }, WorldElement::id))
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
                    framebuffer.main.bind()
                    FabricClientEvents.dispatch(FabricClientEventPhase.BEFORE_WORLD_RENDER, execution.context)
                    execution.worldEventOpen = true
                },
            ),
        )
        val shaderPlan = renderer.context.shaderPipeline.plan()
        if (shaderPlan != null && IrisShaderPackPlanner.SHADOW_VIEW in shaderPlan.views) {
            builder.add(
                RenderPass(
                    id = RenderPassId("iris:shaderpack/shadow"),
                    owner = shaderPlan.owner,
                    view = IrisShaderPackPlanner.SHADOW_VIEW,
                    phase = RenderPhase.SHADOW,
                    draw = { execution ->
                        val chunks = requireNotNull(execution.context.renderer[ChunkRenderer]) {
                            "Iris shadow view requires the selected terrain renderer"
                        }
                        execution.context.shaderPipeline.withPipeline { pipeline ->
                            pipeline.renderView(IrisShaderPackPlanner.SHADOW_VIEW) {
                                chunks.terrain.submit(IrisShaderPackPlanner.SHADOW_VIEW, TerrainMaterialClass.OPAQUE)
                                chunks.terrain.submit(IrisShaderPackPlanner.SHADOW_VIEW, TerrainMaterialClass.CUTOUT)
                            }
                        }
                    },
                ),
            )
        }
        worldElements.forEach { (id, element) ->
            builder.add(
                RenderPass(
                    id = id,
                    owner = element.owner?.invoke() ?: BUILT_IN_WORLD_OWNER,
                    phase = element.phase(),
                    semantic = element.semantic.name.lowercase(),
                    order = element.layer.priority,
                    enabled = { element.isEnabled() },
                    draw = { execution -> element.drawEnabled(execution.context) },
                ),
            )
        }
        builder.add(
            RenderPass(
                id = RenderPassId("minosoft:frame/world-complete"),
                owner = SUBSTRATE_OWNER,
                phase = RenderPhase.WORLD_OVERLAY,
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

        this.worldElements = worldElements.map(WorldElement::element).toTypedArray()
        graph = builder.build()
    }

    override fun draw() {
        val execution = FrameGraphExecution(renderer.context)
        try {
            graph.execute(execution)
        } catch (failure: Throwable) {
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

    private fun Renderer.passId(group: String): RenderPassId {
        val producer = this::class.java.name
            .lowercase()
            .replace('.', '/')
            .replace('$', '-')
        return RenderPassId("minosoft:$group/$producer")
    }

    private fun PipelineElement.phase(): RenderPhase {
        when (semantic) {
            PipelineSemantic.SKY -> return RenderPhase.SKY
            PipelineSemantic.TERRAIN_OPAQUE,
            PipelineSemantic.TERRAIN_CUTOUT,
            PipelineSemantic.TERRAIN_EMISSIVE,
            -> return RenderPhase.WORLD_OPAQUE
            PipelineSemantic.TERRAIN_TRANSLUCENT -> return RenderPhase.WORLD_TRANSLUCENT
            PipelineSemantic.ENTITIES -> return RenderPhase.ENTITIES
            PipelineSemantic.BLOCK_ENTITIES -> return RenderPhase.BLOCK_ENTITIES
            PipelineSemantic.PARTICLES -> return RenderPhase.PARTICLES
            PipelineSemantic.WEATHER -> return RenderPhase.WEATHER
            PipelineSemantic.WORLD_OVERLAY -> return RenderPhase.WORLD_OVERLAY
            PipelineSemantic.AUTO -> {
                return if (layer.priority >= TRANSLUCENT_PRIORITY) {
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
        val BUILT_IN_SCENE_OWNER = RenderOwnerId("minosoft:built-in-scene")
    }
}

class FrameGraphExecution(
    val context: RenderContext,
) {
    internal var worldEventOpen = false
}
