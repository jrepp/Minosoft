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
import de.bixilon.minosoft.config.profile.ProfileOptions
import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.CycleConfigControl
import de.bixilon.minosoft.config.settings.SteppedConfigControl
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.config.settings.SettingsCategory
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisWorldShaderPipeline
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPackOption
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPackSettings
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelinePlan
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRendererBridge
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRendererCallbacks
import de.bixilon.minosoft.gui.rendering.system.opengl.irisPerBufferBlending
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.lwjgl.opengl.GL

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
        var dimension: ResourceLocation?,
        var failedDimension: ResourceLocation? = null,
    )

    private val presentations = IdentityHashMap<RenderContext, Presentation>()
    private val options = FabricAdapterOptionStore(
        "iris",
        mapOf(
            "enabled" to "true",
            "shader_pack" to (System.getenv("MINOSOFT_SHADER_PACK") ?: ""),
            "shader_options" to (System.getenv("MINOSOFT_SHADER_OPTIONS") ?: ""),
        ),
    )
    private val knownPackPaths = linkedSetOf<Path>().apply {
        options.string("shader_pack").takeIf(String::isNotBlank)?.let { configured ->
            runCatching { Path.of(configured).toAbsolutePath().normalize() }.getOrNull()?.let(::add)
        }
    }
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
        val shaderSettings = selectedPath()?.let { path ->
            runCatching { IrisShaderPackPlanner.settings(path) }.getOrNull()
        }
        val shaderOptions = shaderSettings?.options.orEmpty()
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
        if (shaderSettings?.profiles?.isNotEmpty() == true) {
            entries += ConfigEntry(
                id = "shader_profile",
                label = "Profile",
                description = "Applies the shader pack's authored profile values and program selection.",
                defaultValue = CUSTOM_PROFILE,
                control = CycleConfigControl(listOf(CUSTOM_PROFILE) + shaderSettings.profiles.map { it.name }) {
                    if (it == CUSTOM_PROFILE) "Custom" else it
                },
                read = { shaderSettings.selectedProfile(optionValues())?.name ?: CUSTOM_PROFILE },
                write = { name ->
                    if (name != CUSTOM_PROFILE) {
                        val profile = shaderSettings.profiles.single { it.name == name }
                        setOptions(profile.optionValues)
                    }
                },
                enabledWhen = { it[enabledEntry] },
            )
        }
        val optionCategories = optionCategories(shaderSettings)
        orderedOptions(shaderSettings).forEachIndexed { index, option ->
            entries += ConfigEntry(
                id = "shader_option_$index",
                label = option.name.replace('_', ' ').lowercase(),
                description = "Shader-pack define ${option.name}.",
                defaultValue = option.defaultValue,
                control = if (option.name in shaderSettings.orEmptySliders()) {
                    SteppedConfigControl(option.values)
                } else {
                    CycleConfigControl(option.values)
                },
                read = { optionValues()[option.name] ?: option.defaultValue },
                write = { setOption(option.name, it) },
                enabledWhen = { it[enabledEntry] },
                category = optionCategories[option.name] ?: "shader_options",
            )
        }
        val authoredCategories = shaderSettings?.subScreens.orEmpty().mapIndexedNotNull { index, screen ->
            val id = "shader_screen_$index"
            if (optionCategories.values.none { it == id }) return@mapIndexedNotNull null
            SettingsCategory(
                id,
                screen.id.orEmpty().replace('_', ' ').replaceFirstChar(Char::uppercase),
                screen.columns?.let { "Authored shader-pack screen (${it} columns)." },
            )
        }
        val usesFallbackCategory = shaderOptions.any {
            (optionCategories[it.name] ?: "shader_options") == "shader_options"
        }
        return SettingsSchema(
            title = "Iris shader settings",
            entries = entries,
            categories = buildList {
                add(SettingsCategory.GENERAL)
                addAll(authoredCategories)
                if (usesFallbackCategory) add(SettingsCategory("shader_options", "Shader options"))
            },
            persist = {
                if (isInstalled(context)) reload(context)
                options.persist()
            },
        )
    }

    @Synchronized
    fun beforeWorldRender(context: RenderContext) {
        val current = presentations[context] ?: return
        val dimension = context.session.world.name
        if (dimension == current.dimension || dimension == current.failedDimension) return
        try {
            replace(context, current, dimension)
        } catch (failure: Throwable) {
            current.failedDimension = dimension
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                "Iris dimension pipeline transition failed dimension=$dimension: ${failure.message}"
            }
        }
    }

    @Synchronized
    fun attach(context: RenderContext): Boolean {
        check(!closed) { "Iris shader pipeline controller is closed" }
        if (!enabled) return false
        val path = configuredPath() ?: return false
        rememberPack(path)
        val dimension = context.session.world.name
        val plan = IrisShaderPackPlanner.plan(
            path,
            optionValues(),
            dimension,
            shaderPreprocessorDefines(context.session),
        )
        val chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Iris shader pipeline requires terrain" }
        val registration = context.shaderPipeline.replace(chunks.terrain.descriptor()) {
            IrisWorldShaderPipeline.prepare(context, plan)
        }
        presentations.put(context, Presentation(path, plan, registration, dimension))?.registration?.close()
        chunks.invalidate(context.session.world)
        context.renderer.pipeline.rebuild()
        return true
    }

    @Synchronized
    fun setEnabled(context: RenderContext, enabled: Boolean): Boolean {
        check(!closed) { "Iris shader pipeline controller is closed" }
        if (enabled) {
            val previous = this.enabled
            this.enabled = true
            if (presentations[context]?.registration != null) return true
            try {
                return attach(context)
            } catch (failure: Throwable) {
                this.enabled = previous
                throw failure
            }
        }
        this.enabled = false
        presentations.remove(context)?.let { presentation ->
            presentation.registration?.close()
            presentation.registration = null
            requireNotNull(context.renderer[ChunkRenderer]) {
                "Iris shader pipeline requires terrain"
            }.invalidate(context.session.world, TerrainBuildCause.RESOURCE_GENERATION_CHANGE)
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
        try {
            attach(context)
        } catch (error: Throwable) {
            options.set("shader_pack", previous?.toString() ?: "")
            options.set("shader_options", previousOptions)
            throw error
        }
    }

    @Synchronized
    fun reload(context: RenderContext): ShaderPipelinePlan {
        val current = requireNotNull(presentations[context]) { "No Iris shader pack is active" }
        return replace(context, current, context.session.world.name)
    }

    @Synchronized
    fun configureOptions(
        context: RenderContext,
        updates: Map<String, String>,
    ): ShaderPipelinePlan {
        val path = selectedPath()
            ?: throw DebugOperationException("not_ready", "No Iris shader pack is selected")
        val settings = IrisShaderPackPlanner.settings(path)
        val declared = settings.options.associateBy(ShaderPackOption::name)
        updates.forEach { (name, value) ->
            val option = declared[name]
                ?: throw DebugOperationException("invalid_request", "Unknown shader option: $name")
            if (value != option.defaultValue && value !in option.values) {
                throw DebugOperationException(
                    "invalid_request",
                    "Invalid value for $name; expected one of ${option.values.joinToString()}",
                )
            }
        }

        val previous = options.string("shader_options")
        setOptions(updates)
        return try {
            reload(context)
        } catch (error: Throwable) {
            options.set("shader_options", previous)
            throw error
        }
    }

    @Synchronized
    fun configuredOptions(): Map<String, String> = optionValues()

    @Synchronized
    fun selectDiscoveredPack(context: RenderContext, requested: String): ShaderPipelinePlan {
        check(!closed) { "Iris shader pipeline controller is closed" }
        val requestedPath = runCatching { Path.of(requested).toAbsolutePath().normalize() }
            .getOrElse {
                throw DebugOperationException("invalid_request", "shader pack path is invalid")
            }
        val available = availablePacks().asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it).toAbsolutePath().normalize() }
            .toSet()
        if (requestedPath !in available) {
            throw DebugOperationException(
                "invalid_request",
                "shader pack is not present in the configured shaderpacks directory",
            )
        }
        selectPack(context, requestedPath)
        return requireNotNull(presentations[context]?.plan) {
            "Selected Iris shader pack did not publish a presentation"
        }
    }

    @Synchronized
    fun packPath(context: RenderContext): String? = presentations[context]?.path?.toString()

    private fun replace(
        context: RenderContext,
        current: Presentation,
        dimension: ResourceLocation?,
    ): ShaderPipelinePlan {
        val candidate = IrisShaderPackPlanner.plan(
            current.path,
            optionValues(),
            dimension,
            shaderPreprocessorDefines(context.session),
        )
        val chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Iris shader pipeline requires terrain" }
        val registration = context.shaderPipeline.replace(chunks.terrain.descriptor()) {
            IrisWorldShaderPipeline.prepare(context, candidate)
        }
        current.registration?.close()
        current.registration = registration
        current.plan = candidate
        current.dimension = dimension
        current.failedDimension = null
        chunks.invalidate(context.session.world)
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

    private fun shaderPreprocessorDefines(session: PlaySession): Map<String, String> {
        return IrisShaderPackPlanner.standardEnvironmentDefines(
            minecraftVersion = irisMinecraftVersion(session.version.name),
            perBufferBlending = GL.getCapabilities().irisPerBufferBlending,
            distantHorizons = FabricRendererRegistry.registrations().any {
                it.owner == DistantHorizonsCompatibilityAdapter.id
            },
        )
    }

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
        setOptions(mapOf(name to value))
    }

    private fun setOptions(updates: Map<String, String>) {
        updates.forEach { (name, value) ->
            require(';' !in name && '=' !in name && ';' !in value) {
                "Shader option contains an unsupported separator."
            }
        }
        val values = optionValues().toMutableMap()
        values.putAll(updates)
        options.set("shader_options", values.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" })
    }

    private fun optionCategories(settings: ShaderPackSettings?): Map<String, String> {
        if (settings == null) return emptyMap()
        val categories = linkedMapOf<String, String>()
        settings.subScreens.forEachIndexed { index, screen ->
            val category = "shader_screen_$index"
            screen.entries.forEach { entry ->
                if (entry in settings.options.map(ShaderPackOption::name)) {
                    categories.putIfAbsent(entry, category)
                }
            }
            if ("*" in screen.entries) {
                settings.options.forEach { categories.putIfAbsent(it.name, category) }
            }
        }
        settings.mainScreen?.entries.orEmpty().forEach { entry ->
            if (entry in settings.options.map(ShaderPackOption::name)) {
                categories[entry] = SettingsCategory.GENERAL_ID
            }
        }
        return categories
    }

    private fun orderedOptions(settings: ShaderPackSettings?): List<ShaderPackOption> {
        if (settings == null) return emptyList()
        val byName = settings.options.associateBy(ShaderPackOption::name)
        val ordered = linkedSetOf<String>()

        fun add(entries: List<String>) {
            entries.forEach { entry ->
                when {
                    entry == "*" -> ordered += settings.options.map(ShaderPackOption::name)
                    entry.startsWith('[') && entry.endsWith(']') && entry != "[profile]" -> {
                        val id = entry.substring(1, entry.lastIndex)
                        settings.subScreens.firstOrNull { it.id == id }?.let { add(it.entries) }
                    }

                    entry in byName -> ordered += entry
                }
            }
        }

        settings.mainScreen?.let { add(it.entries) }
        settings.subScreens.forEach { add(it.entries) }
        ordered += settings.options.map(ShaderPackOption::name)
        return ordered.map(byName::getValue)
    }

    private fun ShaderPackSettings?.orEmptySliders(): Set<String> = this?.sliders?.toSet().orEmpty()

    private fun availablePacks(): List<String> {
        val packs = linkedSetOf(NONE)
        knownPackPaths.forEach { packs += it.toString() }
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

    private fun rememberPack(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized in knownPackPaths) return
        if (knownPackPaths.size >= MAX_DISCOVERED_PACKS) {
            throw DebugOperationException(
                "resource_limit",
                "Iris remembered shader-pack catalog exceeds $MAX_DISCOVERED_PACKS entries",
            )
        }
        knownPackPaths.add(normalized)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        /*
         * Fabric's process scope is also closed from the JVM shutdown hook,
         * which has no OpenGL context. Keep active presentations reachable so
         * IrisRendererHook.unload can retire their GPU generations on the
         * owning render thread during RenderContext.unload.
         */
    }

    private companion object {
        const val NONE = ""
        const val CUSTOM_PROFILE = ""
        const val MAX_DISCOVERED_PACKS = 1_024
    }
}

