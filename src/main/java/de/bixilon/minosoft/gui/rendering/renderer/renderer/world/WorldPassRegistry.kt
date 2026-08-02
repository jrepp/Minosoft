/*
 * Minosoft
 * Copyright (C) 2020-2023 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.renderer.renderer.world

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.WorldRenderPass
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer

class WorldPassRegistry {
    val declarations: MutableList<WorldRenderPass> = mutableListOf()

    fun add(
        layer: RenderLayer,
        shader: Shader?,
        renderer: () -> Unit,
        semantic: PipelineSemantic,
        owner: (() -> RenderOwnerId)? = null,
        passId: RenderPassId,
        auxiliaryRenderers: Map<RenderViewId, () -> Unit> = emptyMap(),
        skip: (() -> Boolean)? = null,
    ) {
        declarations += WorldRenderPass(
            id = passId,
            layer = layer,
            shader = shader,
            renderer = renderer,
            semantic = semantic,
            owner = owner,
            auxiliaryViews = auxiliaryRenderers,
            skip = skip,
        )
    }
}
