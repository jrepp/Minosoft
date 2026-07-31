/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationException
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.debug.ModDebugProvider
import de.bixilon.minosoft.debug.ModDebugRegistrar
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.terrain.near.provider.TerrainProviderRendererBridge
import de.bixilon.minosoft.gui.rendering.terrain.near.provider.TerrainProviderRendererCallbacks
import de.bixilon.minosoft.gui.rendering.terrain.near.provider.TerrainProviderSelection
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.CompletableFuture

class SodiumRendererHookBuilder(
    private val selection: TerrainProviderSelection,
) : RendererBuilder<TerrainProviderRendererBridge> {
    override fun build(session: PlaySession, context: RenderContext): TerrainProviderRendererBridge {
        var invoked = false
        val callbacks = TerrainProviderRendererCallbacks(
            installed = { chunks ->
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "SODIUM_HOOK_INSTALLED hook=terrain-backend owner=${chunks.terrain.selection().owner} " +
                        "renderer=${chunks::class.java.name}"
                }
            },
            prepared = { chunks, nanos ->
                FabricModDiagnostics.hookInvoked(
                    SodiumCompatibilityAdapter.id,
                    "chunk-render-scheduling",
                    nanos,
                )
                if (!invoked) {
                    invoked = true
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                        "SODIUM_HOOK_INVOKED hook=chunk-scheduling visible=${chunks.visibility.meshes.sizeString}"
                    }
                }
            },
            unloaded = {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "SODIUM_HOOK_UNLOADED hook=terrain-backend"
                }
            },
        )
        return TerrainProviderRendererBridge(context, selection, callbacks)
    }
}

class SodiumDebugProvider(
    private val terrain: TerrainProviderSelection,
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
