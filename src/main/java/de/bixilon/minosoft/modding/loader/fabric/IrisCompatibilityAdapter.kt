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
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.config.profile.ProfileOptions
import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.CycleConfigControl
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.config.settings.SettingsCategory
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.framebuffer.IntegratedFramebuffer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.Renderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisWorldShaderPipeline
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelinePlan
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.nio.file.Path
import java.nio.file.Files
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
        scope.own(FabricRendererRegistry.register(id, IrisRendererHookBuilder(presentation)))
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
                        "IRIS_HOOK_INVOKED hook=$HOOK boundary=render-graph shaderPack=${presentation.packName(context)}"
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
        scope.own(
            FabricSettings.register(id, minosoft("iris_options"), "Iris shader settings") { renderer ->
                presentation.schema(renderer.context)
            },
        )
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "IRIS_HOOK_INSTALLED hook=$HOOK provider=shader-pack-pipeline reload=transactional"
        }
    }

    private const val HOOK = "shader-pipeline"
    private const val PRESENTATION_HOOK = "shader-pack-composite"
    private const val SHADER_RELOAD_HOOK = "shader-reload"
}

private class IrisPresentationController : AutoCloseable {
    private data class Presentation(
        val path: Path,
        var plan: ShaderPipelinePlan,
        var registration: AutoCloseable?,
    )

    private val presentations = IdentityHashMap<RenderContext, Presentation>()
    private val options = FabricAdapterOptionStore(
        "iris",
        mapOf(
            "enabled" to "true",
            "shader_pack" to (System.getenv("MINOSOFT_SHADER_PACK") ?: ""),
            "shader_options" to "",
        ),
    )
    @Volatile private var enabled = options.boolean("enabled")
    @Volatile private var closed = false

    fun schema(context: RenderContext): SettingsSchema {
        val enabledEntry = ConfigEntry(
            id = "enabled",
            label = "Enable shader pack",
            description = "Installs the selected shader-pack pipeline into the active render graph.",
            defaultValue = true,
            control = BooleanConfigControl,
            read = ::isEnabled,
            write = { configureEnabled(context, it) },
        )
        val packs = availablePacks()
        val shaderOptions = selectedPath()?.let { path ->
            runCatching { IrisShaderPackPlanner.options(path) }.getOrDefault(emptyList())
        }.orEmpty()
        val entries = mutableListOf<ConfigEntry<*>>(
            enabledEntry,
            ConfigEntry(
                id = "shader_pack",
                label = "Shader pack",
                description = "Discovers directories and zip archives in the configured profile shaderpacks directory.",
                defaultValue = NONE,
                control = CycleConfigControl(packs) { value ->
                    if (value == NONE) "No shader pack" else Path.of(value).fileName.toString()
                },
                read = { selectedPath()?.toString() ?: NONE },
                write = { selectPack(context, it.takeUnless { value -> value == NONE }?.let(Path::of)) },
                enabledWhen = { it[enabledEntry] },
            ),
        )
        shaderOptions.forEachIndexed { index, option ->
            entries += ConfigEntry(
                id = "shader_option_$index",
                label = option.name.replace('_', ' ').lowercase(),
                description = "Shader-pack define ${option.name}.",
                defaultValue = option.defaultValue,
                control = CycleConfigControl(option.values),
                read = { optionValues()[option.name] ?: option.defaultValue },
                write = { setOption(option.name, it) },
                enabledWhen = { it[enabledEntry] },
                category = "shader_options",
            )
        }
        return SettingsSchema(
            title = "Iris shader settings",
            entries = entries,
            categories = buildList {
                add(SettingsCategory.GENERAL)
                if (shaderOptions.isNotEmpty()) add(SettingsCategory("shader_options", "Shader options"))
            },
            persist = {
                if (isInstalled(context)) reload(context)
                options.persist()
            },
        )
    }

    @Synchronized
    fun beforeWorldRender(context: RenderContext) = Unit

    @Synchronized
    fun attach(context: RenderContext): Boolean {
        check(!closed) { "Iris shader pipeline controller is closed" }
        if (!enabled) return false
        val path = configuredPath() ?: return false
        val plan = IrisShaderPackPlanner.plan(path, optionValues())
        val chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Iris shader pipeline requires terrain" }
        val registration = context.shaderPipeline.replace(chunks.terrain.descriptor()) {
            IrisWorldShaderPipeline.prepare(context, plan)
        }
        presentations.put(context, Presentation(path, plan, registration))?.registration?.close()
        context.renderer.pipeline.rebuild()
        return true
    }

    @Synchronized
    fun setEnabled(context: RenderContext, enabled: Boolean): Boolean {
        check(!closed) { "Iris shader pipeline controller is closed" }
        this.enabled = enabled
        if (enabled) {
            if (presentations[context]?.registration != null) return true
            return attach(context)
        }
        presentations.remove(context)?.let { presentation ->
            presentation.registration?.close()
            presentation.registration = null
            context.renderer.pipeline.rebuild()
        }
        return false
    }

