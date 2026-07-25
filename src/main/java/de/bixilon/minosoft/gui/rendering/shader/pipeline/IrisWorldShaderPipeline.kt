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

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.shader.ChunkShader
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetDescriptor
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.IntegratedBufferTypes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.Framebuffer
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.depth.DepthModes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.texture.TextureModes
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShaderSource
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass

class IrisWorldShaderPipeline private constructor(
    override val plan: ShaderPipelinePlan,
    private val terrain: ChunkShader,
    private val composite: FramebufferShader,
    private val shadow: Shader?,
    private val shadowTarget: Framebuffer?,
) : WorldShaderPipeline {
    override val owner get() = plan.owner
    private var closed = false

    override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) {
        check(!closed) { "Iris shader pipeline is closed" }
        if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
            requireNotNull(shadow) { "Shader pack ${plan.packName} has no shadow program" }.use()
        } else {
            terrain.use()
        }
    }

    override fun renderView(view: RenderViewId, draw: () -> Unit) {
        check(!closed) { "Iris shader pipeline is closed" }
        if (view != IrisShaderPackPlanner.SHADOW_VIEW) return draw()
        val target = requireNotNull(shadowTarget) { "Shader pack ${plan.packName} has no shadow target" }
        val context = terrain.native.context
        context.system.framebuffer = target
        context.system.clear(IntegratedBufferTypes.COLOR_BUFFER, IntegratedBufferTypes.DEPTH_BUFFER)
        try {
            draw()
        } finally {
            context.framebuffer.main.bind()
        }
    }

    override fun composite(fallback: FramebufferShader): FramebufferShader {
        check(!closed) { "Iris shader pipeline is closed" }
        return composite
    }

    override fun close() {
        if (closed) return
        closed = true
        (listOfNotNull(shadow) + composite + terrain).forEach { shader ->
            if (shader.native.loaded) shader.unload()
        }
        shadowTarget?.delete()
    }

    companion object {
        fun prepare(context: RenderContext, plan: ShaderPipelinePlan): IrisWorldShaderPipeline {
            val loaded = mutableListOf<Shader>()
            var shadowTarget: Framebuffer? = null
            try {
                val selectedPrograms = selectPrograms(plan)
                val shadowDescriptor = validateTargets(context, plan)
                val terrainSource = selectedPrograms.terrain
                val terrain = ChunkShader(context.native(terrainSource)).also {
                    it.load()
                    loaded += it
                }
                val compositeSource = selectedPrograms.composite
                val composite = FramebufferShader(context.native(compositeSource)).also {
                    it.load()
                    loaded += it
                }
                val shadowSource = selectedPrograms.shadow
                val shadow = shadowSource?.let { source ->
                    object : Shader(context.native(source)) {}.also {
                        it.load()
                        loaded += it
                    }
                }
                if (shadow != null) {
                    val size = requireNotNull(shadowDescriptor?.size as? RenderTargetSize.Fixed) {
                        "Iris shadow program requires a fixed shadow target"
                    }
                    shadowTarget = context.system.createFramebuffer(
                        Vec2i(size.width, size.height),
                        1.0f,
                        texture = TextureModes.NEAREST,
                        depth = DepthModes.DEPTH24,
                    ).also(Framebuffer::init)
                }
                return IrisWorldShaderPipeline(plan, terrain, composite, shadow, shadowTarget)
            } catch (failure: Throwable) {
                try {
                    shadowTarget?.delete()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                loaded.asReversed().forEach { shader ->
                    try {
                        if (shader.native.loaded) shader.unload()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }

        private data class SelectedPrograms(
            val terrain: ShaderProgramSource,
            val composite: ShaderProgramSource,
            val shadow: ShaderProgramSource?,
        )

        private fun selectPrograms(plan: ShaderPipelinePlan): SelectedPrograms {
            val terrain = plan.programs.filter { it.phase == ShaderProgramPhase.TERRAIN }
            val composites = plan.programs.filter {
                it.phase == ShaderProgramPhase.COMPOSITE || it.phase == ShaderProgramPhase.FINAL
            }
            val shadows = plan.programs.filter { it.phase == ShaderProgramPhase.SHADOW }
            val unsupported = plan.programs - terrain.toSet() - composites.toSet() - shadows.toSet()

            require(terrain.size == 1) {
                "Iris pipeline currently requires exactly one terrain program, found ${terrain.size}"
            }
            require(composites.size == 1) {
                "Iris pipeline currently requires exactly one composite/final program, found ${composites.size}"
            }
            require(shadows.size <= 1) {
                "Iris pipeline currently supports at most one shadow program, found ${shadows.size}"
            }
            require(unsupported.isEmpty()) {
                "Iris pipeline does not yet execute program phases: " +
                    unsupported.joinToString { "${it.name}=${it.phase}" }
            }
            return SelectedPrograms(terrain.single(), composites.single(), shadows.singleOrNull())
        }

        private fun validateTargets(context: RenderContext, plan: ShaderPipelinePlan): RenderTargetDescriptor? {
            val main = plan.resources.targets.singleOrNull { it.id == RenderResourceId("minosoft:main-world") }
                ?: throw IllegalArgumentException("Iris plan must declare the host main-world target")
            val host = context.framebuffer.main.descriptor
            require(main.size == host.size && main.samples == host.samples && main.depth == host.depth) {
                "Iris main target does not match the selected host target"
            }
            require(main.colorAttachments.size == 1 && main.colorAttachments.single().format == RenderColorFormat.RGBA8) {
                "Iris main target currently requires one RGBA8 color attachment"
            }

            val shadow = plan.resources.targets.singleOrNull { it.id == RenderResourceId("iris:shadow") }
            if (shadow == null) {
                require(IrisShaderPackPlanner.SHADOW_VIEW !in plan.views) {
                    "Iris shadow view has no declared target"
                }
                return null
            }
            require(IrisShaderPackPlanner.SHADOW_VIEW in plan.views) {
                "Iris shadow target is declared without a shadow view"
            }
            require(shadow.samples == 1 && shadow.depth == RenderDepthFormat.DEPTH24) {
                "Iris shadow target currently requires one sample and DEPTH24"
            }
            require(shadow.colorAttachments.size == 1 && shadow.colorAttachments.single().format == RenderColorFormat.RGBA8) {
                "Iris shadow target currently requires one RGBA8 color attachment"
            }
            require(shadow.size is RenderTargetSize.Fixed) { "Iris shadow target must have fixed dimensions" }
            return shadow
        }

        private fun RenderContext.native(program: ShaderProgramSource) = system.shader.create(
            vertex = NativeShaderSource(
                ResourceLocation("iris", "shaderpack/${program.name}.vsh"),
                program.vertex,
            ),
            fragment = NativeShaderSource(
                ResourceLocation("iris", "shaderpack/${program.name}.fsh"),
                program.fragment,
            ),
        )
    }
}
