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

package de.bixilon.minosoft.gui.rendering.terrain.near.provider

import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.framebuffer.IntegratedFramebuffer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer

class TerrainProviderRendererCallbacks(
    val installed: (ChunkRenderer) -> Unit = {},
    val prepared: (ChunkRenderer, Long) -> Unit = { _, _ -> },
    val unloaded: () -> Unit = {},
)

/** Provider-neutral renderer lifecycle bridge for terrain profile selection. */
class TerrainProviderRendererBridge(
    override val context: RenderContext,
    private val selection: TerrainProviderSelection,
    private val callbacks: TerrainProviderRendererCallbacks = TerrainProviderRendererCallbacks(),
) : Renderer {
    override val framebuffer: IntegratedFramebuffer? = null
    private lateinit var chunks: ChunkRenderer

    override fun postInit(latch: AbstractLatch) {
        chunks = requireNotNull(context.renderer[ChunkRenderer]) {
            "Terrain provider bridge requires Minosoft's chunk renderer."
        }
        selection.attach(context)
        chunks.limitChunkTransferTime = true
        callbacks.installed(chunks)
    }

    override fun prePrepareDraw() {
        val started = System.nanoTime()
        chunks.limitChunkTransferTime = true
        callbacks.prepared(chunks, System.nanoTime() - started)
    }

    override fun unload() {
        var failure: Throwable? = null
        try {
            selection.detach(context)
        } catch (error: Throwable) {
            failure = error
        }
        try {
            callbacks.unloaded()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        if (failure != null) throw failure
    }
}