internal fun irisMinecraftVersion(name: String): Int {
    val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""").matchEntire(name)
        ?: throw IllegalArgumentException(
            "Iris shader packs require a release Minecraft version; cannot derive MC_VERSION from '$name'",
        )
    val (major, minor, patch) = match.destructured
    val components = listOf(major, minor, patch.ifEmpty { "0" }).map(String::toInt)
    require(components.all { it in 0..99 }) { "Minecraft version '$name' cannot be represented as MC_VERSION" }
    return components[0] * 10_000 + components[1] * 100 + components[2]
}

private class IrisRendererHookBuilder(
    private val controller: IrisPresentationController,
) : RendererBuilder<ShaderPipelineRendererBridge> {
    override fun build(session: PlaySession, context: RenderContext): ShaderPipelineRendererBridge {
        val callbacks = ShaderPipelineRendererCallbacks(
            installed = {
                val installed = controller.attach(context)
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "IRIS_PIPELINE_SELECTION installed=$installed pack=${controller.packName(context)} " +
                        "fingerprint=${controller.fingerprint(context)}"
                }
            },
            unloaded = { controller.detach(context) },
        )
        return ShaderPipelineRendererBridge(context, callbacks)
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
        registrar.operation("pass-cutoff") { _, body ->
            val restoreNode = body["restore"]
            if (restoreNode != null && !restoreNode.isBoolean) {
                throw DebugOperationException("invalid_request", "restore must be boolean")
            }
            val restore = restoreNode?.booleanValue() ?: false
            if (restore && body.has("cutoff")) {
                throw DebugOperationException("invalid_request", "restore and cutoff are mutually exclusive")
            }
            val updateRequested = restore || body.has("cutoff")
            val requestedCutoff = when {
                restore -> null
                body.has("cutoff") && body.path("cutoff").isTextual -> body.path("cutoff").textValue()
                body.has("cutoff") && body.path("cutoff").isNull -> null
                body.has("cutoff") -> throw DebugOperationException(
                    "invalid_request",
                    "cutoff must be a string or null",
                )
                else -> null
            }
            val (_, context) = activeRenderSession()
            val future = CompletableFuture<DebugOperationResult>()
            context.queue += {
                try {
                    context.shaderPipeline.acquire().use { lease ->
                        val pipeline = lease.pipeline as? IrisWorldShaderPipeline
                            ?: throw DebugOperationException("not_ready", "No Iris shader pipeline is active")
                        val active = if (updateRequested) pipeline.setPassCutoff(requestedCutoff)
                        else pipeline.diagnostics().activePassCutoff
                        future.complete(
                            DebugOperationResult.json(
                                DebugJson.MAPPER.createObjectNode().apply {
                                    active?.let { put("activeCutoff", it) } ?: putNull("activeCutoff")
                                    putArray("availableCutoffs").also { values ->
                                        pipeline.passCutoffOptions().forEach(values::add)
                                    }
                                    put("generationScoped", true)
                                    put("frame", context.frameNumber)
                                },
                            ),
                        )
                    }
                } catch (error: IllegalArgumentException) {
                    future.completeExceptionally(
                        DebugOperationException("invalid_request", error.message ?: "invalid Iris pass cutoff"),
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
        registrar.operation("configure-options") { _, body ->
            val options = body.path("options")
            if (!options.isObject || options.size() !in 1..64) {
                throw DebugOperationException(
                    "invalid_request",
                    "options must be an object containing 1..64 shader-option values",
                )
            }
            val updates = linkedMapOf<String, String>()
            options.fields().forEach { (name, value) ->
                if (!value.isTextual || name.length !in 1..128 || value.textValue().length > 128) {
                    throw DebugOperationException(
                        "invalid_request",
                        "shader option names and values must be bounded strings",
                    )
                }
                updates[name] = value.textValue()
            }

            val (session, context) = activeRenderSession()
            val future = CompletableFuture<DebugOperationResult>()
            context.queue += {
                try {
                    FabricResourceReloadEvents.run(
                        session = session,
                        type = FabricResourceReloadType.SHADERS,
                        prepare = { Unit },
                        apply = { presentation.configureOptions(context, updates) },
                    )
                    future.complete(
                        DebugOperationResult.json(
                            DebugJson.MAPPER.createObjectNode().apply {
                                put("configured", true)
                                put("type", FabricResourceReloadType.SHADERS.wireName)
                                put("presentationInstalled", presentation.isInstalled(context))
                                put("shaderPack", presentation.packName(context))
                                put("fingerprint", presentation.fingerprint(context))
                                putObject("options").also { result ->
                                    presentation.configuredOptions().toSortedMap().forEach(result::put)
                                }
                                put("frame", context.frameNumber)
                            },
                        ),
                    )
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            future
        }
        registrar.operation("select-pack") { _, body ->
            val path = body.path("path")
            if (!path.isTextual || path.textValue().length !in 1..4_096) {
                throw DebugOperationException(
                    "invalid_request",
                    "path must be a bounded discovered shader-pack path",
                )
            }
            val (session, context) = activeRenderSession()
            val future = CompletableFuture<DebugOperationResult>()
            context.queue += {
                try {
                    val previousPath = presentation.packPath(context)
                    val previousOptions = presentation.configuredOptions()
                    FabricResourceReloadEvents.run(
                        session = session,
                        type = FabricResourceReloadType.SHADERS,
                        prepare = { Unit },
                        apply = { presentation.selectDiscoveredPack(context, path.textValue()) },
                    )
                    future.complete(
                        DebugOperationResult.json(
                            DebugJson.MAPPER.createObjectNode().apply {
                                put("selected", true)
                                put("type", FabricResourceReloadType.SHADERS.wireName)
                                put("presentationInstalled", presentation.isInstalled(context))
                                put("previousPath", previousPath)
                                putObject("previousOptions").also { result ->
                                    previousOptions.toSortedMap().forEach(result::put)
                                }
                                put("shaderPackPath", presentation.packPath(context))
                                put("shaderPack", presentation.packName(context))
                                put("fingerprint", presentation.fingerprint(context))
                                put("frame", context.frameNumber)
                            },
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
