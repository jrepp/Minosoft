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

import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererManager
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.modding.loader.fabric.FabricClientEventPhase
import de.bixilon.minosoft.modding.loader.fabric.FabricClientEvents

class WorldRendererPipeline(val renderer: RendererManager) : Drawable {
    private val framebuffer = renderer.context.framebuffer.world
    private var elements: Array<PipelineElement> = emptyArray()


    private fun build(): Array<PipelineElement> {
        val list: MutableList<PipelineElement> = mutableListOf()
        for (renderer in renderer) {
            if (renderer !is WorldRenderer) continue
            list += renderer.layers.elements
            renderer.layers.elements.clear() // TODO: replace WorldRenderer::layers with unsafeNull()
        }

        return list.sorted().toTypedArray()
    }

    fun rebuild() {
        this.elements = build()
    }


    override fun draw() {
        framebuffer.bind()
        FabricClientEvents.dispatch(FabricClientEventPhase.BEFORE_WORLD_RENDER, renderer.context)
        var renderFailure: Throwable? = null
        try {
            for (element in elements) {
                element.draw(renderer.context)
            }
        } catch (throwable: Throwable) {
            renderFailure = throwable
            throw throwable
        } finally {
            try {
                FabricClientEvents.dispatch(FabricClientEventPhase.AFTER_WORLD_RENDER, renderer.context)
            } catch (cleanup: Throwable) {
                renderFailure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }
}