    @Synchronized
    fun isInstalled(context: RenderContext): Boolean = presentations[context]?.registration != null

    fun isEnabled(): Boolean = enabled

    private fun configureEnabled(context: RenderContext, enabled: Boolean) {
        setEnabled(context, enabled)
        options.set("enabled", enabled)
    }

    @Synchronized
    private fun selectPack(context: RenderContext, path: Path?) {
        check(!closed) { "Iris shader pipeline controller is closed" }
        val previous = selectedPath()
        val previousOptions = options.string("shader_options")
        if (previous == path) return
        options.set("shader_pack", path?.toString() ?: "")
        options.set("shader_options", "")
        if (!enabled) return
        presentations.remove(context)?.registration?.close()
        try {
            attach(context)
        } catch (error: Throwable) {
            options.set("shader_pack", previous?.toString() ?: "")
            options.set("shader_options", previousOptions)
            try {
                attach(context)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    @Synchronized
    fun reload(context: RenderContext): ShaderPipelinePlan {
        val current = requireNotNull(presentations[context]) { "No Iris shader pack is active" }
        val candidate = IrisShaderPackPlanner.plan(current.path, optionValues())
        val chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Iris shader pipeline requires terrain" }
        val registration = context.shaderPipeline.replace(chunks.terrain.descriptor()) {
            IrisWorldShaderPipeline.prepare(context, candidate)
        }
        current.registration?.close()
        current.registration = registration
        current.plan = candidate
        context.renderer.pipeline.rebuild()
        return candidate
    }

    @Synchronized
    fun packName(context: RenderContext): String? = presentations[context]?.plan?.packName

    @Synchronized
    fun fingerprint(context: RenderContext): String? = presentations[context]?.plan?.fingerprint

    @Synchronized
    fun detach(context: RenderContext) {
        val removed = presentations.remove(context) ?: return
        removed.registration?.close()
        if (context.state != RenderingStates.QUITTING && context.state != RenderingStates.STOPPED) {
            context.renderer.pipeline.rebuild()
        }
    }

    private fun configuredPath(): Path? = selectedPath()

    private fun selectedPath(): Path? = options.string("shader_pack").takeIf(String::isNotBlank)?.let(Path::of)

    private fun optionValues(): Map<String, String> {
        return options.string("shader_options").split(';').mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val index = entry.indexOf('=')
            if (index <= 0 || index == entry.lastIndex) return@mapNotNull null
            entry.substring(0, index) to entry.substring(index + 1)
        }.toMap()
    }

    private fun setOption(name: String, value: String) {
        require(';' !in name && '=' !in name && ';' !in value) { "Shader option contains an unsupported separator." }
        val values = optionValues().toMutableMap()
        values[name] = value
        options.set("shader_options", values.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" })
    }

    private fun availablePacks(): List<String> {
        val packs = linkedSetOf(NONE)
        selectedPath()?.let { packs += it.toString() }
        val directory = ProfileOptions.path.resolve("shaderpacks")
        if (Files.isDirectory(directory)) {
            Files.list(directory).use { entries ->
                entries.sorted().limit(MAX_DISCOVERED_PACKS.toLong()).forEach { entry ->
                    if (Files.isDirectory(entry) || entry.fileName.toString().endsWith(".zip", ignoreCase = true)) {
                        packs += entry.toString()
                    }
                }
            }
        }
        return packs.toList()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        presentations.values.forEach { presentation ->
            presentation.registration?.close()
        }
        presentations.clear()
    }

    private companion object {
        const val NONE = ""
        const val MAX_DISCOVERED_PACKS = 1_024
    }
}

private class IrisRendererHookBuilder(
    private val controller: IrisPresentationController,
) : RendererBuilder<IrisRendererHook> {
    override fun build(session: PlaySession, context: RenderContext) = IrisRendererHook(context, controller)
}

private class IrisRendererHook(
    override val context: RenderContext,
    private val controller: IrisPresentationController,
) : Renderer {
    override val framebuffer: IntegratedFramebuffer? = null

    override fun postInit(latch: AbstractLatch) {
        val installed = controller.attach(context)
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "IRIS_PIPELINE_SELECTION installed=$installed pack=${controller.packName(context)} fingerprint=${controller.fingerprint(context)}"
        }
    }

    override fun unload() = controller.detach(context)
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
                                .put("shaderPack", presentation.packName(context))
                                .put("fingerprint", presentation.fingerprint(context))
                                .put("presentation", "iris-shader-pack-pipeline")
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
                        apply = { presentation.reload(context) },
                    )
                    future.complete(
                        DebugOperationResult.json(
                            DebugJson.MAPPER.createObjectNode()
                                .put("reloaded", true)
                                .put("type", FabricResourceReloadType.SHADERS.wireName)
                                .put("presentationInstalled", presentation.isInstalled(context))
                                .put("shaderPack", presentation.packName(context))
                                .put("fingerprint", presentation.fingerprint(context))
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
