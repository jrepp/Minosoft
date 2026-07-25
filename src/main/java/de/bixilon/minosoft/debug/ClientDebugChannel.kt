/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.events.input.CharInputEvent
import de.bixilon.minosoft.gui.rendering.events.input.KeyInputEvent
import de.bixilon.minosoft.gui.rendering.events.input.MouseMoveEvent
import de.bixilon.minosoft.gui.rendering.events.input.MouseScrollEvent
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import de.bixilon.minosoft.modding.loader.ModOptions
import de.bixilon.minosoft.modding.loader.fabric.FabricModDiagnostics
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.PlaySessionStates
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

object ClientDebugChannel : AutoCloseable {
    private const val MAX_INPUT_EVENTS = 256
    private const val MAX_PIXEL_SAMPLES = 4096
    private const val MAX_REGION_PIXELS = 65536
    private const val MAX_BLOCKS = 32768

    @Volatile
    private var channel: DebugChannelServer? = null

    fun register(provider: ModDebugProvider): AutoCloseable {
        val server = channel ?: return AutoCloseable { }
        require(provider.modId().matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "Invalid mod debug provider id: ${provider.modId()}" }
        require(provider.providerVersion().isNotBlank()) { "Mod debug provider version must not be blank." }
        val registrations = ArrayDeque<AutoCloseable>()
        val registrar = ModDebugRegistrar { localName, handler ->
            require(localName.matches(Regex("[a-z][a-z0-9_.-]*"))) { "Invalid mod debug operation: $localName" }
            server.operations().register("mod:${provider.modId()}@${provider.providerVersion()}", "mods.${provider.modId()}.$localName", handler)
                .also { registrations.addFirst(it) }
        }
        try {
            provider.register(registrar)
        } catch (error: Throwable) {
            registrations.forEach { runCatching { it.close() } }
            throw error
        }
        return AutoCloseable {
            var failure: Throwable? = null
            registrations.forEach { registration ->
                try {
                    registration.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    val enabled: Boolean
        get() = System.getenv("MINOSOFT_DEBUG")?.lowercase() in setOf("1", "true", "yes", "on")

    @Synchronized
    fun start() {
        if (!enabled || channel != null) return
        val server = DebugChannelServer(DebugPaths.system(), DebugEndpointRole.CLIENT, ModOptions.trajectory, ModOptions.hotReloadGeneration)
        register(server)
        server.setStatusSupplier(::status)
        server.setMetricsSupplier(::metrics)
        try {
            server.start()
        } catch (error: Throwable) {
            runCatching { server.close() }.onFailure(error::addSuppressed)
            throw error
        }
        channel = server
        Log.log(LogMessageType.OTHER, LogLevels.INFO) {
            "Debug channel ready: ${server.endpoint().id} (${server.endpoint().address})"
        }
    }

    private fun register(server: DebugChannelServer) {
        server.operations().register("client", "state.sample") { _, body -> completed(sampleState(body)) }
        server.operations().register("client", "visual.capture") { _, _ -> onRender(::capture) }
        server.operations().register("client", "visual.sample") { _, body -> onRender { sampleVisual(it, body) } }
        server.operations().register("client", "input.inject") { _, body -> onRender { injectInput(it, body) } }
        server.operations().register("client", "world.blocks.sample") { _, body -> completed(sampleBlocks(body)) }
        server.operations().register("client", "world.aoi") { _, body -> completed(sampleBlocks(body)) }
        server.operations().register("client", "mods.debug") { _, _ -> completed(modDiagnostics()) }
        server.operations().register("client", "render.substrate") { _, _ -> onRender(::renderSubstrate) }
    }

    private fun status(): JsonNode {
        val sessions = PlaySession.collectSessions()
        val active = sessions.firstOrNull { it.state == PlaySessionStates.PLAYING } ?: sessions.firstOrNull()
        return DebugJson.MAPPER.createObjectNode().apply {
            put("ready", active?.state == PlaySessionStates.PLAYING)
            put("sessionCount", sessions.size)
            put("renderReady", renderContextOrNull()?.state?.active == true)
            renderContextOrNull()?.let {
                put("frame", it.frameNumber)
                put("frames", it.renderStats.totalFrames)
                put("fps", it.renderStats.smoothAvgFPS)
                put("averageFrameNanos", it.renderStats.avgFrameTime.avg.inWholeNanoseconds)
                put("averageDrawNanos", it.renderStats.avgDrawTime.avg.inWholeNanoseconds)
                put("timingSamples", it.renderStats.timingSamples)
                put("medianFrameNanos", it.renderStats.medianFrameNanos)
                put("p95FrameNanos", it.renderStats.p95FrameNanos)
                put("medianDrawNanos", it.renderStats.medianDrawNanos)
                put("p95DrawNanos", it.renderStats.p95DrawNanos)
            }
            active?.let {
                put("sessionId", it.sessionId.toString())
                put("sessionState", it.state.name.lowercase())
                put("minecraftVersion", it.version.name)
                put("world", it.world.name?.toString())
            }
            FabricModDiagnostics.snapshot()?.let {
                put("modpack", it.packId)
                put("modpackActive", it.active)
                put("modCount", it.mods.size)
            }
        }
    }

    private fun metrics(): JsonNode = DebugJson.MAPPER.createObjectNode().apply {
        val context = renderContextOrNull()
        put("renderReady", context?.state?.active == true)
        if (context != null) {
            put("frame", context.frameNumber)
            put("frames", context.renderStats.totalFrames)
            put("fps", context.renderStats.smoothAvgFPS)
            put("averageFrameNanos", context.renderStats.avgFrameTime.avg.inWholeNanoseconds)
            put("averageDrawNanos", context.renderStats.avgDrawTime.avg.inWholeNanoseconds)
            put("renderQueueDepth", context.queue.size)
        }
        val sessions = PlaySession.collectSessions()
        put("sessions", sessions.size)
        put("playingSessions", sessions.count { it.state == PlaySessionStates.PLAYING })
    }

    private fun sampleState(body: JsonNode): DebugOperationResult {
        val view = body.path("view").asText("client.summary")
        if (view !in setOf("client.summary", "client.player", "client.world", "client.entities")) {
            throw DebugOperationException("invalid_request", "unknown client state view: $view")
        }
        val session = selectedSession()
        val result = status() as ObjectNode
        result.put("view", view)
        if (session.state == PlaySessionStates.PLAYING) {
            val player = session.player
            val position = player.physics.position
            result.putObject("player").apply {
                put("x", position.x); put("y", position.y); put("z", position.z)
                put("health", player.health)
                put("gamemode", player.gamemode.name.lowercase())
                put("sprinting", player.isSprinting)
            }
            result.putObject("worldState").apply {
                put("dimension", session.world.name?.toString())
                put("time", session.world.time.time)
                put("age", session.world.time.age)
                put("dayPhase", session.world.time.phase.name.lowercase())
                putObject("weather").apply {
                    put("rain", session.world.weather.rain)
                    put("thunder", session.world.weather.thunder)
                }
                put("loadedChunks", session.world.chunks.chunks.size)
            }
            if (view == "client.entities") {
                val entities = result.putArray("entities")
                val origin = player.physics.position
                session.world.entities.lock.acquired {
                    session.world.entities.entities
                        .sortedBy {
                            val position = it.physics.position
                            val dx = position.x - origin.x
                            val dy = position.y - origin.y
                            val dz = position.z - origin.z
                            dx * dx + dy * dy + dz * dz
                        }
                        .take(128)
                        .forEach { entity ->
                            val position = entity.physics.position
                            val entityRenderer = entity.renderer
                            entities.addObject().apply {
                                put("id", entity.id)
                                put("type", entity.type.identifier.toString())
                                put("x", position.x); put("y", position.y); put("z", position.z)
                                put("invisible", entity.isInvisible)
                                put("renderer", entityRenderer?.javaClass?.simpleName)
                                put("visibility", entityRenderer?.visibility?.name?.lowercase())
                                put("features", entityRenderer?.features?.count() ?: 0)
                            }
                        }
                }
                result.put("entityCount", session.world.entities.size)
                renderContextOrNull()?.renderer?.get(EntitiesRenderer)?.let {
                    result.put("entityRendererCount", it.renderers.size)
                    result.put("entityDrawableCount", it.drawer.size)
                }
            }
        }
        renderContextOrNull()?.rendering?.audioPlayer?.let { audio ->
            result.putObject("audio").apply {
                put("initialized", audio.initialized)
                put("enabled", session.profiles.audio.enabled)
                put("configuredMasterVolume", session.profiles.audio.volume.master)
                put("appliedMasterVolume", audio.appliedMasterVolume)
                put("sources", audio.sourcesCount)
                put("activeSources", audio.sourcesCount - audio.availableSources)
                put("requestedSounds", audio.requestedSounds)
                put("resolvedSounds", audio.resolvedSounds)
                put("unresolvedSounds", audio.unresolvedSounds)
                put("startedSounds", audio.startedSounds)
                put("distanceRejectedSounds", audio.distanceRejectedSounds)
                put("missingBufferSounds", audio.missingBufferSounds)
                put("lastRequestedSound", audio.lastRequestedSound?.toString())
                put("lastResolvedSound", audio.lastResolvedSound?.toString())
                put("lastUnresolvedSound", audio.lastUnresolvedSound?.toString())
                put("lastStartedSound", audio.lastStartedSound?.toString())
                audio.lastStartedPosition?.let { position ->
                    putObject("lastStartedPosition").apply {
                        put("x", position.x); put("y", position.y); put("z", position.z)
                    }
                }
                putObject("listenerPosition").apply {
                    put("x", audio.listenerWorldPosition.x)
                    put("y", audio.listenerWorldPosition.y)
                    put("z", audio.listenerWorldPosition.z)
                }
            }
        }
        return DebugOperationResult.json(result)
    }

    private fun capture(context: RenderContext): DebugOperationResult {
        val size = context.window.size
        validateFramebuffer(size)
        val buffer = context.system.readPixels(Vec2i.EMPTY, size)
        val bytes = encodePng(buffer)
        val metadata = DebugJson.MAPPER.createObjectNode().apply {
            put("width", size.x); put("height", size.y)
            put("frame", context.frameNumber)
            put("capturedAt", Instant.now().toString())
        }
        return DebugOperationResult.attachment(metadata, bytes, "image/png", "minosoft-${context.frameNumber}.png")
    }

    private fun sampleVisual(context: RenderContext, body: JsonNode): DebugOperationResult {
        val size = context.window.size
        validateFramebuffer(size)
        val buffer = context.system.readPixels(Vec2i.EMPTY, size)
        val result = DebugJson.MAPPER.createObjectNode().apply {
            put("width", size.x); put("height", size.y); put("frame", context.frameNumber)
        }
        val points = body.path("points")
        if (points.isArray) {
            if (points.size() > MAX_PIXEL_SAMPLES) throw DebugOperationException("limit_exceeded", "too many pixel samples")
            val values = result.putArray("points")
            points.forEach { point ->
                val x = point.path("x").asInt(-1)
                val y = point.path("y").asInt(-1)
                validatePixel(x, y, size)
                val color = buffer.getRGBA(x, size.y - 1 - y)
                values.addObject().apply {
                    put("x", x); put("y", y)
                    put("r", color.red); put("g", color.green); put("b", color.blue); put("a", color.alpha)
                    put("hex", color.toString())
                }
            }
        }
        val region = body.path("region")
        if (region.isObject) addRegionSample(result, buffer, size, region)
        return DebugOperationResult.json(result)
    }

    private fun addRegionSample(result: ObjectNode, buffer: TextureBuffer, size: Vec2i, region: JsonNode) {
        val x = region.path("x").asInt(-1)
        val y = region.path("y").asInt(-1)
        val width = region.path("width").asInt(0)
        val height = region.path("height").asInt(0)
        if (width < 1 || height < 1 || width.toLong() * height > MAX_REGION_PIXELS
            || x < 0 || y < 0 || x > size.x - width || y > size.y - height) {
            throw DebugOperationException("limit_exceeded", "visual region is invalid or exceeds $MAX_REGION_PIXELS pixels")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var luminance = 0.0
        for (py in y until y + height) for (px in x until x + width) {
            val color = buffer.getRGBA(px, size.y - 1 - py)
            digest.update(byteArrayOf(color.red.toByte(), color.green.toByte(), color.blue.toByte(), color.alpha.toByte()))
            luminance += 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
        }
        result.putObject("region").apply {
            put("x", x); put("y", y); put("width", width); put("height", height)
            put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            put("averageLuminance", luminance / (width * height) / 255.0)
        }
    }

    private fun injectInput(context: RenderContext, body: JsonNode): DebugOperationResult {
        val events = body.path("events")
        if (!events.isArray || events.isEmpty) throw DebugOperationException("invalid_request", "events must be a non-empty array")
        if (events.size() > MAX_INPUT_EVENTS) throw DebugOperationException("limit_exceeded", "too many input events")
        var expandedEvents = 0
        events.forEach { event ->
            if (!event.isObject) throw DebugOperationException("invalid_request", "input event must be an object")
            expandedEvents += when (event.path("type").asText()) {
                "key" -> {
                    enumValue<KeyCodes>(event, "code")
                    enumValue<KeyChangeTypes>(event, "action")
                    1
                }
                "text" -> {
                    val text = event["text"]?.takeIf(JsonNode::isTextual)?.asText()
                        ?: throw DebugOperationException("invalid_request", "text event requires text")
                    text.codePointCount(0, text.length)
                }
                "mouse_move", "scroll" -> {
                    finiteFloat(event, "x")
                    finiteFloat(event, "y")
                    1
                }
                else -> throw DebugOperationException("invalid_request", "unknown input event type")
            }
            if (expandedEvents > MAX_INPUT_EVENTS) {
                throw DebugOperationException("limit_exceeded", "expanded input exceeds $MAX_INPUT_EVENTS events")
            }
        }
        var injected = 0
        events.forEach { event ->
            when (event.path("type").asText()) {
                "key" -> {
                    val code = enumValue<KeyCodes>(event, "code")
                    val action = enumValue<KeyChangeTypes>(event, "action")
                    context.session.events.fire(KeyInputEvent(context, code, action))
                    injected++
                }
                "text" -> {
                    val text = event.path("text").asText()
                    text.codePoints().forEach { context.session.events.fire(CharInputEvent(context, it)); injected++ }
                }
                "mouse_move" -> {
                    val x = finiteFloat(event, "x"); val y = finiteFloat(event, "y")
                    val previous = context.input.mousePosition
                    val position = Vec2f(x, y)
                    context.session.events.fire(MouseMoveEvent(context, position, position - previous))
                    injected++
                }
                "scroll" -> {
                    context.session.events.fire(MouseScrollEvent(context, Vec2f(finiteFloat(event, "x"), finiteFloat(event, "y"))))
                    injected++
                }
                else -> throw DebugOperationException("invalid_request", "unknown input event type")
            }
        }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().put("injected", injected).put("frame", context.frameNumber))
    }

    private inline fun <reified T : Enum<T>> enumValue(node: JsonNode, field: String): T {
        val value = node.path(field).asText().uppercase()
        return try { enumValueOf<T>(value) } catch (_: IllegalArgumentException) {
            throw DebugOperationException("invalid_request", "invalid $field: $value")
        }
    }

    private fun finiteFloat(node: JsonNode, field: String): Float {
        val value = node[field]?.takeIf(JsonNode::isNumber)?.floatValue()
            ?: throw DebugOperationException("invalid_request", "$field must be numeric")
        if (!value.isFinite()) throw DebugOperationException("invalid_request", "$field must be finite")
        return value
    }

    private fun sampleBlocks(body: JsonNode): DebugOperationResult {
        val session = selectedPlayingSession()
        val minNode = body.path("min")
        val maxNode = body.path("max")
        if (!minNode.isObject || !maxNode.isObject) throw DebugOperationException("invalid_request", "min and max block coordinates are required")
        val min = blockPosition(minNode)
        val max = blockPosition(maxNode)
        val sx = max.x.toLong() - min.x + 1
        val sy = max.y.toLong() - min.y + 1
        val sz = max.z.toLong() - min.z + 1
        val volume = checkedBlockVolume(sx, sy, sz)

        val palette = linkedMapOf<String, Int>()
        val indices = ArrayList<Int>(volume)
        var notLoaded = 0
        session.world.lock.acquired {
            for (y in min.y..max.y) for (z in min.z..max.z) for (x in min.x..max.x) {
                val position = BlockPosition(x, y, z)
                val chunk = session.world.chunks[position.chunkPosition]
                val state = chunk?.get(position.inChunkPosition)
                val name = if (chunk == null) {
                    notLoaded++; "minosoft:not_loaded"
                } else if (state == null) {
                    "minecraft:air"
                } else buildString {
                    append(state.block.identifier)
                    if (state.properties.isNotEmpty()) append(state.properties.entries.sortedBy { it.key.toString() }.joinToString(",", "[", "]") {
                        "${it.key.toString().lowercase()}=${it.value.toString().lowercase()}"
                    })
                }
                indices += palette.getOrPut(name) { palette.size }
            }
        }
        val result = DebugJson.MAPPER.createObjectNode()
        result.set<ObjectNode>("min", positionJson(min)); result.set<ObjectNode>("max", positionJson(max))
        result.put("order", "y,z,x"); result.put("volume", volume); result.put("notLoaded", notLoaded)
        val paletteJson = result.putArray("palette")
        palette.keys.forEach(paletteJson::add)
        val runs = result.putArray("runs")
        var index = 0
        while (index < indices.size) {
            val value = indices[index]
            var count = 1
            while (index + count < indices.size && indices[index + count] == value) count++
            runs.addArray().add(value).add(count)
            index += count
        }
        return DebugOperationResult.json(result)
    }

    private fun modDiagnostics(): DebugOperationResult {
        val snapshot = FabricModDiagnostics.snapshot()
            ?: return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().put("active", false).set<ArrayNode>("mods", DebugJson.MAPPER.createArrayNode()))
        val result = DebugJson.MAPPER.createObjectNode().apply {
            put("packId", snapshot.packId); put("packName", snapshot.packName); put("trajectory", snapshot.trajectory)
            put("generation", snapshot.generation); put("active", snapshot.active)
            put("preflightNanos", snapshot.preflightNanos); put("uptimeNanos", snapshot.uptimeNanos)
        }
        val mods = result.putArray("mods")
        snapshot.mods.forEach { mod ->
            val item = mods.addObject()
            item.put("id", mod.id); item.put("name", mod.name); item.put("version", mod.version)
            item.put("adapterId", mod.adapterId); item.put("status", mod.status.wireName)
            item.put("activationNanos", mod.activationNanos); item.put("failure", mod.failure)
            val hooks = item.putArray("hooks")
            mod.hooks.forEach { hook -> hooks.addObject().apply {
                put("kind", hook.kind); put("installed", hook.installed); put("invocations", hook.invocations)
                put("totalNanos", hook.totalNanos); put("averageNanos", hook.averageNanos); put("maxNanos", hook.maxNanos)
            } }
        }
        return DebugOperationResult.json(result)
    }

    private fun renderSubstrate(context: RenderContext): DebugOperationResult {
        val graph = context.renderer.pipeline.generation
        val shader = context.shaderPipeline.selection()
        val shaderStats = context.shaderPipeline.stats()
        val result = DebugJson.MAPPER.createObjectNode().apply {
            put("frame", context.frameNumber)
            put("graphGeneration", graph.number)
            put("passCount", graph.passes.size)
            put("shaderGeneration", shader.generation)
            put("shaderOwner", shader.owner.value)
            put("shaderPack", shader.packName)
            put("shaderFingerprint", shader.fingerprint)
            putObject("shaderResources").apply {
                put("activeLeases", shaderStats.activeLeases)
                put("retiredAwaitingLeases", shaderStats.retiredAwaitingLeases)
                put("closed", shaderStats.closed)
            }
        }
        val passes = result.putArray("passes")
        graph.passes.forEach { pass ->
            passes.addObject().apply {
                put("id", pass.id.value)
                put("owner", pass.owner.value)
                put("view", pass.view.value)
                put("phase", pass.phase.name.lowercase())
                put("semantic", pass.semantic)
                put("order", pass.order)
            }
        }
        val owners = result.putArray("owners")
        graph.passes.map { it.owner.value }.distinct().sorted().forEach(owners::add)

        context.renderer[ChunkRenderer]?.let { chunks ->
            val terrain = chunks.terrain.selection()
            val descriptor = chunks.terrain.descriptor()
            val terrainStats = chunks.terrain.stats()
            result.putObject("terrain").apply {
                put("generation", terrain.generation)
                put("owner", terrain.owner.value)
                put("implementation", terrain.implementation)
                put("supportsAuxiliaryViews", descriptor.supportsAuxiliaryViews)
                put("vertexLayout", descriptor.vertexLayout.id.value)
                put("vertexStrideBytes", descriptor.vertexLayout.strideBytes)
                val materials = putArray("materials")
                descriptor.materials.map { it.name.lowercase() }.sorted().forEach(materials::add)
                val semantics = putArray("vertexSemantics")
                descriptor.vertexLayout.attributes.map { it.semantic.name.lowercase() }.forEach(semantics::add)
                put("preparedFrames", terrainStats.preparedFrames)
                put("submittedBatches", terrainStats.submittedBatches)
                put("preparationTimingSamples", terrainStats.preparationTimingSamples)
                put("medianPreparationNanos", terrainStats.medianPreparationNanos)
                put("p95PreparationNanos", terrainStats.p95PreparationNanos)
                put("submissionTimingSamples", terrainStats.submissionTimingSamples)
                put("medianSubmissionNanos", terrainStats.medianSubmissionNanos)
                put("p95SubmissionNanos", terrainStats.p95SubmissionNanos)
                val submissions = putArray("currentFrameSubmissions")
                terrainStats.currentFrameSubmissions
                    .sortedWith(compareBy({ it.first.value }, { it.second.name }))
                    .forEach { (view, material) ->
                        submissions.addObject()
                            .put("view", view.value)
                            .put("material", material.name.lowercase())
                    }
                putObject("resources").apply {
                    put("activeLeases", terrainStats.resources.activeLeases)
                    put("retiredAwaitingLeases", terrainStats.resources.retiredAwaitingLeases)
                    put("closed", terrainStats.resources.closed)
                }
            }
        }
        return DebugOperationResult.json(result)
    }

    private fun onRender(work: (RenderContext) -> DebugOperationResult): CompletableFuture<DebugOperationResult> {
        val context = renderContextOrNull() ?: throw DebugOperationException("not_ready", "no active render context")
        val future = CompletableFuture<DebugOperationResult>()
        context.queue += {
            if (!future.isDone) {
                try { future.complete(work(context)) } catch (error: Throwable) { future.completeExceptionally(error) }
            }
        }
        return future
    }

    private fun completed(result: DebugOperationResult) = CompletableFuture.completedFuture(result)

    private fun selectedSession(): PlaySession = PlaySession.collectSessions().firstOrNull { it.state == PlaySessionStates.PLAYING }
        ?: PlaySession.collectSessions().firstOrNull()
        ?: throw DebugOperationException("not_ready", "no client session")

    private fun selectedPlayingSession(): PlaySession = PlaySession.collectSessions().firstOrNull { it.state == PlaySessionStates.PLAYING }
        ?: throw DebugOperationException("not_ready", "no playing client session")

    private fun renderContextOrNull(): RenderContext? = PlaySession.collectSessions().asSequence()
        .mapNotNull { it.rendering?.context }
        .firstOrNull { it.state != RenderingStates.STOPPED && it.state != RenderingStates.QUITTING }

    private fun validateFramebuffer(size: Vec2i) {
        if (size.x < 1 || size.y < 1 || size.x.toLong() * size.y > 16_777_216L) {
            throw DebugOperationException("limit_exceeded", "framebuffer dimensions are invalid or too large")
        }
    }

    private fun validatePixel(x: Int, y: Int, size: Vec2i) {
        if (x !in 0 until size.x || y !in 0 until size.y) throw DebugOperationException("invalid_request", "pixel is outside framebuffer")
    }

    private fun checkedBlockVolume(sx: Long, sy: Long, sz: Long): Int {
        val maximum = MAX_BLOCKS.toLong()
        if (sx !in 1..maximum || sy !in 1..maximum || sz !in 1..maximum) {
            throw DebugOperationException("limit_exceeded", "block sample must contain 1..$MAX_BLOCKS blocks")
        }
        val area = sx * sy
        if (area > maximum || sz > maximum / area) {
            throw DebugOperationException("limit_exceeded", "block sample must contain 1..$MAX_BLOCKS blocks")
        }
        return (area * sz).toInt()
    }

    private fun blockPosition(node: JsonNode): BlockPosition {
        val fields = listOf("x", "y", "z")
        if (fields.any { node[it]?.isIntegralNumber != true || !node[it].canConvertToInt() }) {
            throw DebugOperationException("invalid_request", "invalid block coordinate")
        }
        return BlockPosition(node["x"].intValue(), node["y"].intValue(), node["z"].intValue())
    }

    private fun positionJson(position: BlockPosition) = DebugJson.MAPPER.createObjectNode().apply {
        put("x", position.x); put("y", position.y); put("z", position.z)
    }

    private fun encodePng(buffer: TextureBuffer): ByteArray {
        val image = BufferedImage(buffer.size.x, buffer.size.y, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until buffer.size.x) for (y in 0 until buffer.size.y) {
            image.setRGB(x, buffer.size.y - 1 - y, buffer.getRGBA(x, y).argb)
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG encoder is unavailable" }
            output.toByteArray()
        }
    }

    @Synchronized
    override fun close() {
        val current = channel ?: return
        channel = null
        try { current.close() } catch (error: Throwable) { error.printStackTrace() }
    }
}
