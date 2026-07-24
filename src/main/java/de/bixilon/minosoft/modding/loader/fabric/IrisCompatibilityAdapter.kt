/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.debug.ClientDebugChannel
import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationException
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.debug.ModDebugProvider
import de.bixilon.minosoft.debug.ModDebugRegistrar
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

object IrisCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:iris-1.7.2-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(
        FabricHostCapability.SHADER_PIPELINE,
        FabricHostCapability.RESOURCE_RELOAD_EVENTS,
    )
    override val functionality = FabricFunctionalityCatalog.IRIS

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "iris" &&
            metadata.version == "1.7.2+mc1.20.4" &&
            metadata.environment == "client" &&
            metadata.entrypoints == setOf("modmenu") &&
            metadata.mixins == 9 &&
            metadata.accessWidener == "iris.accesswidener" &&
            metadata.nestedJars == 5
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Iris artifact: ${probe.metadata.version}" }
        val presentation = IrisPresentationController()
        scope.own(presentation)
        val invoked = AtomicBoolean()
        FabricModDiagnostics.hookInstalled(id, HOOK)
        scope.own(AutoCloseable { FabricModDiagnostics.hookUninstalled(id, HOOK) })
        FabricModDiagnostics.hookInstalled(id, PRESENTATION_HOOK)
        scope.own(AutoCloseable { FabricModDiagnostics.hookUninstalled(id, PRESENTATION_HOOK) })
        FabricModDiagnostics.hookInstalled(id, SHADER_RELOAD_HOOK)
        scope.own(AutoCloseable { FabricModDiagnostics.hookUninstalled(id, SHADER_RELOAD_HOOK) })
        scope.own(
            FabricClientEvents.register(id, FabricClientEventPhase.BEFORE_WORLD_RENDER) { context ->
                presentation.beforeWorldRender(context)
                FabricModDiagnostics.hookInvoked(id, HOOK, 0L)
                FabricModDiagnostics.hookInvoked(id, PRESENTATION_HOOK, 0L)
                if (invoked.compareAndSet(false, true)) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                        "IRIS_HOOK_INVOKED hook=$HOOK boundary=before-world-render presentation=world-post-process"
                    }
                }
            },
        )
        scope.own(
            FabricResourceReloadEvents.register(id, FabricResourceReloadPhase.COMPLETE) { context ->
                if (context.type == FabricResourceReloadType.SHADERS) {
                    FabricModDiagnostics.hookInvoked(id, SHADER_RELOAD_HOOK, 0L)
                }
            },
        )
        scope.own(ClientDebugChannel.register(IrisDebugProvider(presentation)))
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "IRIS_HOOK_INSTALLED hook=$HOOK presentation=world-post-process reload=shaders"
        }
    }

    private const val HOOK = "shader-pipeline"
    private const val PRESENTATION_HOOK = "world-post-process"
    private const val SHADER_RELOAD_HOOK = "shader-reload"
}

private class IrisPresentationController : AutoCloseable {
    private data class Presentation(
        val shader: FramebufferShader,
        var registration: AutoCloseable?,
    )

    private val presentations = IdentityHashMap<RenderContext, Presentation>()
    @Volatile private var enabled = true
    @Volatile private var closed = false

    @Synchronized
    fun beforeWorldRender(context: RenderContext) {
        if (closed) return
        val presentation = presentations[context] ?: create(context).also { presentations[context] = it }
        if (enabled && presentation.registration == null) {
            presentation.registration = context.framebuffer.world.postProcessors.install(IrisCompatibilityAdapter.id, presentation.shader)
        }
    }

    @Synchronized
    fun setEnabled(context: RenderContext, enabled: Boolean): Boolean {
        check(!closed) { "Iris presentation controller is closed" }
        this.enabled = enabled
        val presentation = presentations[context] ?: if (enabled) {
            create(context).also { presentations[context] = it }
        } else {
            return false
        }
        if (enabled && presentation.registration == null) {
            presentation.registration = context.framebuffer.world.postProcessors.install(IrisCompatibilityAdapter.id, presentation.shader)
        } else if (!enabled) {
            presentation.registration?.close()
            presentation.registration = null
        }
        return presentation.registration != null
    }

    @Synchronized
    fun isInstalled(context: RenderContext): Boolean = presentations[context]?.registration != null

    fun isEnabled(): Boolean = enabled

    private fun create(context: RenderContext): Presentation {
        val native = context.system.shader.create(
            vertex = minosoft("framebuffer/world.vsh"),
            fragment = minosoft("framebuffer/world/compatibility/iris.fsh"),
        )
        val shader = FramebufferShader(native)
        shader.load()
        return Presentation(shader, null)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        presentations.values.forEach { presentation ->
            presentation.registration?.close()
            val shader = presentation.shader
            val context = shader.native.context
            if (context.state.active) {
                context.queue += {
                    if (shader.native.loaded) shader.unload()
                }
            }
        }
        presentations.clear()
    }
}

private class IrisDebugProvider(
    private val presentation: IrisPresentationController,
) : ModDebugProvider {
    override fun modId() = "iris"
    override fun providerVersion() = "fabric-adapter-v2"

    override fun register(registrar: ModDebugRegistrar) {
        registrar.operation("presentation") { _, body ->
            val enabled = if (body.has("enabled")) {
                if (!body.path("enabled").isBoolean) {
                    throw DebugOperationException("invalid_request", "enabled must be a boolean")
                }
                body.path("enabled").booleanValue()
            } else {
                presentation.isEnabled()
            }
            val (_, context) = activeRenderSession()
            val future = CompletableFuture<DebugOperationResult>()
            context.queue += {
                try {
                    val installed = presentation.setEnabled(context, enabled)
                    future.complete(
                        DebugOperationResult.json(
                            DebugJson.MAPPER.createObjectNode()
                                .put("enabled", presentation.isEnabled())
                                .put("installed", installed)
                                .put("shader", "minosoft:framebuffer/world/compatibility/iris.fsh")
                                .put("presentation", "source-native-iris-compatibility")
                                .put("frame", context.frameNumber),
                        ),
                    )
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            future
        }
        registrar.operation("reload-shaders") { _, _ ->
            val (session, context) = activeRenderSession()
            val future = CompletableFuture<DebugOperationResult>()
            context.queue += {
                try {
                    FabricResourceReloadEvents.run(
                        session = session,
                        type = FabricResourceReloadType.SHADERS,
                        prepare = { Unit },
                        apply = { context.system.shader.reload() },
                    )
                    future.complete(
                        DebugOperationResult.json(
                            DebugJson.MAPPER.createObjectNode()
                                .put("reloaded", true)
                                .put("type", FabricResourceReloadType.SHADERS.wireName)
                                .put("presentationInstalled", presentation.isInstalled(context))
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

    private fun activeRenderSession(): Pair<PlaySession, RenderContext> {
        val active = PlaySession.collectSessions().mapNotNull { session ->
            session.rendering?.context?.takeIf { it.state.active }?.let { session to it }
        }
        if (active.size != 1) {
            throw DebugOperationException("not_ready", "Iris presentation control requires exactly one active render session")
        }
        return active.single()
    }
}
