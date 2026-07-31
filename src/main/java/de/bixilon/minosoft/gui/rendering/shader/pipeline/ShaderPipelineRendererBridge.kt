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

import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.IntegratedFramebuffer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer

class ShaderPipelineRendererCallbacks(
    val installed: () -> Unit,
    val unloaded: () -> Unit,
)

/** Renderer-neutral lifecycle shell for an optional shader-pipeline presentation. */
class ShaderPipelineRendererBridge(
    override val context: RenderContext,
    private val callbacks: ShaderPipelineRendererCallbacks,
) : Renderer {
    override val framebuffer: IntegratedFramebuffer? = null

    override fun postInit(latch: AbstractLatch) = callbacks.installed()

    override fun unload() = callbacks.unloaded()
}
