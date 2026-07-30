/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationException
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.debug.ModDebugProvider
import de.bixilon.minosoft.debug.ModDebugRegistrar
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.framebuffer.IntegratedFramebuffer
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackend
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainInvalidationReason
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.TerrainSectionSnapshot
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

class SodiumRendererHook(
    override val context: RenderContext,
    private val controller: SodiumTerrainController,
) : Renderer {
    override val framebuffer: IntegratedFramebuffer? = null
    private lateinit var chunks: ChunkRenderer
    private var invoked = false

    override fun postInit(latch: AbstractLatch) {
        chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Sodium adapter requires Minosoft's chunk renderer." }
        controller.attach(context)
        chunks.limitChunkTransferTime = true
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "SODIUM_HOOK_INSTALLED hook=terrain-backend owner=${chunks.terrain.selection().owner} renderer=${chunks::class.java.name}"
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
        controller.detach(context)
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) { "SODIUM_HOOK_UNLOADED hook=terrain-backend" }
    }
}

class SodiumRendererHookBuilder(
    private val controller: SodiumTerrainController,
) : RendererBuilder<SodiumRendererHook> {
    override fun build(session: PlaySession, context: RenderContext) = SodiumRendererHook(context, controller)
}

class SodiumTerrainController : AutoCloseable {
    private val registrations = IdentityHashMap<RenderContext, AutoCloseable>()
    @Volatile private var enabled = true
    @Volatile private var closed = false

    @Synchronized
    fun attach(context: RenderContext): Boolean {
        check(!closed) { "Sodium terrain controller is closed" }
        if (!enabled) return false
        if (registrations.containsKey(context)) return true
        val chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Sodium adapter requires terrain" }
        registrations[context] = chunks.terrain.replace { SodiumTerrainBackend(chunks) }
        context.renderer.pipeline.rebuild()
        return true
    }

    @Synchronized
    fun setEnabled(context: RenderContext, enabled: Boolean): Boolean {
        check(!closed) { "Sodium terrain controller is closed" }
        this.enabled = enabled
        if (enabled) return attach(context)
        detach(context)
        return false
    }

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun isInstalled(context: RenderContext): Boolean = registrations.containsKey(context)

    @Synchronized
    fun detach(context: RenderContext) {
        registrations.remove(context)?.close()
        if (context.state != RenderingStates.QUITTING && context.state != RenderingStates.STOPPED) {
            context.renderer.pipeline.rebuild()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        registrations.values.forEach(AutoCloseable::close)
        registrations.clear()
    }
}

class SodiumDebugProvider(
    private val terrain: SodiumTerrainController,
) : ModDebugProvider {
    override fun modId() = "sodium"
    override fun providerVersion() = "fabric-adapter-v2"

    override fun register(registrar: ModDebugRegistrar) {
        registrar.operation("terrain") { _, body ->
            val enabled = if (body.has("enabled")) {
                if (!body.path("enabled").isBoolean) {
                    throw DebugOperationException("invalid_request", "enabled must be a boolean")
                }
                body.path("enabled").booleanValue()
            } else {
                terrain.isEnabled()
            }
            val context = activeRenderContext()
            val future = CompletableFuture<DebugOperationResult>()
            context.queue += {
                try {
                    val installed = terrain.setEnabled(context, enabled)
                    val selection = requireNotNull(context.renderer[ChunkRenderer]).terrain.selection()
                    future.complete(
                        DebugOperationResult.json(
                            DebugJson.MAPPER.createObjectNode()
                                .put("enabled", terrain.isEnabled())
                                .put("installed", installed)
                                .put("terrainOwner", selection.owner.value)
                                .put("terrainGeneration", selection.generation)
                                .put("frame", context.frameNumber),
                        ),
                    )
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            future
        }
    }

    private fun activeRenderContext(): RenderContext {
        val active = PlaySession.collectSessions().mapNotNull { session ->
            session.rendering?.context?.takeIf { it.state.active }
        }
        if (active.size != 1) {
            throw DebugOperationException("not_ready", "Sodium terrain control requires exactly one active render session")
        }
        return active.single()
    }
}

private class SodiumTerrainBackend(
    private val chunks: ChunkRenderer,
) : TerrainBackend {
    override val descriptor = TerrainBackendDescriptor(
        owner = OWNER,
        implementation = "sodium-0.5.8-adapter-minosoft-core",
        materials = TerrainMaterialClass.entries.toSet(),
        vertexLayout = BuiltInTerrainVertexLayout.VALUE,
        supportsAuxiliaryViews = true,
    )
    private var closed = false

    override fun prepare() {
        check(!closed) { "Sodium terrain backend is closed" }
        chunks.prepareTerrainCore()
    }

    override fun finishPreparation() {
        check(!closed) { "Sodium terrain backend is closed" }
        chunks.finishTerrainPreparationCore()
    }

    override fun submit(view: RenderViewId, material: TerrainMaterialClass) {
        check(!closed) { "Sodium terrain backend is closed" }
        chunks.submitTerrainCore(view, material)
    }

    override fun finishFrame() {
        check(!closed) { "Sodium terrain backend is closed" }
        chunks.finishTerrainFrameCore()
    }

    override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) {
        check(!closed) { "Sodium terrain backend is closed" }
        chunks.invalidate(snapshot.position)
    }

    override fun close() {
        closed = true
    }

    companion object {
        val OWNER = RenderOwnerId("minosoft:sodium-compatible-terrain")
    }
}
