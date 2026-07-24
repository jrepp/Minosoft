/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.framebuffer.IntegratedFramebuffer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

class SodiumRendererHook private constructor(
    override val context: RenderContext,
) : Renderer {
    override val framebuffer: IntegratedFramebuffer? = null
    private lateinit var chunks: ChunkRenderer
    private var invoked = false

    override fun postInit(latch: AbstractLatch) {
        chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Sodium adapter requires Minosoft's chunk renderer." }
        chunks.limitChunkTransferTime = true
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "SODIUM_HOOK_INSTALLED hook=chunk-scheduling renderer=${chunks::class.java.name}"
        }
    }

    override fun prePrepareDraw() {
        val started = System.nanoTime()
        chunks.limitChunkTransferTime = true
        FabricModDiagnostics.hookInvoked(SodiumCompatibilityAdapter.id, "chunk-render-scheduling", System.nanoTime() - started)
        if (invoked) return
        invoked = true
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "SODIUM_HOOK_INVOKED hook=chunk-scheduling visible=${chunks.visibility.meshes.sizeString}"
        }
    }

    override fun unload() {
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) { "SODIUM_HOOK_UNLOADED hook=chunk-scheduling" }
    }

    companion object : RendererBuilder<SodiumRendererHook> {
        override fun build(session: PlaySession, context: RenderContext) = SodiumRendererHook(context)
    }
}
