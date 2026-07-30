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

package de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world

import de.bixilon.kutil.profiler.stack.StackedProfiler.Companion.invoke
import de.bixilon.kutil.reflection.ReflectionUtil.realName
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer

class PipelineElement(
    val layer: RenderLayer,
    val shader: Shader?,
    val renderer: () -> Unit,
    val skip: (() -> Boolean)?,
    val semantic: PipelineSemantic = PipelineSemantic.AUTO,
    val owner: (() -> RenderOwnerId)? = null,
    val passId: RenderPassId? = null,
    val auxiliaryRenderers: Map<RenderViewId, () -> Unit> = emptyMap(),
) : Comparable<PipelineElement> {

    fun draw(context: RenderContext) {
        if (!isEnabled()) return
        drawEnabled(context)
    }

    fun isEnabled(): Boolean = skip?.invoke() != true

    fun drawEnabled(context: RenderContext) {
        draw(context, renderer)
    }

    fun supports(view: RenderViewId): Boolean = view in auxiliaryRenderers

    fun draw(view: RenderViewId, context: RenderContext) {
        val renderer = auxiliaryRenderers[view] ?: return
        draw(context, renderer)
    }

    private fun draw(context: RenderContext, renderer: () -> Unit) {
        context.system.set(layer.settings)
        shader?.use()
        context.profiler(renderer::class.java.realName) { renderer.invoke() }
    }

    override fun compareTo(other: PipelineElement): Int {
        return layer.priority.compareTo(other.layer.priority)
    }
}

enum class PipelineSemantic {
    AUTO,
    SKY,
    TERRAIN_OPAQUE,
    TERRAIN_CUTOUT,
    TERRAIN_TRANSLUCENT,
    TERRAIN_EMISSIVE,
    DISTANT_TERRAIN,
    DISTANT_WATER,
    ENTITIES,
    ENTITIES_TRANSLUCENT,
    BLOCK_ENTITIES,
    BLOCK_ENTITIES_TRANSLUCENT,
    PARTICLES,
    PARTICLES_OPAQUE,
    PARTICLES_TRANSLUCENT,
    WEATHER,
    HAND,
    WORLD_OVERLAY,
}
