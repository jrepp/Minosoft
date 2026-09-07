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
import de.bixilon.kmath.vec.vec2.d.Vec2d
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.minosoft.assets.audit.ContentAssetAudit
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.container.stack.properties.EnchantingProperty
import de.bixilon.minosoft.data.container.stack.properties.NbtProperty
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.block.BeaconBlockEntity
import de.bixilon.minosoft.data.entities.block.container.storage.StorageBlockEntity
import de.bixilon.minosoft.data.entities.block.sign.SignBlockEntity
import de.bixilon.minosoft.data.entities.entities.LightningBolt
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.entities.entities.item.ItemEntity
import de.bixilon.minosoft.data.entities.entities.item.PrimedTNT
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.monster.Creeper
import de.bixilon.minosoft.data.registries.biomes.BiomePrecipitation
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.MinecraftBlocks
import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperties
import de.bixilon.minosoft.data.registries.dimension.effects.minecraft.EndEffects
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.data.world.border.area.BorderArea
import de.bixilon.minosoft.data.world.border.area.StaticBorderArea
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.time.WorldTime
import de.bixilon.minosoft.data.world.weather.WorldWeather
import de.bixilon.minosoft.debug.content.BlockStateSculptureCatalog
import de.bixilon.minosoft.debug.terrain.TerrainDiagnosticDebugOperation
import de.bixilon.minosoft.debug.terrain.TerrainFaultAction
import de.bixilon.minosoft.debug.terrain.TerrainFlushIdleCondition
import de.bixilon.minosoft.debug.terrain.TerrainIdleState
import de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCapture
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticOperations
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejection
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAcceptanceFaultScope
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDualBuildFixtures
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.camera.arm.ArmRenderer
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.border.ChunkBorderRenderer
import de.bixilon.minosoft.gui.rendering.chunk.border.world.WorldBorderRenderer
import de.bixilon.minosoft.gui.rendering.chunk.breaking.BlockBreakRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.types.ChunkMeshTypes
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.feature.armor.VanillaArmorPoseSource
import de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.SkeletalFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.ContentModelInspectable
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.inspectDrawPasses
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.gui.rendering.events.input.CharInputEvent
import de.bixilon.minosoft.gui.rendering.events.input.KeyInputEvent
import de.bixilon.minosoft.gui.rendering.events.input.MouseMoveEvent
import de.bixilon.minosoft.gui.rendering.events.input.MouseScrollEvent
import de.bixilon.minosoft.gui.rendering.framebuffer.world.overlay.overlays.weather.WeatherOverlay
import de.bixilon.minosoft.gui.rendering.framebuffer.world.overlay.overlays.FireOverlay
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.models.loader.ContentReloadRejectedException
import de.bixilon.minosoft.gui.rendering.models.loader.ContentReloadRejectionPoint
import de.bixilon.minosoft.gui.rendering.particle.ParticleRenderer
import de.bixilon.minosoft.gui.rendering.particle.types.render.texture.simple.cloud.SneezeParticle
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.sky.SkyRenderer
import de.bixilon.minosoft.gui.rendering.sky.box.SkyboxRenderer
import de.bixilon.minosoft.gui.rendering.sky.clouds.CloudRenderer
import de.bixilon.minosoft.gui.rendering.sky.planet.scatter.SunScatterRenderer
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlCapabilityDiagnostics
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlWorkSnapshot
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceSnapshot
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureManager
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainPerformanceSnapshot
import de.bixilon.minosoft.gui.rendering.terrain.distant.DistantTerrainRenderer
import de.bixilon.minosoft.local.LocalConnection
import de.bixilon.minosoft.local.datapack.parseLocalBlockState
import de.bixilon.minosoft.modding.loader.ModOptions
import de.bixilon.minosoft.modding.loader.fabric.FabricModDiagnostics
import de.bixilon.minosoft.modding.loader.fabric.FabricResourceReloadEvents
import de.bixilon.minosoft.modding.loader.fabric.FabricResourceReloadType
import de.bixilon.minosoft.modding.event.events.BlockBreakAnimationEvent
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.PlaySessionStates
import de.bixilon.minosoft.terrain.runtime.TerrainProcessBuildService
import de.bixilon.minosoft.util.KUtil.startInit
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import org.lwjgl.opengl.GL11.glFinish

object ClientDebugChannel : AutoCloseable {
    private const val MAX_INPUT_EVENTS = 256
    private const val MAX_PIXEL_SAMPLES = 4096
    private const val MAX_REGION_PIXELS = 65536
    private const val NEAR_BLACK_LUMINANCE = 5.1
    private const val MAX_BLOCKS = 32768
    private const val MAX_PLACE_BLOCKS = 512
    private const val MAX_PLACE_BLOCKS_CHUNKS = 64
    private const val MAX_FUNCTION_ARGUMENTS = 16
    private const val MAX_FUNCTION_ARGUMENT_BYTES = 8192
    private const val CREEPER_OVERLAY_FUSE_TICKS = 20
    private const val DEFAULT_WORLD_BORDER_CANARY_RADIUS = 8.0
    private const val MIN_WORLD_BORDER_CANARY_RADIUS = 2.0
    private const val MAX_WORLD_BORDER_CANARY_RADIUS = 64.0
    private const val LIGHTNING_CANARY_ENTITY_ID = -2_000_000_001
    private const val PRIMED_TNT_CANARY_ENTITY_ID = -2_000_000_003
    private const val ITEM_CANARY_ENTITY_ID = -2_000_000_004
    private const val DEFAULT_LIGHTNING_CANARY_DISTANCE = 4.0
    private const val MIN_LIGHTNING_CANARY_DISTANCE = 1.0
    private const val MAX_LIGHTNING_CANARY_DISTANCE = 16.0
    private const val BLOCK_BREAK_CANARY_ID = -2_000_000_002
    private const val BLOCK_BREAK_CANARY_PROGRESS = 4.0f / 9.0f
    private const val BEACON_CANARY_HORIZONTAL_RADIUS = 6
    private const val BEACON_CANARY_CLEAR_HEIGHT = 8
    private const val STORAGE_BLOCK_ENTITY_CANARY_DISTANCE = 2.0
    private val preparedArmor = mutableMapOf<Int, Map<EquipmentSlots, ItemStack?>>()
    private var preparedBillboardText: PreparedBillboardText? = null
    private val preparedEntityEyes = mutableMapOf<Int, PreparedEntityEyes>()
    private val preparedLeashes = mutableMapOf<Int, PreparedLeash>()
    private val preparedCreeperOverlays = mutableMapOf<Int, PreparedCreeperOverlay>()
    private var preparedTranslucentHeldItem: PreparedHeldItem? = null
    private var preparedWorldBorder: PreparedWorldBorder? = null
    private var preparedSkyTexture: PreparedSkyTexture? = null
    private var preparedSunScatter: PreparedSunScatter? = null
    private var preparedFireOverlay: PreparedFireOverlay? = null
    private var preparedWeather: PreparedWeather? = null
    private var preparedLightning: PreparedLightning? = null
    private var preparedPrimedTnt: PreparedPrimedTnt? = null
    private var preparedItemEntity: PreparedItemEntity? = null
    private var preparedBeacon: PreparedBeacon? = null
    private var preparedTerrainMaterials: PreparedTerrainMaterials? = null
    private var preparedVisualReferenceTime: PreparedVisualReferenceTime? = null
    private var preparedVisualReferenceWeather: PreparedVisualReferenceWeather? = null
    private var preparedStorageBlockEntity: PreparedStorageBlockEntity? = null
    private var preparedSignText: PreparedSignText? = null
    private var preparedBlockBreak: PreparedBlockBreak? = null
    private var preparedTranslucentParticle: PreparedTranslucentParticle? = null
    private var preparedChunkBorder: PreparedChunkBorder? = null
    @Volatile
    private var lastOpenGlWorkCapture: OpenGlWorkCapture? = null

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
            registrations.forEach { registration ->
                try {
                    registration.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            var failure: Throwable? = null
            registrations.forEach { registration ->
                try {
                    registration.close()
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
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
        server.operations().register("client", "visual.capture") { _, body ->
            onRender { capture(it, body.path("includeScene").asBoolean(false)) }
        }
        server.operations().register("client", "visual.sample") { _, body -> onRender { sampleVisual(it, body) } }
        server.operations().register("client", "visual.prepare-reference") { _, body -> onRender { prepareVisualReference(it, body) } }
        server.operations().register("client", "visual.background-throttle") { _, body ->
            onRender { configureBackgroundThrottle(it, body) }
        }
        server.operations().register("client", "state.respawn") { _, _ -> onRender(::respawn) }
        server.operations().register("client", "input.inject") { _, body -> onRender { injectInput(it, body) } }
        server.operations().register("client", "world.blocks.sample") { _, body -> completed(sampleBlocks(body)) }
        server.operations().register("client", "world.aoi") { _, body -> completed(sampleBlocks(body)) }
        server.operations().register("client", "mods.debug") { _, _ -> completed(modDiagnostics()) }
        server.operations().register("client", "content.audit") { _, _ -> onRender(::contentAudit) }
        server.operations().register("client", "render.substrate") { _, _ -> onRender(::renderSubstrate) }
        TerrainDiagnosticDebugOperation.register(server.operations()) { _, body ->
            onRender { terrainDiagnostics(it, body) }
        }
        listOf(
            TerrainDiagnosticOperations.SUMMARY,
            TerrainDiagnosticOperations.PAGES,
            TerrainDiagnosticOperations.COVERAGE,
            TerrainDiagnosticOperations.PAGE,
        ).forEach { operation ->
            server.operations().register("client", operation) { request, body ->
                onRender {
                    terrainDiagnosticOperation(
                        context = it,
                        body = body,
                        endpointGeneration = request.endpoint().generation,
                        operation = operation,
                    )
                }
            }
        }
        server.operations().register("client", TerrainDiagnosticOperations.FLUSH_IDLE) { request, body ->
            onRenderAsync { flushTerrainIdle(request, body, it) }
        }
        server.operations().register("client", TerrainDiagnosticOperations.COMPARE) { request, body ->
            onRender { terrainCompare(it, request.endpoint().generation, body) }
        }
        server.operations().register("client", TerrainDiagnosticOperations.FAULT) { request, body ->
            onRender { terrainFault(it, request.endpoint().generation, body) }
        }
        server.operations().register("client", "render.terrain-telemetry") { _, body ->
            onRender { configureTerrainTelemetry(it, body) }
        }
        server.operations().register("client", "render.prepare-reference-hand") { _, body ->
            onRender { prepareReferenceHand(it, body) }
        }
        server.operations().register("client", "render.reload-content") { _, body -> onRender { reloadContent(it, body) } }
        server.operations().register("client", "render.prepare-entity-flame") { _, body ->
            onRender { prepareEntityFlame(it, body) }
        }
        server.operations().register("client", "render.prepare-billboard-text") { _, body ->
            onRender { prepareBillboardText(it, body) }
        }
        server.operations().register("client", "render.prepare-entity-outline") { _, body ->
            onRender { prepareEntityOutline(it, body) }
        }
        server.operations().register("client", "render.prepare-entity-eyes") { _, body ->
            onRender { prepareEntityEyes(it, body) }
        }
        server.operations().register("client", "render.prepare-leash") { _, body ->
            onRender { prepareLeash(it, body) }
        }
        server.operations().register("client", "render.prepare-vanilla-armor") { _, body ->
            onRender { prepareVanillaArmor(it, body) }
        }
        server.operations().register("client", "render.prepare-creeper-overlay") { _, body ->
            onRender { prepareCreeperOverlay(it, body) }
        }
        server.operations().register("client", "render.prepare-translucent-held-item") { _, body ->
            onRender { prepareTranslucentHeldItem(it, body) }
        }
        server.operations().register("client", "render.prepare-world-border") { _, body ->
            onRender { prepareWorldBorder(it, body) }
        }
        server.operations().register("client", "render.prepare-sky-texture") { _, body ->
            onRender { prepareSkyTexture(it, body) }
        }
        server.operations().register("client", "render.prepare-sun-scatter") { _, body ->
            onRender { prepareSunScatter(it, body) }
        }
        server.operations().register("client", "render.prepare-fire-overlay") { _, body ->
            onRender { prepareFireOverlay(it, body) }
        }
        server.operations().register("client", "render.prepare-weather") { _, body ->
            onRender { prepareWeather(it, body) }
        }
        server.operations().register("client", "render.prepare-lightning") { _, body ->
            onRender { prepareLightning(it, body) }
        }
        server.operations().register("client", "render.prepare-primed-tnt") { _, body ->
            onRender { preparePrimedTnt(it, body) }
        }
        server.operations().register("client", "render.prepare-item-entity") { _, body ->
            onRender { prepareItemEntity(it, body) }
        }
        server.operations().register("client", "render.prepare-beacon") { _, body ->
            onRender { prepareBeacon(it, body) }
        }
        server.operations().register("client", "render.prepare-terrain-materials") { _, body ->
            onRender { prepareTerrainMaterials(it, body) }
        }
        server.operations().register("client", "render.prepare-storage-block-entity") { _, body ->
            onRender { prepareStorageBlockEntity(it, body) }
        }
        server.operations().register("client", "render.prepare-sign-text") { _, body ->
            onRender { prepareSignText(it, body) }
        }
        server.operations().register("client", "render.prepare-block-break") { _, body ->
            onRender { prepareBlockBreak(it, body) }
        }
        server.operations().register("client", "render.prepare-translucent-particle") { _, body ->
            onRender { prepareTranslucentParticle(it, body) }
        }
        server.operations().register("client", "render.prepare-chunk-border") { _, body ->
            onRender { prepareChunkBorder(it, body) }
        }
        server.operations().register("client", "content.execute-local") { _, body -> onRender { executeLocalContent(it, body) } }
        server.operations().register("client", "content.place-blocks") { _, body -> onRender { placeLocalBlocks(it, body) } }
        server.operations().register("client", "content.place-block-state-sculpture") { _, body ->
            onRender { placeBlockStateSculpture(it, body) }
        }
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
                val timing = it.renderStats.timingSnapshot
                put("timingSamples", timing.samples)
                put("medianFrameNanos", timing.medianFrameNanos)
                put("p95FrameNanos", timing.p95FrameNanos)
                put("medianDrawNanos", timing.medianDrawNanos)
                put("p95DrawNanos", timing.p95DrawNanos)
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
            context.renderer[ChunkRenderer]?.terrainPerformance?.snapshot()?.let {
                putTerrainPerformance("terrain", it, includeBuckets = false)
            }
            (context.system as? OpenGlRenderSystem)?.work?.snapshot()?.let {
                putOpenGlWork("openGlWork", it)
            }
        }
        val sessions = PlaySession.collectSessions()
        put("sessions", sessions.size)
        put("playingSessions", sessions.count { it.state == PlaySessionStates.PLAYING })
    }

    private fun contentAudit(context: RenderContext): DebugOperationResult {
        val snapshot = context.contentAssetAudit.snapshot()
        val known = context.session.assets.list()
        val result = DebugJson.MAPPER.createObjectNode().apply {
            put("schema", ContentAssetAudit.SCHEMA)
            put("auditVersion", ContentAssetAudit.AUDIT_VERSION)
            put("minecraftVersion", context.session.version.name)
            put("fingerprint", snapshot.fingerprint)
            put("complete", snapshot.entries.isEmpty())
            put("truncated", snapshot.truncated)
            putObject("inventory").apply {
                put("resources", known.size)
                put("blockstates", known.count { it.path.startsWith("blockstates/") && it.path.endsWith(".json") })
                put("models", known.count { it.path.startsWith("models/") && it.path.endsWith(".json") })
                put("textures", known.count { it.path.startsWith("textures/") && it.path.endsWith(".png") })
            }
            putObject("counts").apply {
                put("blockstates", snapshot.count(ContentAssetAudit.Kind.BLOCKSTATE))
                put("models", snapshot.count(ContentAssetAudit.Kind.MODEL))
                put("textures", snapshot.count(ContentAssetAudit.Kind.TEXTURE))
                put("total", snapshot.entries.size)
            }
            val missing = putObject("missing")
            for (kind in ContentAssetAudit.Kind.entries) {
                val entries = missing.putArray(kind.wireName + "s")
                snapshot.entries.asSequence().filter { it.kind == kind }.forEach { entry ->
                    entries.addObject().apply {
                        put("resource", entry.resource)
                        put("target", entry.target)
                        put("consumersTruncated", entry.consumersTruncated)
                        putArray("consumers").also { consumers -> entry.consumers.forEach(consumers::add) }
                    }
                }
            }
        }
        return DebugOperationResult.json(result)
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
                put("yaw", player.physics.rotation.yaw)
                put("pitch", player.physics.rotation.pitch)
                put("health", player.health)
                put("gamemode", player.gamemode.name.lowercase())
                put("sprinting", player.isSprinting)
            }
            result.putObject("worldState").apply {
                put("dimension", session.world.name?.toString())
                put("time", session.world.time.time)
                put("age", session.world.time.age)
                put("dayPhase", session.world.time.phase.name.lowercase())
                put("presentationTime", session.world.presentationTime.time)
                put("presentationDayPhase", session.world.presentationTime.phase.name.lowercase())
                put("presentationTimeOverridden", session.world.presentationTimeOverride != null)
                putObject("weather").apply {
                    put("rain", session.world.weather.rain)
                    put("thunder", session.world.weather.thunder)
                }
                putObject("presentationWeather").apply {
                    put("rain", session.world.presentationWeather.rain)
                    put("thunder", session.world.presentationWeather.thunder)
                    put("overridden", session.world.presentationWeatherOverride != null)
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
                                put("uuid", entity.uuid?.toString())
                                put("type", entity.type.identifier.toString())
                                put("x", position.x); put("y", position.y); put("z", position.z)
                                put("invisible", entity.isInvisible)
                                put("glowing", entity.hasGlowingEffect)
                                put("onFire", entity.isOnFire)
                                put("renderer", entityRenderer?.javaClass?.simpleName)
                                put("visibility", entityRenderer?.visibility?.name?.lowercase())
                                put("features", entityRenderer?.features?.count() ?: 0)
                                (entityRenderer as? PlayerRenderer<*>)?.let { playerRenderer ->
                                    val playerModel = playerRenderer.model
                                    put("playerModelRetained", playerModel != null)
                                    put("playerModelEnabled", playerModel?.enabled == true)
                                    playerModel?.instance?.let { instance ->
                                        put("playerModelState", instance.state.name.lowercase())
                                        put("playerModelVertices", instance.model.mesh.buffer.vertices)
                                        put("playerModelTransforms", instance.model.transformCount)
                                        putObject("playerModelTranslation").apply {
                                            put("x", instance.matrix[0].w)
                                            put("y", instance.matrix[1].w)
                                            put("z", instance.matrix[2].w)
                                        }
                                        putObject("playerModelScale").apply {
                                            put("x", instance.matrix[0].x)
                                            put("y", instance.matrix[1].y)
                                            put("z", instance.matrix[2].z)
                                        }
                                        val root = instance.transform.renderMatrix
                                        putObject("playerModelRootTranslation").apply {
                                            put("x", root[0].w)
                                            put("y", root[1].w)
                                            put("z", root[2].w)
                                        }
                                        putObject("playerModelRootScale").apply {
                                            put("x", root[0].x)
                                            put("y", root[1].y)
                                            put("z", root[2].z)
                                        }
                                        val world = root * Vec4f(0.0f, 1.0f, 0.0f, 1.0f)
                                        renderContextOrNull()?.let { renderContext ->
                                            val clip = renderContext.camera.matrix.viewProjectionMatrix * world
                                            putObject("playerModelClip").apply {
                                                put("x", clip.x)
                                                put("y", clip.y)
                                                put("z", clip.z)
                                                put("w", clip.w)
                                            }
                                        }
                                    }
                                }
                                renderContextOrNull()?.models?.skeletal?.let { skeletal ->
                                    skeletal.contentModel(entity.type.identifier)?.let { route ->
                                        put("contentRoute", route.toString())
                                        skeletal[route]?.let { routed ->
                                            routed.contentIdentity?.let { identity ->
                                                put("routedContentFormat", identity.format.name.lowercase())
                                                put("routedContentSource", identity.source.toString())
                                                put("routedContentGeometry", identity.identifier)
                                            }
                                            val textures = skeletal.entityTextures(entity, routed)
                                            textures.values.singleOrNull()
                                                ?.let { put("routedContentTexture", it.base.toString()) }
                                            val passes = routed.inspectDrawPasses(textures)
                                            put("routedContentBaseVertices", passes.baseVertices)
                                            put("routedContentSelectedTexturePasses", passes.selectedTexturePasses)
                                            put("routedContentSelectedTextureVertices", passes.selectedTextureVertices)
                                            put("routedContentGeometryPasses", passes.geometryPasses)
                                            put("routedContentEmissivePasses", passes.emissivePasses)
                                            put("routedContentEmissiveVertices", passes.emissiveVertices)
                                            put("routedContentGeckoLayerCandidates", passes.geckoLayerCandidates)
                                            put("routedContentGeckoLayerCandidateVertices", passes.geckoLayerCandidateVertices)
                                            put("routedContentKnownDrawPasses", passes.knownDrawPasses)
                                        }
                                    }
                                }
                                val contentInspectable = entityRenderer as? ContentModelInspectable
                                contentInspectable?.retainedContentModel?.let { model ->
                                    model.contentIdentity?.let { identity ->
                                        put("contentFormat", identity.format.name.lowercase())
                                        put("contentSource", identity.source.toString())
                                        put("contentGeometry", identity.identifier)
                                    }
                                    renderContextOrNull()?.models?.skeletal?.let { skeletal ->
                                        val textures = skeletal.entityTextures(entity, model)
                                        textures.values.singleOrNull()
                                            ?.let { put("contentTexture", it.base.toString()) }
                                        val passes = model.inspectDrawPasses(textures)
                                        put("contentBaseVertices", passes.baseVertices)
                                        put("contentSelectedTexturePasses", passes.selectedTexturePasses)
                                        put("contentSelectedTextureVertices", passes.selectedTextureVertices)
                                        put("contentGeometryPasses", passes.geometryPasses)
                                        put("contentEmissivePasses", passes.emissivePasses)
                                        put("contentEmissiveVertices", passes.emissiveVertices)
                                        put("contentGeckoLayerCandidates", passes.geckoLayerCandidates)
                                        put("contentGeckoLayerCandidateVertices", passes.geckoLayerCandidateVertices)
                                        put("contentKnownDrawPasses", passes.knownDrawPasses)
                                    }
                                }
                                contentInspectable?.retainedContentControllers
                                    ?.takeIf { it.controllerCount > 0 }
                                    ?.let { inspection ->
                                        put("contentControllerCount", inspection.controllerCount)
                                        put("contentControllersTruncated", inspection.truncated)
                                        val controllers = putArray("contentControllers")
                                        for (controller in inspection.controllers) {
                                            controllers.addObject().apply {
                                                put("name", controller.name)
                                                controller.currentClip?.let { put("currentClip", it) }
                                                put("elapsedSeconds", controller.elapsedSeconds)
                                                controller.remainingSeconds?.let { put("remainingSeconds", it) }
                                                put("transitionElapsedSeconds", controller.transitionElapsedSeconds)
                                                put("transitionDurationSeconds", controller.transitionDurationSeconds)
                                                put("triggered", controller.triggered)
                                                put("queued", controller.queued)
                                                controller.queueStageIndex?.let { put("queueStageIndex", it) }
                                                controller.queueStageCount?.let { put("queueStageCount", it) }
                                                controller.queueCurrentAnimation?.let { put("queueCurrentAnimation", it) }
                                                controller.queueWaitRemainingSeconds?.let {
                                                    put("queueWaitRemainingSeconds", it)
                                                }
                                                controller.completedCycles?.let { put("completedCycles", it) }
                                                put("held", controller.held)
                                                put("rawFinished", controller.rawFinished)
                                            }
                                        }
                                    }
                                put("passengers", entity.attachment.passengers.size)
                                if (entity.commandTags.isNotEmpty()) {
                                    val tags = putArray("tags")
                                    entity.commandTags.sorted().take(32).forEach(tags::add)
                                    put("tagCount", entity.commandTags.size)
                                }
                                if (entity is ItemDisplayEntity) {
                                    (entity.stack?.nbt?.nbt?.get("CustomModelData") as? Number)?.let {
                                        put("customModelData", it.toInt())
                                    }
                                }
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

    private fun capture(context: RenderContext, includeScene: Boolean = false): DebugOperationResult {
        val screenshot = context.screenshotTaker.capture()
        validateFramebuffer(screenshot.size)
        val bytes = encodePng(screenshot.buffer)
        val metadata = DebugJson.MAPPER.createObjectNode().apply {
            put("width", screenshot.size.x); put("height", screenshot.size.y)
            put("frame", screenshot.frame)
            put("capturedAt", screenshot.capturedAt.toString())
            put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            put("suggestedFilename", screenshot.suggestedFilename)
            put("userScreenshotDirectory", context.screenshotTaker.userDirectory.toString())
            put("pixelFormat", "rgba8")
            put("origin", "top-left")
            if (includeScene) set<ObjectNode>("scene", SceneReviewCapture.capture(context))
        }
        return DebugOperationResult.attachment(metadata, bytes, "image/png", screenshot.suggestedFilename)
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
        (context.textures as? OpenGlTextureManager)?.static
            ?.materialAnimationDiagnostics()
            ?.let { animations ->
                result.putArray("materialAnimationStates").also { states ->
                    animations.states.forEach { state ->
                        states.addObject().apply {
                            put("kind", state.kind)
                            put("resource", state.resource)
                            put("uploadedFrameIndex", state.uploadedFrameIndex)
                        }
                    }
                }
            }
        return DebugOperationResult.json(result)
    }

    /**
     * Removes transient GUI state before a checked framebuffer capture. This is
     * intentionally narrower than input injection: it cannot open a screen or
     * mutate gameplay, and it makes a reference run independent of whether the
     * window previously lost focus or displayed the pause menu.
     */
    @Synchronized
    private fun prepareVisualReference(context: RenderContext, body: JsonNode): DebugOperationResult {
        val timeOfDayNode = body["timeOfDay"]
        if (timeOfDayNode != null && !timeOfDayNode.canConvertToLong()) {
            throw DebugOperationException("invalid_request", "timeOfDay must be an integer from 0 through 23999")
        }
        val restoreTimeNode = body["restoreTime"]
        if (restoreTimeNode != null && !restoreTimeNode.isBoolean) {
            throw DebugOperationException("invalid_request", "restoreTime must be boolean")
        }
        val timeOfDay = timeOfDayNode?.asLong()
        if (timeOfDay != null && timeOfDay !in 0L..23_999L) {
            throw DebugOperationException("invalid_request", "timeOfDay must be an integer from 0 through 23999")
        }
        val restoreTime = restoreTimeNode?.asBoolean() ?: false
        if (timeOfDay != null && restoreTime) {
            throw DebugOperationException("invalid_request", "timeOfDay and restoreTime are mutually exclusive")
        }
        val clearWeatherNode = body["clearWeather"]
        if (clearWeatherNode != null && !clearWeatherNode.isBoolean) {
            throw DebugOperationException("invalid_request", "clearWeather must be boolean")
        }
        val restoreWeatherNode = body["restoreWeather"]
        if (restoreWeatherNode != null && !restoreWeatherNode.isBoolean) {
            throw DebugOperationException("invalid_request", "restoreWeather must be boolean")
        }
        val clearWeather = clearWeatherNode?.asBoolean() ?: false
        val restoreWeather = restoreWeatherNode?.asBoolean() ?: false
        if (clearWeather && restoreWeather) {
            throw DebugOperationException("invalid_request", "clearWeather and restoreWeather are mutually exclusive")
        }
        val hideHudNode = body["hideHud"]
        if (hideHudNode != null && !hideHudNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideHud must be boolean")
        }
        val hideHitboxesNode = body["hideHitboxes"]
        if (hideHitboxesNode != null && !hideHitboxesNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideHitboxes must be boolean")
        }
        val hideCloudsNode = body["hideClouds"]
        if (hideCloudsNode != null && !hideCloudsNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideClouds must be boolean")
        }
        val hideWorldBorderNode = body["hideWorldBorder"]
        if (hideWorldBorderNode != null && !hideWorldBorderNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideWorldBorder must be boolean")
        }
        val hideEntitiesNode = body["hideEntities"]
        if (hideEntitiesNode != null && !hideEntitiesNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideEntities must be boolean")
        }
        val hideParticlesNode = body["hideParticles"]
        if (hideParticlesNode != null && !hideParticlesNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideParticles must be boolean")
        }
        val hideArmNode = body["hideArm"]
        if (hideArmNode != null && !hideArmNode.isBoolean) {
            throw DebugOperationException("invalid_request", "hideArm must be boolean")
        }
        val hideHud = hideHudNode?.asBoolean() ?: true
        val hideHitboxes = hideHitboxesNode?.asBoolean() ?: true
        val hideClouds = hideCloudsNode?.asBoolean() ?: true
        val hideWorldBorder = hideWorldBorderNode?.asBoolean() ?: false
        val hideEntities = hideEntitiesNode?.asBoolean() ?: false
        val hideParticles = hideParticlesNode?.asBoolean() ?: false
        val hideArm = hideArmNode?.asBoolean() ?: false
        val gui = context.renderer[GUIRenderer]
            ?: throw DebugOperationException("not_ready", "GUI renderer is not active")
        val world = context.session.world
        val preparedTime = preparedVisualReferenceTime
        if (timeOfDay != null && preparedTime != null && preparedTime.session !== context.session) {
            throw DebugOperationException("not_ready", "the prepared visual-reference world is no longer active")
        }
        if (restoreTime && preparedTime == null) {
            throw DebugOperationException("invalid_request", "no visual-reference time is prepared")
        }
        if (restoreTime && preparedTime?.session !== context.session) {
            throw DebugOperationException("not_ready", "the prepared visual-reference world is no longer active")
        }
        val preparedWeather = preparedVisualReferenceWeather
        if (clearWeather && preparedWeather != null && preparedWeather.session !== context.session) {
            throw DebugOperationException("not_ready", "the prepared visual-reference world is no longer active")
        }
        if (restoreWeather && preparedWeather == null) {
            throw DebugOperationException("invalid_request", "no visual-reference weather is prepared")
        }
        if (restoreWeather && preparedWeather?.session !== context.session) {
            throw DebugOperationException("not_ready", "the prepared visual-reference world is no longer active")
        }
        if (timeOfDay != null) {
            val appliedOverride = WorldTime(timeOfDay.toInt(), world.time.age)
            if (preparedTime == null) {
                preparedVisualReferenceTime = PreparedVisualReferenceTime(
                    context.session,
                    world.presentationTimeOverride,
                    appliedOverride,
                )
            } else {
                preparedTime.appliedOverride = appliedOverride
            }
            world.presentationTimeOverride = appliedOverride
        } else if (restoreTime) {
            world.presentationTimeOverride = requireNotNull(preparedTime).previousOverride
            preparedVisualReferenceTime = null
        }
        if (clearWeather) {
            val appliedOverride = WorldWeather.SUNNY
            if (preparedWeather == null) {
                preparedVisualReferenceWeather = PreparedVisualReferenceWeather(
                    context.session,
                    world.presentationWeatherOverride,
                    appliedOverride,
                )
            } else {
                preparedWeather.appliedOverride = appliedOverride
            }
            world.presentationWeatherOverride = appliedOverride
        } else if (restoreWeather) {
            world.presentationWeatherOverride = requireNotNull(preparedWeather).previousOverride
            preparedVisualReferenceWeather = null
        }
        gui.gui.clear()
        gui.hud.enabled = !hideHud
        if (hideHitboxes) {
            context.renderer[EntitiesRenderer]?.features?.hitbox?.enabled = false
        }
        context.renderer[CloudRenderer]?.referenceSuppressed = hideClouds
        context.renderer[WorldBorderRenderer]?.referenceSuppressed = hideWorldBorder
        context.renderer[EntitiesRenderer]?.referenceSuppressed = hideEntities
        context.renderer[ParticleRenderer]?.referenceSuppressed = hideParticles
        context.renderer[ArmRenderer]?.referenceSuppressed = hideArm
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("overlaysCleared", true)
            put("hudEnabled", gui.hud.enabled)
            put("hitboxesEnabled", context.renderer[EntitiesRenderer]?.features?.hitbox?.enabled)
            put("cloudsSuppressed", context.renderer[CloudRenderer]?.referenceSuppressed)
            put("worldBorderSuppressed", context.renderer[WorldBorderRenderer]?.referenceSuppressed)
            put("entitiesSuppressed", context.renderer[EntitiesRenderer]?.referenceSuppressed)
            put("particlesSuppressed", context.renderer[ParticleRenderer]?.referenceSuppressed)
            put("armSuppressed", context.renderer[ArmRenderer]?.referenceSuppressed)
            put("time", world.presentationTime.time)
            put("authoritativeTime", world.time.time)
            put("timePrepared", preparedVisualReferenceTime != null)
            put("rain", world.presentationWeather.rain)
            put("authoritativeRain", world.weather.rain)
            put("weatherPrepared", preparedVisualReferenceWeather != null)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedVisualReferenceTime(
        val session: PlaySession,
        val previousOverride: WorldTime?,
        var appliedOverride: WorldTime,
    )

    private data class PreparedVisualReferenceWeather(
        val session: PlaySession,
        val previousOverride: WorldWeather?,
        var appliedOverride: WorldWeather,
    )

    /**
     * Compare-and-set control for the non-persistent background frame limiter.
     * This lets a bounded visual measurement remain representative while the
     * terminal owns focus without changing the user's rendering profile.
     */
    private fun configureBackgroundThrottle(context: RenderContext, body: JsonNode): DebugOperationResult {
        val expected = body.path("expected").asText("")
        val value = body.path("value").asText("")
        if (expected !in setOf("default", "enabled", "disabled")) {
            throw DebugOperationException("invalid_request", "expected must be default, enabled, or disabled")
        }
        if (value !in setOf("default", "enabled", "disabled")) {
            throw DebugOperationException("invalid_request", "value must be default, enabled, or disabled")
        }
        val current = backgroundThrottleState(context.backgroundThrottleOverride)
        if (current != expected) {
            throw DebugOperationException(
                "state_conflict",
                "background throttle is $current, expected $expected"
            )
        }
        context.backgroundThrottleOverride = when (value) {
            "default" -> null
            "enabled" -> true
            else -> false
        }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("previous", current)
            put("current", value)
            put("profileSlowRendering", context.profile.performance.slowRendering)
            put("frame", context.frameNumber)
        })
    }

    private fun backgroundThrottleState(value: Boolean?): String = when (value) {
        null -> "default"
        true -> "enabled"
        false -> "disabled"
    }

    /**
     * Reversibly replaces only the first-person arm's sampled texture with the
     * generated high-contrast reference skin. The player model and account
     * skin remain untouched.
     */
    private fun prepareReferenceHand(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode == null || !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be a boolean")
        }
        val renderer = context.renderer[ArmRenderer]
            ?: throw DebugOperationException("not_ready", "first-person arm renderer is not active")
        val previous = renderer.referenceSkinEnabled
        try {
            renderer.setReferenceSkinEnabled(enabledNode.booleanValue())
        } catch (error: IllegalStateException) {
            throw DebugOperationException("not_ready", error.message ?: "reference skin is unavailable")
        }
        val ordinarySkin = renderer.ordinarySkinDiagnostics()
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("previousEnabled", previous)
            put("enabled", renderer.referenceSkinEnabled)
            put("texture", if (renderer.referenceSkinEnabled) "minosoft:debug/reference_hand_skin" else null)
            put("referenceTextureShaderId", renderer.referenceSkinShaderId)
            put("lastArmTextureShaderId", renderer.lastArmTextureShaderId)
            put(
                "referenceTextureBound",
                renderer.referenceSkinShaderId != null &&
                    renderer.lastArmTextureShaderId == renderer.referenceSkinShaderId,
            )
            put("armDraws", renderer.armDraws)
            put("lastArmDrawFrame", renderer.lastArmDrawFrame)
            ordinarySkin?.let { skin ->
                putObject("ordinarySkin").also { output ->
                    fun ObjectNode.putTexture(name: String, texture: de.bixilon.minosoft.gui.rendering.camera.arm.ArmSkinTextureDiagnostics?) {
                        if (texture == null) {
                            putNull(name)
                            return
                        }
                        putObject(name).apply {
                            put("shaderId", texture.shaderId)
                            put("state", texture.state)
                            put("width", texture.width)
                            put("height", texture.height)
                            put("visiblePixels", texture.visiblePixels)
                            put("nonBlackVisiblePixels", texture.nonBlackVisiblePixels)
                            put("armNonBlackVisiblePixels", texture.armNonBlackVisiblePixels)
                        }
                    }
                    output.putTexture("selected", skin.selected)
                    output.putTexture("frame", skin.frame)
                    output.putTexture("fallback", skin.fallback)
                }
            }
            put("playerSkinUnchanged", true)
            put("frame", context.frameNumber)
        })
    }

    /**
     * Toggles the synchronized fire flag on one retained client entity so a
     * supervised render run can exercise the exact entity-flame producer even
     * when the remote command tree is unavailable. The caller receives the
     * previous value and can restore it with the same entity ID.
     */
    private fun prepareEntityFlame(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val requestedId = entityIdNode?.asInt()
        val session = context.session
        val origin = session.player.physics.position
        val entity = session.world.entities.lock.acquired {
            session.world.entities.entities
                .asSequence()
                .filter { it !== session.player }
                .filter { it.renderer?.visibility == EntityVisibilityLevels.VISIBLE }
                .filter { requestedId == null || it.id == requestedId }
                .minByOrNull {
                    val position = it.physics.position
                    val dx = position.x - origin.x
                    val dy = position.y - origin.y
                    val dz = position.z - origin.z
                    dx * dx + dy * dy + dz * dz
                }
        } ?: throw DebugOperationException(
            "not_ready",
            if (requestedId == null) "no visible non-player entity is retained"
            else "visible entity $requestedId is not retained",
        )
        val previous = entity.isOnFire
        entity.isOnFire = enabled
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("entityId", entity.id)
            put("entityType", entity.type.identifier.toString())
            put("previous", previous)
            put("enabled", entity.isOnFire)
            put("frame", context.frameNumber)
        })
    }

    /**
     * Publishes one bounded client-only custom name through ordinary tracked
     * entity data. The existing EntityNameFeature, font mesh, billboard
     * transform, graph layer, and selected Iris program remain the only draw
     * path. Disable restores the exact prior name and visibility values.
     */
    private fun prepareBillboardText(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session

        val entity = if (enabled) {
            if (preparedBillboardText != null) {
                throw DebugOperationException("invalid_request", "a billboard-text canary is already prepared")
            }
            val requestedId = entityIdNode?.asInt()
            val origin = session.player.physics.position
            val selected = session.world.entities.lock.acquired {
                session.world.entities.entities
                    .asSequence()
                    .filter { it !== session.player }
                    .filter { it.renderer != null }
                    .filter { requestedId == null || it.id == requestedId }
                    .minByOrNull {
                        val position = it.physics.position
                        val dx = position.x - origin.x
                        val dy = position.y - origin.y
                        val dz = position.z - origin.z
                        dx * dx + dy * dy + dz * dz
                    }
            } ?: throw DebugOperationException(
                "not_ready",
                if (requestedId == null) "no non-player entity renderer is retained"
                else "entity renderer $requestedId is not retained",
            )
            preparedBillboardText = PreparedBillboardText(
                session,
                selected,
                selected.customName,
                selected.isNameVisible,
                selected.renderer?.referenceVisibilityOverride,
            )
            selected.renderer?.referenceVisibilityOverride = EntityVisibilityLevels.VISIBLE
            selected.data[de.bixilon.minosoft.data.entities.entities.Entity.CUSTOM_NAME_DATA] =
                ChatComponent.of("Iris billboard basis")
            selected.data[de.bixilon.minosoft.data.entities.entities.Entity.CUSTOM_NAME_VISIBLE_DATA] = true
            selected
        } else {
            val prepared = preparedBillboardText
                ?: throw DebugOperationException("invalid_request", "no billboard-text canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared billboard-text world is no longer active")
            }
            prepared.entity.data[de.bixilon.minosoft.data.entities.entities.Entity.CUSTOM_NAME_DATA] =
                prepared.previousName
            prepared.entity.data[de.bixilon.minosoft.data.entities.entities.Entity.CUSTOM_NAME_VISIBLE_DATA] =
                prepared.previousVisible
            prepared.entity.renderer?.referenceVisibilityOverride = prepared.previousVisibilityOverride
            preparedBillboardText = null
            prepared.entity
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("entityId", entity.id)
            put("entityType", entity.type.identifier.toString())
            put("name", entity.customName?.message)
            put("nameVisible", entity.isNameVisible)
            put("restored", !enabled)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedBillboardText(
        val session: PlaySession,
        val entity: de.bixilon.minosoft.data.entities.entities.Entity,
        val previousName: ChatComponent?,
        val previousVisible: Boolean,
        val previousVisibilityOverride: EntityVisibilityLevels?,
    )

    /**
     * Toggles one retained entity's synchronized glowing flag so the complete
     * offscreen mask/composite path can be accepted without server commands.
     * The returned previous value supports exact restoration.
     */
    private fun prepareEntityOutline(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val requestedId = entityIdNode?.asInt()
        val session = context.session
        val origin = session.player.physics.position
        val entity = session.world.entities.lock.acquired {
            session.world.entities.entities
                .asSequence()
                .filter { it !== session.player }
                .filter { it.renderer?.visibility == EntityVisibilityLevels.VISIBLE }
                .filter { requestedId == null || it.id == requestedId }
                .minByOrNull {
                    val position = it.physics.position
                    val dx = position.x - origin.x
                    val dy = position.y - origin.y
                    val dz = position.z - origin.z
                    dx * dx + dy * dy + dz * dz
                }
        } ?: throw DebugOperationException(
            "not_ready",
            if (requestedId == null) "no visible non-player entity is retained"
            else "visible entity $requestedId is not retained",
        )
        val previous = entity.hasGlowingEffect
        entity.hasGlowingEffect = enabled
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("entityId", entity.id)
            put("entityType", entity.type.identifier.toString())
            put("previous", previous)
            put("enabled", entity.hasGlowingEffect)
            put("clientOnly", true)
            put("frame", context.frameNumber)
        })
    }

    /**
     * Routes one retained skeletal entity's already resolved base material
     * through its production emissive layer. This proves the exact
     * ENTITY_EYES program without inventing debug geometry or changing the
     * entity, its authoritative assets, or server state.
     */
    private fun prepareEntityEyes(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val requestedId = entityIdNode?.asInt()
        val session = context.session

        if (enabled) {
            val origin = session.player.physics.position
            val candidate = session.world.entities.lock.acquired {
                session.world.entities.entities
                    .asSequence()
                    .filter { it !== session.player }
                    .mapNotNull { entity ->
                        val renderer = entity.renderer ?: return@mapNotNull null
                        val feature = renderer.features.asSequence()
                            .filterIsInstance<SkeletalFeature>()
                            .firstOrNull { it.referenceEmissiveBaseMeshCount > 0 }
                            ?: return@mapNotNull null
                        if (requestedId != null && entity.id != requestedId) return@mapNotNull null
                        Triple(entity, renderer, feature)
                    }
                    .minWithOrNull(compareBy(
                        { if (it.second.visibility == EntityVisibilityLevels.VISIBLE) 0 else 1 },
                        {
                            val position = it.first.physics.position
                            val dx = position.x - origin.x
                            val dy = position.y - origin.y
                            val dz = position.z - origin.z
                            dx * dx + dy * dy + dz * dz
                        },
                    ))
            } ?: throw DebugOperationException(
                "not_ready",
                if (requestedId == null) "no retained skeletal entity has a base emissive canary material"
                else "skeletal entity $requestedId has no base emissive canary material",
            )
            val (entity, renderer, feature) = candidate
            val entityId = entity.id
                ?: throw DebugOperationException("not_ready", "selected skeletal entity has no numeric ID")
            if (preparedEntityEyes.containsKey(entityId)) {
                throw DebugOperationException("invalid_request", "entity-eyes canary $entityId is already prepared")
            }
            val prepared = PreparedEntityEyes(
                renderer = renderer,
                feature = feature,
                previousVisibilityOverride = renderer.referenceVisibilityOverride,
                previousEmissiveBaseOverride = feature.referenceEmissiveBaseOverride,
            )
            renderer.referenceVisibilityOverride = EntityVisibilityLevels.VISIBLE
            feature.referenceEmissiveBaseOverride = true
            preparedEntityEyes[entityId] = prepared
            return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
                put("entityId", entityId)
                put("entityType", entity.type.identifier.toString())
                put("meshCount", feature.referenceEmissiveBaseMeshCount)
                put("previousEmissiveBaseOverride", prepared.previousEmissiveBaseOverride)
                put("emissiveBaseOverride", feature.referenceEmissiveBaseOverride)
                put("visibilityOverride", renderer.referenceVisibilityOverride?.name?.lowercase())
                put("clientOnly", true)
                put("frame", context.frameNumber)
            })
        }

        val restoreId = requestedId ?: preparedEntityEyes.keys.singleOrNull()
            ?: throw DebugOperationException(
                "invalid_request",
                "entityId is required unless exactly one entity-eyes canary is prepared",
            )
        val prepared = preparedEntityEyes.remove(restoreId)
            ?: throw DebugOperationException("invalid_request", "entity-eyes canary $restoreId is not prepared")
        var emissiveRestored = false
        if (prepared.feature.referenceEmissiveBaseOverride) {
            prepared.feature.referenceEmissiveBaseOverride = prepared.previousEmissiveBaseOverride
            emissiveRestored = true
        }
        var visibilityRestored = false
        if (prepared.renderer.referenceVisibilityOverride == EntityVisibilityLevels.VISIBLE) {
            prepared.renderer.referenceVisibilityOverride = prepared.previousVisibilityOverride
            visibilityRestored = true
        }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("entityId", restoreId)
            put("meshCount", prepared.feature.referenceEmissiveBaseMeshCount)
            put("emissiveBaseOverride", prepared.feature.referenceEmissiveBaseOverride)
            put("emissiveRestored", emissiveRestored)
            put("visibilityOverride", prepared.renderer.referenceVisibilityOverride?.name?.lowercase())
            put("visibilityRestored", visibilityRestored)
            put("clientOnly", true)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedEntityEyes(
        val renderer: de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer<*>,
        val feature: SkeletalFeature,
        val previousVisibilityOverride: EntityVisibilityLevels?,
        val previousEmissiveBaseOverride: Boolean,
    )

    /**
     * Temporarily attaches one retained living entity to the local player.
     * The synchronized client attachment, normal leash feature, EMF-adjusted
     * mob anchor, light interpolation, and retained ribbon shader remain the
     * complete draw path.
     */
    private fun prepareLeash(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val requestedId = entityIdNode?.asInt()
        val session = context.session

        if (enabled) {
            val origin = session.player.physics.position
            val entity = session.world.entities.lock.acquired {
                session.world.entities.entities
                    .asSequence()
                    .filterIsInstance<LivingEntity>()
                    .filter { it !== session.player }
                    .filter { it.renderer is LivingEntityRenderer<*> }
                    .filter { requestedId == null || it.id == requestedId }
                    .minWithOrNull(compareBy(
                        { if (it.renderer?.visibility == EntityVisibilityLevels.VISIBLE) 0 else 1 },
                        {
                            val position = it.physics.position
                            val dx = position.x - origin.x
                            val dy = position.y - origin.y
                            val dz = position.z - origin.z
                            dx * dx + dy * dy + dz * dz
                        },
                    ))
            } ?: throw DebugOperationException(
                "not_ready",
                if (requestedId == null) "no retained non-player living entity is available for a leash"
                else "living entity $requestedId is not retained",
            )
            val entityId = entity.id
                ?: throw DebugOperationException("not_ready", "selected leash entity has no numeric ID")
            if (preparedLeashes.containsKey(entityId)) {
                throw DebugOperationException("invalid_request", "leash canary $entityId is already prepared")
            }
            val renderer = entity.renderer
                ?: throw DebugOperationException("not_ready", "selected leash entity has no renderer")
            val prepared = PreparedLeash(
                entity = entity,
                renderer = renderer,
                previousHolder = entity.attachment.leashHolder,
                previousVisibilityOverride = renderer.referenceVisibilityOverride,
            )
            entity.attachment.leashHolder = session.player
            renderer.referenceVisibilityOverride = EntityVisibilityLevels.VISIBLE
            preparedLeashes[entityId] = prepared
            return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
                put("entityId", entityId)
                put("entityType", entity.type.identifier.toString())
                put("previousHolderId", prepared.previousHolder?.id)
                put("holderId", entity.attachment.leashHolder?.id)
                put("visibilityOverride", renderer.referenceVisibilityOverride?.name?.lowercase())
                put("clientOnly", true)
                put("frame", context.frameNumber)
            })
        }

        val restoreId = requestedId ?: preparedLeashes.keys.singleOrNull()
            ?: throw DebugOperationException(
                "invalid_request",
                "entityId is required unless exactly one leash canary is prepared",
            )
        val prepared = preparedLeashes.remove(restoreId)
            ?: throw DebugOperationException("invalid_request", "leash canary $restoreId is not prepared")
        var holderRestored = false
        if (prepared.entity.attachment.leashHolder === session.player) {
            prepared.entity.attachment.leashHolder = prepared.previousHolder
            holderRestored = true
        }
        var visibilityRestored = false
        if (prepared.renderer.referenceVisibilityOverride == EntityVisibilityLevels.VISIBLE) {
            prepared.renderer.referenceVisibilityOverride = prepared.previousVisibilityOverride
            visibilityRestored = true
        }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("entityId", restoreId)
            put("entityType", prepared.entity.type.identifier.toString())
            put("holderId", prepared.entity.attachment.leashHolder?.id)
            put("holderRestored", holderRestored)
            put("visibilityOverride", prepared.renderer.referenceVisibilityOverride?.name?.lowercase())
            put("visibilityRestored", visibilityRestored)
            put("clientOnly", true)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedLeash(
        val entity: LivingEntity,
        val renderer: de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer<*>,
        val previousHolder: de.bixilon.minosoft.data.entities.entities.Entity?,
        val previousVisibilityOverride: EntityVisibilityLevels?,
    )

    /**
     * Equips one retained humanoid with a client-only enchanted, trimmed iron
     * armor set. This exercises the ordinary armor, trim, armor-glint, item-ID,
     * host-pose, and shadow-caster paths without mutating server authority.
     * Supplying the returned entity ID with enabled=false restores the exact
     * stacks observed before preparation.
     */
    private fun prepareVanillaArmor(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val requestedId = entityIdNode?.asInt()
        val session = context.session
        val entity = if (enabled) {
            val origin = session.player.physics.position
            session.world.entities.lock.acquired {
                session.world.entities.entities
                    .asSequence()
                    .filterIsInstance<LivingEntity>()
                    .filter { it !== session.player || requestedId == it.id }
                    .filter { it.renderer?.visibility == EntityVisibilityLevels.VISIBLE }
                    .filter {
                        val pose = it.renderer as? VanillaArmorPoseSource
                        pose?.vanillaArmorPose != null
                    }
                    .filter { requestedId == null || it.id == requestedId }
                    .minWithOrNull(
                        compareBy<LivingEntity>(
                            { if (it.type.identifier.toString() == "minecraft:zombie") 0 else 1 },
                            {
                                val position = it.physics.position
                                val dx = position.x - origin.x
                                val dy = position.y - origin.y
                                val dz = position.z - origin.z
                                dx * dx + dy * dy + dz * dz
                            },
                        ),
                    )
            }
        } else {
            val restoreId = requestedId ?: preparedArmor.keys.singleOrNull()
                ?: throw DebugOperationException(
                    "invalid_request",
                    "entityId is required unless exactly one armor canary is prepared",
                )
            session.world.entities.lock.acquired {
                session.world.entities.entities
                    .filterIsInstance<LivingEntity>()
                    .singleOrNull { it.id == restoreId }
            }
        } ?: throw DebugOperationException(
            "not_ready",
            if (requestedId == null) "no retained humanoid armor pose is available"
            else "humanoid entity $requestedId is not retained",
        )
        val entityId = entity.id
            ?: throw DebugOperationException("not_ready", "retained humanoid has no network entity ID")

        val previous = EquipmentSlots.ARMOR_SLOTS.associateWith(entity.equipment::get)
        if (enabled) {
            preparedArmor.putIfAbsent(entityId, previous)
            val protection = session.registries.enchantment["minecraft:protection"]
            val enchanting = protection?.let { EnchantingProperty(mapOf(it to 1)) }
                ?: EnchantingProperty.DEFAULT
            val trim = NbtProperty(
                mapOf(
                    "Trim" to mapOf(
                        "pattern" to "minecraft:sentry",
                        "material" to "minecraft:gold",
                    ),
                ),
            )
            val items = mapOf(
                EquipmentSlots.FEET to "minecraft:iron_boots",
                EquipmentSlots.LEGS to "minecraft:iron_leggings",
                EquipmentSlots.CHEST to "minecraft:iron_chestplate",
                EquipmentSlots.HEAD to "minecraft:iron_helmet",
            )
            for ((slot, identifier) in items) {
                val item = session.registries.item[identifier]
                    ?: throw DebugOperationException("not_ready", "item $identifier is unavailable")
                entity.equipment[slot] = ItemStack(item, enchanting = enchanting, nbt = trim)
            }
        } else {
            val stored = preparedArmor.remove(entityId)
                ?: throw DebugOperationException("invalid_request", "entity $entityId has no prepared armor")
            for ((slot, stack) in stored) entity.equipment[slot] = stack
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("entityId", entityId)
            put("entityType", entity.type.identifier.toString())
            put("renderer", entity.renderer?.javaClass?.simpleName)
            put("enabled", enabled)
            put("clientOnly", true)
            (entity.renderer as? LivingEntityRenderer<*>)?.vanillaArmor?.let { feature ->
                put("retainedArmorEntries", feature.entryCount)
                put("retainedArmorDecorations", feature.decorationCount)
            }
            putObject("previous").apply {
                previous.forEach { (slot, stack) ->
                    put(slot.name.lowercase(), stack?.item?.identifier?.toString())
                }
            }
            putObject("current").apply {
                val textures = context.renderer[EntitiesRenderer]?.features?.armor?.textures
                for (slot in EquipmentSlots.ARMOR_SLOTS) {
                    val stack = entity.equipment[slot]
                    putObject(slot.name.lowercase()).apply {
                        put("item", stack?.item?.identifier?.toString())
                        put("enchanted", stack?.enchanting?.enchantments?.isNotEmpty() == true)
                        put("trimTag", stack?.nbt?.nbt?.containsKey("Trim") == true)
                        val material = stack?.let { textures?.resolve(it, slot) }
                        put("baseResolved", material != null)
                        put("trimResolved", material?.trim != null)
                        put("glintResolved", material?.enchanted == true)
                    }
                }
            }
            put("frame", context.frameNumber)
        })
    }

    /**
     * Holds one retained creeper at the pinned fuse counter that produces a
     * nonzero vanilla white overlay. The override is client-only and restores
     * the prior debug value without pausing the real client fuse accumulator.
     */
    private fun prepareCreeperOverlay(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val entityIdNode = body["entityId"]
        if (entityIdNode != null && !entityIdNode.isIntegralNumber) {
            throw DebugOperationException("invalid_request", "entityId must be an integer")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val requestedId = entityIdNode?.asInt()
        val session = context.session
        val origin = session.player.physics.position
        val entity = session.world.entities.lock.acquired {
            session.world.entities.entities
                .asSequence()
                .filterIsInstance<Creeper>()
                .filter { (it.renderer?.visibility ?: EntityVisibilityLevels.OUT_OF_VIEW_DISTANCE) >= EntityVisibilityLevels.OCCLUDED }
                .filter { requestedId == null || it.id == requestedId }
                .minByOrNull {
                    val position = it.physics.position
                    val dx = position.x - origin.x
                    val dy = position.y - origin.y
                    val dz = position.z - origin.z
                    dx * dx + dy * dy + dz * dz
                }
        } ?: throw DebugOperationException(
            "not_ready",
            if (requestedId == null) "no render-eligible retained creeper is available"
            else "render-eligible creeper $requestedId is not retained",
        )
        val entityId = entity.id
            ?: throw DebugOperationException("not_ready", "retained creeper has no network entity ID")
        val previous = if (enabled) {
            if (preparedCreeperOverlays.containsKey(entityId)) {
                throw DebugOperationException("invalid_request", "creeper $entityId overlay is already prepared")
            }
            val renderer = entity.renderer
                ?: throw DebugOperationException("not_ready", "retained creeper $entityId has no renderer")
            val prepared = PreparedCreeperOverlay(
                fuseTicks = entity.setWhiteOverlayFuseTicksForDebug(CREEPER_OVERLAY_FUSE_TICKS),
                visibilityOverride = renderer.referenceVisibilityOverride,
            )
            renderer.referenceVisibilityOverride = EntityVisibilityLevels.VISIBLE
            preparedCreeperOverlays[entityId] = prepared
            prepared.fuseTicks
        } else {
            if (!preparedCreeperOverlays.containsKey(entityId)) {
                throw DebugOperationException("invalid_request", "creeper $entityId has no prepared overlay")
            }
            val stored = preparedCreeperOverlays.remove(entityId)!!
            entity.renderer?.referenceVisibilityOverride = stored.visibilityOverride
            entity.setWhiteOverlayFuseTicksForDebug(stored.fuseTicks)
        }
        val progress = entity.whiteOverlayProgress(entity.renderInfo.partialTick)
        val color = IrisEntityOverlay.resolve(entity)
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("entityId", entityId)
            put("entityType", entity.type.identifier.toString())
            put("enabled", enabled)
            put("clientOnly", true)
            put("visibility", entity.renderer?.visibility?.name?.lowercase())
            put("visibilityOverride", entity.renderer?.referenceVisibilityOverride?.name?.lowercase())
            if (previous == null) putNull("previousFuseTicks") else put("previousFuseTicks", previous)
            put("fuseState", entity.fuseState)
            put("whiteOverlayProgress", progress)
            putObject("entityColor").apply {
                put("red", color.x)
                put("green", color.y)
                put("blue", color.z)
                put("alpha", color.w)
            }
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedCreeperOverlay(
        val fuseTicks: Int?,
        val visibilityOverride: EntityVisibilityLevels?,
    )

    /**
     * Equips the local player with one client-only vanilla stack for an
     * ordinary item-model material canary. This is deliberately distinct from
     * Gecko item layers and restores the exact prior main-hand stack without
     * mutating the server inventory.
     */
    private fun prepareTranslucentHeldItem(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val itemNode = body["item"]
        if (itemNode != null && !itemNode.isTextual) {
            throw DebugOperationException("invalid_request", "item must be a resource identifier")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val player = session.player
        val slot = EquipmentSlots.MAIN_HAND
        val previous = player.equipment[slot]

        if (enabled) {
            if (preparedTranslucentHeldItem != null) {
                throw DebugOperationException("invalid_request", "a translucent held-item canary is already prepared")
            }
            val identifier = itemNode?.asText() ?: "minecraft:slime_block"
            if (!identifier.matches(Regex("minecraft:[a-z0-9_./-]+"))) {
                throw DebugOperationException("invalid_request", "item must be a vanilla resource identifier")
            }
            val item = session.registries.item[identifier]
                ?: throw DebugOperationException("not_ready", "item $identifier is unavailable")
            preparedTranslucentHeldItem = PreparedHeldItem(player, previous)
            player.equipment[slot] = ItemStack(item)
        } else {
            val prepared = preparedTranslucentHeldItem
                ?: throw DebugOperationException("invalid_request", "no translucent held-item canary is prepared")
            if (prepared.player !== player) {
                throw DebugOperationException("not_ready", "the prepared held-item player is no longer active")
            }
            player.equipment -= slot
            prepared.stack?.let { player.equipment[slot] = it }
            preparedTranslucentHeldItem = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("slot", slot.name.lowercase())
            put("previousItem", previous?.item?.identifier?.toString())
            put("currentItem", player.equipment[slot]?.item?.identifier?.toString())
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedHeldItem(
        val player: LivingEntity,
        val stack: ItemStack?,
    )

    /**
     * Moves the client-side world border around the local player so the
     * retained force-field producer can be exercised without changing server
     * authority. Disable restores the exact border area object, center, and
     * diagnostic presentation suppression state.
     */
    private fun prepareWorldBorder(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val radiusNode = body["radius"]
        if (radiusNode != null && !radiusNode.isNumber) {
            throw DebugOperationException("invalid_request", "radius must be a number")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val border = context.session.world.border
        val renderer = context.renderer[WorldBorderRenderer]
            ?: throw DebugOperationException("not_ready", "world-border renderer is not active")
        val previousCenter = border.center
        val previousArea = border.area

        if (enabled) {
            if (preparedWorldBorder != null) {
                throw DebugOperationException("invalid_request", "a world-border canary is already prepared")
            }
            val radius = radiusNode?.asDouble() ?: DEFAULT_WORLD_BORDER_CANARY_RADIUS
            if (!radius.isFinite() || radius !in MIN_WORLD_BORDER_CANARY_RADIUS..MAX_WORLD_BORDER_CANARY_RADIUS) {
                throw DebugOperationException(
                    "invalid_request",
                    "radius must be finite and in $MIN_WORLD_BORDER_CANARY_RADIUS..$MAX_WORLD_BORDER_CANARY_RADIUS",
                )
            }
            val position = context.session.player.physics.position
            preparedWorldBorder = PreparedWorldBorder(previousCenter, previousArea, renderer.referenceSuppressed)
            border.center = Vec2d(position.x, position.z)
            border.area = StaticBorderArea(radius)
            renderer.referenceSuppressed = false
        } else {
            val prepared = preparedWorldBorder
                ?: throw DebugOperationException("invalid_request", "no world-border canary is prepared")
            border.center = prepared.center
            border.area = prepared.area
            renderer.referenceSuppressed = prepared.referenceSuppressed
            preparedWorldBorder = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            putObject("previousCenter").apply {
                put("x", previousCenter.x)
                put("z", previousCenter.y)
            }
            put("previousRadius", previousArea.radius)
            putObject("currentCenter").apply {
                put("x", border.center.x)
                put("z", border.center.y)
            }
            put("currentRadius", border.area.radius)
            put("distance", border.getDistanceTo(context.session.player.physics.position))
            put("presentationSuppressed", renderer.referenceSuppressed)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedWorldBorder(
        val center: Vec2d,
        val area: BorderArea,
        val referenceSuppressed: Boolean,
    )

    /**
     * Selects the already loaded End sky texture through the production
     * skybox renderer without changing world or dimension authority.
     */
    private fun prepareSkyTexture(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val renderer = context.renderer[SkyRenderer]
            ?: throw DebugOperationException("not_ready", "sky renderer is unavailable")
        val box = renderer.box
        val previous = box.referenceTextureOverride

        if (enabled) {
            if (preparedSkyTexture != null) {
                throw DebugOperationException("invalid_request", "a sky-texture canary is already prepared")
            }
            preparedSkyTexture = PreparedSkyTexture(renderer, previous)
            box.referenceTextureOverride = EndEffects.fixedTexture
        } else {
            val prepared = preparedSkyTexture
                ?: throw DebugOperationException("invalid_request", "no sky-texture canary is prepared")
            if (prepared.renderer !== renderer) {
                throw DebugOperationException("not_ready", "the prepared sky renderer is no longer active")
            }
            box.referenceTextureOverride = prepared.previousOverride
            preparedSkyTexture = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("authoritativeDimension", context.session.world.name?.toString())
            put("authoredTexture", renderer.effects.fixedTexture?.toString())
            put("previousOverride", previous?.toString())
            put("currentOverride", box.referenceTextureOverride?.toString())
            put("effectiveTexture", SkyboxRenderer.selectedTexture(
                renderer.effects.fixedTexture,
                box.referenceTextureOverride,
            )?.toString())
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedSkyTexture(
        val renderer: SkyRenderer,
        val previousOverride: ResourceLocation?,
    )

    /**
     * Enables the existing sun-scatter renderer without changing the
     * authoritative day phase or weather. The renderer still evaluates and
     * uploads its production matrix, position, intensity, and mesh.
     */
    private fun prepareSunScatter(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val sky = context.renderer[SkyRenderer]
            ?: throw DebugOperationException("not_ready", "sky renderer is unavailable")
        val renderer = sky.sunScatter
        val previous = renderer.referenceEnabledOverride

        if (enabled) {
            if (preparedSunScatter != null) {
                throw DebugOperationException("invalid_request", "a sun-scatter canary is already prepared")
            }
            preparedSunScatter = PreparedSunScatter(renderer, previous)
            renderer.referenceEnabledOverride = true
        } else {
            val prepared = preparedSunScatter
                ?: throw DebugOperationException("invalid_request", "no sun-scatter canary is prepared")
            if (prepared.renderer !== renderer) {
                throw DebugOperationException("not_ready", "the prepared sun-scatter renderer is no longer active")
            }
            renderer.referenceEnabledOverride = prepared.previousOverride
            preparedSunScatter = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("dayPhase", sky.time.phase.name.lowercase())
            put("profileEnabled", sky.profile.sunScatter)
            put("dimensionSun", sky.effects.sun)
            putObject("weather").apply {
                put("rain", context.session.world.weather.rain)
                put("thunder", context.session.world.weather.thunder)
            }
            put("previousOverride", previous)
            put("currentOverride", renderer.referenceEnabledOverride)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedSunScatter(
        val renderer: SunScatterRenderer,
        val previousOverride: Boolean?,
    )

    /**
     * Enables the existing first-person fire overlay through its presentation
     * gate while leaving the local player's authoritative fire state intact.
     */
    private fun prepareFireOverlay(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val overlay = context.framebuffer.main.overlay.get(FireOverlay::class.java)
            ?: throw DebugOperationException("not_ready", "fire overlay is unavailable")
        val previous = overlay.referenceRenderOverride

        if (enabled) {
            if (preparedFireOverlay != null) {
                throw DebugOperationException("invalid_request", "a fire-overlay canary is already prepared")
            }
            preparedFireOverlay = PreparedFireOverlay(overlay, previous)
            overlay.referenceRenderOverride = true
        } else {
            val prepared = preparedFireOverlay
                ?: throw DebugOperationException("invalid_request", "no fire-overlay canary is prepared")
            if (prepared.overlay !== overlay) {
                throw DebugOperationException("not_ready", "the prepared fire overlay is no longer active")
            }
            overlay.referenceRenderOverride = prepared.previousOverride
            preparedFireOverlay = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("playerOnFire", context.session.player.isOnFire)
            put("previousOverride", previous)
            put("currentOverride", overlay.referenceRenderOverride)
            put("render", overlay.render)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedFireOverlay(
        val overlay: FireOverlay,
        val previousOverride: Boolean?,
    )

    /**
     * Applies one full-strength client-only rain state while retaining the
     * production biome, overlay, texture, graph phase, and weather shader path.
     * Restore is conditional so a concurrent authoritative weather change is
     * never overwritten.
     */
    private fun prepareWeather(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val world = session.world
        val overlay = context.framebuffer.main.overlay.get(WeatherOverlay::class.java)
            ?: throw DebugOperationException("not_ready", "weather overlay is unavailable")
        val applied: WorldWeather
        val restored: Boolean

        if (enabled) {
            if (preparedWeather != null) {
                throw DebugOperationException("invalid_request", "a weather canary is already prepared")
            }
            if (!world.dimension.effects.weather) {
                throw DebugOperationException("not_ready", "the active dimension does not render weather")
            }
            applied = WorldWeather(rain = 1.0f, thunder = 0.0f)
            preparedWeather = PreparedWeather(
                session,
                world.weather,
                applied,
                overlay.referencePrecipitationOverride,
            )
            overlay.referencePrecipitationOverride = BiomePrecipitation.RAIN
            world.weather = applied
            restored = false
        } else {
            val prepared = preparedWeather
                ?: throw DebugOperationException("invalid_request", "no weather canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared weather world is no longer active")
            }
            applied = prepared.applied
            val weatherMatches = world.weather == applied
            val precipitationMatches = overlay.referencePrecipitationOverride == BiomePrecipitation.RAIN
            restored = weatherMatches && precipitationMatches
            if (weatherMatches) world.weather = prepared.previous
            if (precipitationMatches) overlay.referencePrecipitationOverride = prepared.previousPrecipitationOverride
            preparedWeather = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("sourcePrecipitation", overlay.sourcePrecipitation?.name?.lowercase())
            put("effectivePrecipitation", overlay.effectivePrecipitation?.name?.lowercase())
            put("precipitationOverridden", overlay.referencePrecipitationOverride != null)
            put("dimensionWeather", world.dimension.effects.weather)
            putObject("applied").apply {
                put("rain", applied.rain)
                put("thunder", applied.thunder)
            }
            putObject("current").apply {
                put("rain", world.weather.rain)
                put("thunder", world.weather.thunder)
            }
            put("restored", restored)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedWeather(
        val session: PlaySession,
        val previous: WorldWeather,
        val applied: WorldWeather,
        val previousPrecipitationOverride: BiomePrecipitation?,
    )

    /**
     * Adds one bounded client-only lightning entity through the production
     * entity manager. The normal renderer factory, retained feature, semantic
     * entity layer, and Iris scene contract therefore remain the only draw
     * path. Disabling removes the exact prepared instance without server
     * mutation.
     */
    private fun prepareLightning(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val distanceNode = body["distance"]
        if (distanceNode != null && !distanceNode.isNumber) {
            throw DebugOperationException("invalid_request", "distance must be a number")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session

        val entity = if (enabled) {
            if (preparedLightning != null) {
                throw DebugOperationException("invalid_request", "a lightning canary is already prepared")
            }
            val distance = distanceNode?.asDouble() ?: DEFAULT_LIGHTNING_CANARY_DISTANCE
            if (!distance.isFinite() || distance !in MIN_LIGHTNING_CANARY_DISTANCE..MAX_LIGHTNING_CANARY_DISTANCE) {
                throw DebugOperationException(
                    "invalid_request",
                    "distance must be finite and in $MIN_LIGHTNING_CANARY_DISTANCE..$MAX_LIGHTNING_CANARY_DISTANCE",
                )
            }
            if (session.world.entities[LIGHTNING_CANARY_ENTITY_ID] != null) {
                throw DebugOperationException(
                    "not_ready",
                    "reserved lightning canary entity ID $LIGHTNING_CANARY_ENTITY_ID is occupied",
                )
            }
            val player = session.player
            val origin = player.physics.position
            val yaw = Math.toRadians(player.physics.rotation.yaw.toDouble())
            val position = Vec3d(
                origin.x - kotlin.math.sin(yaw) * distance,
                origin.y - 4.0,
                origin.z + kotlin.math.cos(yaw) * distance,
            )
            val type = session.registries.entityType[LightningBolt.identifier]
                ?: throw DebugOperationException("not_ready", "lightning-bolt entity type is unavailable")
            LightningBolt(session, type, EntityData(session), position).also {
                it.startInit()
                session.world.entities.add(LIGHTNING_CANARY_ENTITY_ID, null, it)
                preparedLightning = PreparedLightning(session, it)
            }
        } else {
            val prepared = preparedLightning
                ?: throw DebugOperationException("invalid_request", "no lightning canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared lightning world is no longer active")
            }
            session.world.entities.remove(prepared.entity)
            preparedLightning = null
            prepared.entity
        }

        val position = entity.physics.position
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            if (enabled) put("entityId", LIGHTNING_CANARY_ENTITY_ID) else putNull("entityId")
            put("entityType", entity.type.identifier.toString())
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("retained", if (enabled) session.world.entities[LIGHTNING_CANARY_ENTITY_ID] === entity else false)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedLightning(
        val session: PlaySession,
        val entity: LightningBolt,
    )

    /**
     * Adds one bounded client-only primed-TNT entity through the production
     * entity manager. Its normal renderer and FlashingBlockFeature exercise
     * the exact block-feature flash ABI under the entity graph semantic.
     */
    private fun preparePrimedTnt(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val distanceNode = body["distance"]
        if (distanceNode != null && !distanceNode.isNumber) {
            throw DebugOperationException("invalid_request", "distance must be a number")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session

        val entity = if (enabled) {
            if (preparedPrimedTnt != null) {
                throw DebugOperationException("invalid_request", "a primed-TNT canary is already prepared")
            }
            val distance = distanceNode?.asDouble() ?: DEFAULT_LIGHTNING_CANARY_DISTANCE
            if (!distance.isFinite() || distance !in MIN_LIGHTNING_CANARY_DISTANCE..MAX_LIGHTNING_CANARY_DISTANCE) {
                throw DebugOperationException(
                    "invalid_request",
                    "distance must be finite and in $MIN_LIGHTNING_CANARY_DISTANCE..$MAX_LIGHTNING_CANARY_DISTANCE",
                )
            }
            if (session.world.entities[PRIMED_TNT_CANARY_ENTITY_ID] != null) {
                throw DebugOperationException(
                    "not_ready",
                    "reserved primed-TNT canary entity ID $PRIMED_TNT_CANARY_ENTITY_ID is occupied",
                )
            }
            val player = session.player
            val origin = player.physics.position
            val yaw = Math.toRadians(player.physics.rotation.yaw.toDouble())
            val position = Vec3d(
                origin.x - kotlin.math.sin(yaw) * distance,
                origin.y + 0.25,
                origin.z + kotlin.math.cos(yaw) * distance,
            )
            val type = session.registries.entityType[PrimedTNT.identifier]
                ?: throw DebugOperationException("not_ready", "primed-TNT entity type is unavailable")
            PrimedTNT(session, type, EntityData(session), position, EntityRotation.EMPTY).also {
                it.startInit()
                session.world.entities.add(PRIMED_TNT_CANARY_ENTITY_ID, null, it)
                preparedPrimedTnt = PreparedPrimedTnt(session, it)
            }
        } else {
            val prepared = preparedPrimedTnt
                ?: throw DebugOperationException("invalid_request", "no primed-TNT canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared primed-TNT world is no longer active")
            }
            session.world.entities.remove(prepared.entity)
            preparedPrimedTnt = null
            prepared.entity
        }

        val position = entity.physics.position
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            if (enabled) put("entityId", PRIMED_TNT_CANARY_ENTITY_ID) else putNull("entityId")
            put("entityType", entity.type.identifier.toString())
            put("fuseTime", entity.fuseTime)
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("retained", if (enabled) session.world.entities[PRIMED_TNT_CANARY_ENTITY_ID] === entity else false)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedPrimedTnt(
        val session: PlaySession,
        val entity: PrimedTNT,
    )

    /**
     * Adds one bounded client-only dropped item through the production entity
     * manager. Its ordinary ItemFeature retains the block-feature vertex ABI
     * while requesting the Iris entity program family.
     */
    private fun prepareItemEntity(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val itemNode = body["item"]
        if (itemNode != null && !itemNode.isTextual) {
            throw DebugOperationException("invalid_request", "item must be a resource identifier")
        }
        val distanceNode = body["distance"]
        if (distanceNode != null && !distanceNode.isNumber) {
            throw DebugOperationException("invalid_request", "distance must be a number")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session

        val entity = if (enabled) {
            if (preparedItemEntity != null) {
                throw DebugOperationException("invalid_request", "an item-entity canary is already prepared")
            }
            val identifier = itemNode?.asText() ?: "minecraft:diamond"
            if (!identifier.matches(Regex("minecraft:[a-z0-9_./-]+"))) {
                throw DebugOperationException("invalid_request", "item must be a vanilla resource identifier")
            }
            val item = session.registries.item[identifier]
                ?: throw DebugOperationException("not_ready", "item $identifier is unavailable")
            val distance = distanceNode?.asDouble() ?: DEFAULT_LIGHTNING_CANARY_DISTANCE
            if (!distance.isFinite() || distance !in MIN_LIGHTNING_CANARY_DISTANCE..MAX_LIGHTNING_CANARY_DISTANCE) {
                throw DebugOperationException(
                    "invalid_request",
                    "distance must be finite and in $MIN_LIGHTNING_CANARY_DISTANCE..$MAX_LIGHTNING_CANARY_DISTANCE",
                )
            }
            if (session.world.entities[ITEM_CANARY_ENTITY_ID] != null) {
                throw DebugOperationException(
                    "not_ready",
                    "reserved item canary entity ID $ITEM_CANARY_ENTITY_ID is occupied",
                )
            }
            val player = session.player
            val origin = player.physics.position
            val yaw = Math.toRadians(player.physics.rotation.yaw.toDouble())
            val position = Vec3d(
                origin.x - kotlin.math.sin(yaw) * distance,
                origin.y + 0.25,
                origin.z + kotlin.math.cos(yaw) * distance,
            )
            val type = session.registries.entityType[ItemEntity.identifier]
                ?: throw DebugOperationException("not_ready", "item entity type is unavailable")
            val data = EntityData(session).apply {
                this[ItemEntity.ITEM_DATA] = ItemStack(item)
            }
            ItemEntity(session, type, data, position, EntityRotation.EMPTY).also {
                it.startInit()
                session.world.entities.add(ITEM_CANARY_ENTITY_ID, null, it)
                preparedItemEntity = PreparedItemEntity(session, it)
            }
        } else {
            val prepared = preparedItemEntity
                ?: throw DebugOperationException("invalid_request", "no item-entity canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared item-entity world is no longer active")
            }
            session.world.entities.remove(prepared.entity)
            preparedItemEntity = null
            prepared.entity
        }

        val position = entity.physics.position
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            if (enabled) put("entityId", ITEM_CANARY_ENTITY_ID) else putNull("entityId")
            put("entityType", entity.type.identifier.toString())
            put("item", entity.stack?.item?.identifier?.toString())
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("retained", if (enabled) session.world.entities[ITEM_CANARY_ENTITY_ID] === entity else false)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedItemEntity(
        val session: PlaySession,
        val entity: ItemEntity,
    )

    /**
     * Places one active beacon into an empty loaded client position through
     * the production world mutation path. This creates the normal block
     * entity, invalidates the chunk cache, and lets ChunkRenderer collect the
     * retained translucent beam. Disable restores the exact prior air state.
     */
    private fun prepareBeacon(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val world = session.world

        val position: BlockPosition
        val entity: BeaconBlockEntity?
        val restored: Boolean
        if (enabled) {
            if (preparedBeacon != null) {
                throw DebugOperationException("invalid_request", "a beacon canary is already prepared")
            }
            val origin = session.player.physics.positionInfo.position
            val yaw = Math.toRadians(session.player.physics.rotation.yaw.toDouble())
            val targetX = origin.x - kotlin.math.sin(yaw) * 4.0
            val targetZ = origin.z + kotlin.math.cos(yaw) * 4.0
            val horizontal = buildList {
                for (x in -BEACON_CANARY_HORIZONTAL_RADIUS..BEACON_CANARY_HORIZONTAL_RADIUS) {
                    for (z in -BEACON_CANARY_HORIZONTAL_RADIUS..BEACON_CANARY_HORIZONTAL_RADIUS) {
                        if (x != 0 || z != 0) add(x to z)
                    }
                }
            }.sortedBy { (x, z) ->
                val dx = origin.x + x - targetX
                val dz = origin.z + z - targetZ
                dx * dx + dz * dz
            }
            position = sequence {
                for (y in origin.y - 1..origin.y + 4) {
                    for ((x, z) in horizontal) yield(BlockPosition(origin.x + x, y, origin.z + z))
                }
            }.firstOrNull { candidate ->
                world.isValidPosition(candidate) &&
                    candidate.y + BEACON_CANARY_CLEAR_HEIGHT <= world.dimension.maxY &&
                    world.chunks[candidate.chunkPosition] != null &&
                    world[candidate] == null &&
                    world.getBlockEntity(candidate) == null &&
                    (1..BEACON_CANARY_CLEAR_HEIGHT).all { offset ->
                        val state = world[candidate.with(y = candidate.y + offset)]
                        state == null || BlockStateFlags.FULL_OPAQUE !in state.flags
                    }
            } ?: throw DebugOperationException(
                "not_ready",
                "no empty loaded position with a clear beacon column is available nearby",
            )
            val state = session.registries.block[BeaconBlockEntity.identifier]?.states?.default
                ?: throw DebugOperationException("not_ready", "beacon block state is unavailable")
            world[position] = state
            entity = world.getBlockEntity(position) as? BeaconBlockEntity
                ?: run {
                    world[position] = null
                    throw DebugOperationException("not_ready", "beacon block entity was not created")
                }
            entity.updateNBT(mapOf("Levels" to 4))
            preparedBeacon = PreparedBeacon(session, position, state)
            restored = false
        } else {
            val prepared = preparedBeacon
                ?: throw DebugOperationException("invalid_request", "no beacon canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared beacon world is no longer active")
            }
            position = prepared.position
            entity = world.getBlockEntity(position) as? BeaconBlockEntity
            restored = world[position] == prepared.state
            if (restored) world[position] = null
            preparedBeacon = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("levels", entity?.levels)
            put("blockEntity", entity?.javaClass?.name)
            put("currentBlock", world[position]?.block?.identifier?.toString())
            put("restored", restored)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedBeacon(
        val session: PlaySession,
        val position: BlockPosition,
        val state: BlockState,
    )

    /**
     * Publishes a small reversible terrain-material fixture through the normal
     * client-world mutation path. It is deliberately limited to a local
     * connection and empty loaded cells so acceptance can cover fluids,
     * waterlogging, shoreline adjacency, emissive blocks, and day/night
     * remeshing without exposing a general-purpose block editor.
     */
    private fun prepareTerrainMaterials(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val phaseNode = body["phase"]
        if (phaseNode != null && !phaseNode.isTextual) {
            throw DebugOperationException("invalid_request", "phase must be day or night")
        }
        val phase = phaseNode?.asText()?.lowercase() ?: "day"
        if (phase !in setOf("day", "night")) {
            throw DebugOperationException("invalid_request", "phase must be day or night")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        if (session.connection !is LocalConnection) {
            throw DebugOperationException("not_ready", "terrain material fixtures require a local connection")
        }
        val world = session.world

        if (!enabled) {
            val prepared = preparedTerrainMaterials
                ?: throw DebugOperationException("invalid_request", "no terrain material fixture is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared terrain material world is no longer active")
            }
            val conflicts = prepared.placements.count { world[it.position] != it.fixture }
            if (conflicts != 0) {
                throw DebugOperationException(
                    "state_conflict",
                    "$conflicts terrain material fixture blocks changed before restoration",
                )
            }
            var restoredPlacements = 0
            try {
                for (placement in prepared.placements) {
                    world[placement.position] = placement.previous
                    restoredPlacements++
                }
                world.time = prepared.previousTime
            } catch (failure: Throwable) {
                for (index in restoredPlacements - 1 downTo 0) {
                    val placement = prepared.placements[index]
                    try {
                        world[placement.position] = placement.fixture
                    } catch (rollback: Throwable) {
                        failure.addSuppressed(rollback)
                    }
                }
                throw failure
            }
            preparedTerrainMaterials = null
            return terrainMaterialResult(context, enabled = false, phase = "restored", prepared, restored = true)
        }

        val existing = preparedTerrainMaterials
        if (existing != null) {
            if (existing.session !== session) {
                throw DebugOperationException("not_ready", "the prepared terrain material world is no longer active")
            }
            world.time = WorldTime(if (phase == "day") 6_000 else 18_000, world.time.age)
            return terrainMaterialResult(context, enabled = true, phase, existing, restored = false)
        }

        fun state(identifier: ResourceLocation): BlockState = session.registries.block[identifier]?.states?.default
            ?: throw DebugOperationException("not_ready", "terrain fixture block state is unavailable: $identifier")

        val fixtureStates = listOf(
            state(MinecraftBlocks.WATER),
            state(MinecraftBlocks.WATER),
            state(MinecraftBlocks.OAK_SLAB).withProperties(BlockProperties.WATERLOGGED to true),
            state(MinecraftBlocks.STONE),
            state(MinecraftBlocks.GLOWSTONE),
            state(MinecraftBlocks.SEA_LANTERN),
            state(MinecraftBlocks.LAVA),
        )
        val offsets = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 0 to 1, 1 to 1, 2 to 1)
        val origin = session.player.physics.positionInfo.position
        val anchor = sequence {
            for (y in origin.y..origin.y + 3) {
                for (radius in 3..8) {
                    for (x in -radius..radius) for (z in -radius..radius) {
                        if (maxOf(kotlin.math.abs(x), kotlin.math.abs(z)) != radius) continue
                        yield(BlockPosition(origin.x + x, y, origin.z + z))
                    }
                }
            }
        }.firstOrNull { candidate ->
            offsets.all { (x, z) ->
                val position = candidate + BlockPosition(x, 0, z)
                world.isValidPosition(position) &&
                    world.chunks[position.chunkPosition] != null &&
                    world[position] == null &&
                    world.getBlockEntity(position) == null
            }
        } ?: throw DebugOperationException("not_ready", "no empty loaded terrain fixture area is available nearby")

        val placements = offsets.zip(fixtureStates).map { (offset, fixture) ->
            val position = anchor + BlockPosition(offset.first, 0, offset.second)
            TerrainMaterialPlacement(position, world[position], fixture)
        }
        val prepared = PreparedTerrainMaterials(session, placements, world.time)
        var appliedPlacements = 0
        try {
            for (placement in placements) {
                world[placement.position] = placement.fixture
                appliedPlacements++
            }
            world.time = WorldTime(if (phase == "day") 6_000 else 18_000, world.time.age)
        } catch (failure: Throwable) {
            for (index in appliedPlacements - 1 downTo 0) {
                val placement = placements[index]
                try {
                    world[placement.position] = placement.previous
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
            }
            try {
                world.time = prepared.previousTime
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
        preparedTerrainMaterials = prepared
        return terrainMaterialResult(context, enabled = true, phase, prepared, restored = false)
    }

    private fun terrainMaterialResult(
        context: RenderContext,
        enabled: Boolean,
        phase: String,
        prepared: PreparedTerrainMaterials,
        restored: Boolean,
    ) = DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
        put("enabled", enabled)
        put("clientOnly", true)
        put("phase", phase)
        put("restored", restored)
        put("frame", context.frameNumber)
        putArray("blocks").also { blocks ->
            prepared.placements.forEach { placement ->
                blocks.addObject().apply {
                    put("x", placement.position.x)
                    put("y", placement.position.y)
                    put("z", placement.position.z)
                    put("fixture", placement.fixture.block.identifier.toString())
                    put("waterlogged", BlockStateFlags.WATERLOGGED in placement.fixture.flags)
                    put("current", context.session.world[placement.position]?.block?.identifier?.toString())
                }
            }
        }
    })

    private data class PreparedTerrainMaterials(
        val session: PlaySession,
        val placements: List<TerrainMaterialPlacement>,
        val previousTime: WorldTime,
    )

    private data class TerrainMaterialPlacement(
        val position: BlockPosition,
        val previous: BlockState?,
        val fixture: BlockState,
    )

    /**
     * Places one bounded storage block entity through the production world
     * mutation path. The retained block-entity renderer, skeletal model,
     * opening animation, chunk collection, and Iris block-entity route remain
     * authoritative. Disable restores the exact prior air state.
     */
    private fun prepareStorageBlockEntity(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val openNode = body["open"]
        if (openNode != null && !openNode.isBoolean) {
            throw DebugOperationException("invalid_request", "open must be boolean")
        }
        val typeNode = body["type"]
        if (typeNode != null && !typeNode.isTextual) {
            throw DebugOperationException("invalid_request", "type must be a string")
        }

        val enabled = enabledNode?.asBoolean() ?: true
        val open = openNode?.asBoolean() ?: true
        val session = context.session
        val world = session.world

        val position: BlockPosition
        val type: StorageBlockEntityCanaryType
        val entity: StorageBlockEntity?
        val restored: Boolean
        if (enabled) {
            if (preparedStorageBlockEntity != null) {
                throw DebugOperationException(
                    "invalid_request",
                    "a storage block-entity canary is already prepared",
                )
            }
            type = typeNode?.asText()?.let(StorageBlockEntityCanaryType::fromRequest)
                ?: if (typeNode == null) {
                    StorageBlockEntityCanaryType.CHEST
                } else {
                    throw DebugOperationException(
                        "invalid_request",
                        "type must be one of " +
                            StorageBlockEntityCanaryType.entries.joinToString { it.requestName },
                    )
                }

            val origin = session.player.physics.positionInfo.position
            val yaw = Math.toRadians(session.player.physics.rotation.yaw.toDouble())
            val targetX = origin.x - kotlin.math.sin(yaw) * STORAGE_BLOCK_ENTITY_CANARY_DISTANCE
            val targetZ = origin.z + kotlin.math.cos(yaw) * STORAGE_BLOCK_ENTITY_CANARY_DISTANCE
            val horizontal = buildList {
                for (x in -BEACON_CANARY_HORIZONTAL_RADIUS..BEACON_CANARY_HORIZONTAL_RADIUS) {
                    for (z in -BEACON_CANARY_HORIZONTAL_RADIUS..BEACON_CANARY_HORIZONTAL_RADIUS) {
                        if (x != 0 || z != 0) add(x to z)
                    }
                }
            }.sortedBy { (x, z) ->
                val dx = origin.x + x - targetX
                val dz = origin.z + z - targetZ
                dx * dx + dz * dz
            }
            position = sequence {
                for (y in origin.y - 1..origin.y + 4) {
                    for ((x, z) in horizontal) yield(BlockPosition(origin.x + x, y, origin.z + z))
                }
            }.firstOrNull { candidate ->
                world.isValidPosition(candidate) &&
                    world.chunks[candidate.chunkPosition] != null &&
                    world[candidate] == null &&
                    world.getBlockEntity(candidate) == null
            } ?: throw DebugOperationException(
                "not_ready",
                "no empty loaded storage block-entity position is available nearby",
            )

            val state = session.registries.block[type.block]?.states?.default
                ?: throw DebugOperationException(
                    "not_ready",
                    "${type.requestName} block state is unavailable",
                )
            world[position] = state
            entity = world.getBlockEntity(position) as? StorageBlockEntity
                ?: run {
                    world[position] = null
                    throw DebugOperationException(
                        "not_ready",
                        "${type.requestName} block entity was not created",
                    )
                }
            entity.setBlockActionData(1, if (open) 1 else 0)
            preparedStorageBlockEntity = PreparedStorageBlockEntity(session, position, state, type)
            restored = false
        } else {
            val prepared = preparedStorageBlockEntity
                ?: throw DebugOperationException(
                    "invalid_request",
                    "no storage block-entity canary is prepared",
                )
            if (prepared.session !== session) {
                throw DebugOperationException(
                    "not_ready",
                    "the prepared storage block-entity world is no longer active",
                )
            }
            position = prepared.position
            type = prepared.type
            entity = world.getBlockEntity(position) as? StorageBlockEntity
            restored = world[position] == prepared.state
            if (restored) world[position] = null
            preparedStorageBlockEntity = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("type", type.requestName)
            put("open", entity?.closed == false)
            put("viewing", entity?.viewing)
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("blockEntity", entity?.javaClass?.name)
            put("currentBlock", world[position]?.block?.identifier?.toString())
            put("restored", restored)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedStorageBlockEntity(
        val session: PlaySession,
        val position: BlockPosition,
        val state: BlockState,
        val type: StorageBlockEntityCanaryType,
    )

    /**
     * Places one text-bearing sign through the production block and
     * block-entity-data paths. Sign glyphs are baked into the section's
     * EMISSIVE_ADDITIVE terrain material, so this canary exercises the terrain
     * provider route rather than a block-entity scene shader.
     */
    private fun prepareSignText(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val world = session.world

        val position: BlockPosition
        val entity: SignBlockEntity?
        val restored: Boolean
        if (enabled) {
            if (preparedSignText != null) {
                throw DebugOperationException("invalid_request", "a sign-text canary is already prepared")
            }
            val origin = session.player.physics.positionInfo.position
            val yaw = Math.toRadians(session.player.physics.rotation.yaw.toDouble())
            val targetX = origin.x - kotlin.math.sin(yaw) * 4.0
            val targetZ = origin.z + kotlin.math.cos(yaw) * 4.0
            position = sequence {
                val offsets = buildList {
                    for (x in -BEACON_CANARY_HORIZONTAL_RADIUS..BEACON_CANARY_HORIZONTAL_RADIUS) {
                        for (z in -BEACON_CANARY_HORIZONTAL_RADIUS..BEACON_CANARY_HORIZONTAL_RADIUS) {
                            if (x != 0 || z != 0) add(x to z)
                        }
                    }
                }.sortedBy { (x, z) ->
                    val dx = origin.x + x - targetX
                    val dz = origin.z + z - targetZ
                    dx * dx + dz * dz
                }
                for (y in origin.y - 1..origin.y + 4) {
                    for ((x, z) in offsets) yield(BlockPosition(origin.x + x, y, origin.z + z))
                }
            }.firstOrNull { candidate ->
                world.isValidPosition(candidate) &&
                    world.chunks[candidate.chunkPosition] != null &&
                    world[candidate] == null &&
                    world.getBlockEntity(candidate) == null
            } ?: throw DebugOperationException("not_ready", "no empty loaded sign position is available nearby")

            val state = session.registries.block[MinecraftBlocks.OAK_SIGN]?.states?.default
                ?: throw DebugOperationException("not_ready", "oak sign block state is unavailable")
            world[position] = state
            val chunk = world.chunks[position.chunkPosition]
                ?: run {
                    world[position] = null
                    throw DebugOperationException("not_ready", "prepared sign chunk was unloaded")
                }
            entity = chunk.applyBlockEntityData(
                position.inChunkPosition,
                mapOf(
                    "is_waxed" to 1.toByte(),
                    "front_text" to mapOf(
                        "has_glowing_text" to 1.toByte(),
                        "color" to "white",
                        "messages" to listOf("\"IRIS\"", "\"SIGN\"", "\"TEXT\"", "\"ROUTE\""),
                    ),
                    "back_text" to mapOf(
                        "has_glowing_text" to 1.toByte(),
                        "color" to "white",
                        "messages" to listOf("\"IRIS\"", "\"SIGN\"", "\"TEXT\"", "\"ROUTE\""),
                    ),
                ),
            ) as? SignBlockEntity ?: run {
                world[position] = null
                throw DebugOperationException("not_ready", "sign block entity was not created")
            }
            preparedSignText = PreparedSignText(session, position, state)
            restored = false
        } else {
            val prepared = preparedSignText
                ?: throw DebugOperationException("invalid_request", "no sign-text canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared sign world is no longer active")
            }
            position = prepared.position
            entity = world.getBlockEntity(position) as? SignBlockEntity
            restored = world[position] == prepared.state
            if (restored) world[position] = null
            preparedSignText = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("blockEntity", entity?.javaClass?.name)
            put("frontGlowing", entity?.front?.glowing)
            put("backGlowing", entity?.back?.glowing)
            putArray("frontLines").also { lines ->
                entity?.front?.text?.forEach { line ->
                    lines.addObject().apply {
                        put("message", line.message)
                        put("length", line.length)
                        put("primitives", de.bixilon.minosoft.gui.rendering.font.renderer.component.ChatComponentRenderer.calculatePrimitiveCount(line))
                    }
                }
            }
            put("blockClass", world[position]?.block?.javaClass?.name)
            put("blockModel", world[position]?.block?.model?.javaClass?.name)
            put("stateModel", world[position]?.model?.javaClass?.name)
            val section = world.chunks[position.chunkPosition]?.get(position.sectionHeight)
            put("sectionEntityMatches", section?.entities?.get(position.inSectionPosition) === entity)
            put("sectionStateMatches", section?.blocks?.get(position.inSectionPosition) === world[position])
            put("currentBlock", world[position]?.block?.identifier?.toString())
            put("restored", restored)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedSignText(
        val session: PlaySession,
        val position: BlockPosition,
        val state: BlockState,
    )

    /**
     * Publishes one bounded client-only remote-break event for an existing
     * nearby block. The normal retained overlay renderer, texture animation,
     * world-overlay phase, and damaged-block Iris contract remain authoritative.
     */
    private fun prepareBlockBreak(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val renderer = context.renderer[BlockBreakRenderer]
            ?: throw DebugOperationException("not_ready", "block-break renderer is unavailable")

        val position: BlockPosition
        val cancelled: Boolean
        if (enabled) {
            if (preparedBlockBreak != null) {
                throw DebugOperationException("invalid_request", "a block-break canary is already prepared")
            }
            val origin = session.player.physics.positionInfo.position
            position = sequence {
                for (y in origin.y - 1 downTo origin.y - 4) {
                    for (radius in 0..BEACON_CANARY_HORIZONTAL_RADIUS) {
                        for (x in -radius..radius) {
                            for (z in -radius..radius) {
                                if (maxOf(kotlin.math.abs(x), kotlin.math.abs(z)) != radius) continue
                                yield(BlockPosition(origin.x + x, y, origin.z + z))
                            }
                        }
                    }
                }
            }.firstOrNull { candidate ->
                session.world.chunks[candidate.chunkPosition] != null &&
                    session.world[candidate]?.let { it.model ?: it.block.model } != null
            } ?: throw DebugOperationException("not_ready", "no model-bearing loaded block is available nearby")
            cancelled = session.events.fire(
                BlockBreakAnimationEvent(session, BLOCK_BREAK_CANARY_ID, position, BLOCK_BREAK_CANARY_PROGRESS),
            )
            if (cancelled) {
                session.events.fire(BlockBreakAnimationEvent(session, BLOCK_BREAK_CANARY_ID, position, null))
                throw DebugOperationException("not_ready", "block-break canary event was cancelled")
            }
            preparedBlockBreak = PreparedBlockBreak(session, position)
        } else {
            val prepared = preparedBlockBreak
                ?: throw DebugOperationException("invalid_request", "no block-break canary is prepared")
            if (prepared.session !== session) {
                throw DebugOperationException("not_ready", "the prepared block-break world is no longer active")
            }
            position = prepared.position
            cancelled = session.events.fire(BlockBreakAnimationEvent(session, BLOCK_BREAK_CANARY_ID, position, null))
            preparedBlockBreak = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("eventId", BLOCK_BREAK_CANARY_ID)
            putObject("position").apply {
                put("x", position.x)
                put("y", position.y)
                put("z", position.z)
            }
            put("block", session.world[position]?.block?.identifier?.toString())
            if (enabled) put("progress", BLOCK_BREAK_CANARY_PROGRESS) else putNull("progress")
            put("cancelled", cancelled)
            put("retained", renderer.hasInstance(BLOCK_BREAK_CANARY_ID))
            put("retainedInstances", renderer.instanceCount)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedBlockBreak(
        val session: PlaySession,
        val position: BlockPosition,
    )

    /**
     * Adds one long-lived stationary sneeze particle through the production
     * particle queue. Its authored alpha selects the ordinary translucent mesh
     * and graph pass. Disable marks only that instance dead; the normal ticker
     * removes it from either the queue or retained list.
     */
    private fun prepareTranslucentParticle(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val renderer = context.renderer[ParticleRenderer]
            ?: throw DebugOperationException("not_ready", "particle renderer is unavailable")

        val particle = if (enabled) {
            if (preparedTranslucentParticle != null) {
                throw DebugOperationException("invalid_request", "a translucent-particle canary is already prepared")
            }
            if (!renderer.enabled) {
                throw DebugOperationException("not_ready", "particle rendering is disabled")
            }
            val player = session.player
            val origin = player.physics.position
            val yaw = Math.toRadians(player.physics.rotation.yaw.toDouble())
            val position = Vec3d(
                origin.x - kotlin.math.sin(yaw) * 2.0,
                origin.y + player.eyeHeight * 0.65,
                origin.z + kotlin.math.cos(yaw) * 2.0,
            )
            SneezeParticle(
                session,
                position,
                de.bixilon.kmath.vec.vec3.d.MVec3d.EMPTY,
            ).also {
                it.movement = false
                it.physics = false
                it.maxAge = 400
                renderer += it
                preparedTranslucentParticle = PreparedTranslucentParticle(session, renderer, it)
            }
        } else {
            val prepared = preparedTranslucentParticle
                ?: throw DebugOperationException("invalid_request", "no translucent-particle canary is prepared")
            if (prepared.session !== session || prepared.renderer !== renderer) {
                throw DebugOperationException("not_ready", "the prepared particle renderer is no longer active")
            }
            prepared.particle.dead = true
            preparedTranslucentParticle = null
            prepared.particle
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("particleType", SneezeParticle.identifier.toString())
            put("retained", renderer.hasParticle(particle))
            put("dead", particle.dead)
            put("maxAge", particle.maxAge)
            put("particleCount", renderer.size)
            put("translucentMesh", renderer.translucentMesh != null)
            putObject("position").apply {
                put("x", particle.position.x)
                put("y", particle.position.y)
                put("z", particle.position.z)
            }
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedTranslucentParticle(
        val session: PlaySession,
        val renderer: ParticleRenderer,
        val particle: SneezeParticle,
    )

    /**
     * Enables the normal F3+G chunk-border producer without changing the
     * user's profile. Async mesh generation, layer ordering, and the generic
     * color shader remain authoritative.
     */
    private fun prepareChunkBorder(context: RenderContext, body: JsonNode): DebugOperationResult {
        val enabledNode = body["enabled"]
        if (enabledNode != null && !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be boolean")
        }
        val enabled = enabledNode?.asBoolean() ?: true
        val session = context.session
        val renderer = context.renderer[ChunkBorderRenderer]
            ?: throw DebugOperationException("not_ready", "chunk-border renderer is unavailable")
        val restored: Boolean

        if (enabled) {
            if (preparedChunkBorder != null) {
                throw DebugOperationException("invalid_request", "a chunk-border canary is already prepared")
            }
            preparedChunkBorder = PreparedChunkBorder(session, renderer, renderer.referenceEnabledOverride)
            renderer.referenceEnabledOverride = true
            restored = false
        } else {
            val prepared = preparedChunkBorder
                ?: throw DebugOperationException("invalid_request", "no chunk-border canary is prepared")
            if (prepared.session !== session || prepared.renderer !== renderer) {
                throw DebugOperationException("not_ready", "the prepared chunk-border renderer is no longer active")
            }
            restored = renderer.referenceEnabledOverride == true
            if (restored) renderer.referenceEnabledOverride = prepared.previousOverride
            preparedChunkBorder = null
        }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("enabled", enabled)
            put("clientOnly", true)
            put("profileEnabled", session.profiles.rendering.chunkBorder.enabled)
            put("referenceEnabledOverride", renderer.referenceEnabledOverride)
            put("effectiveEnabled", renderer.effectiveEnabled)
            put("meshRetained", renderer.mesh != null)
            put("nextMeshRetained", renderer.nextMesh != null)
            put("restored", restored)
            put("frame", context.frameNumber)
        })
    }

    private data class PreparedChunkBorder(
        val session: PlaySession,
        val renderer: ChunkBorderRenderer,
        val previousOverride: Boolean?,
    )

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
        var minimumLuminance = Double.POSITIVE_INFINITY
        var maximumLuminance = Double.NEGATIVE_INFINITY
        var nearBlackPixels = 0
        var minimumAlpha = 255
        var maximumAlpha = 0
        for (py in y until y + height) for (px in x until x + width) {
            val color = buffer.getRGBA(px, size.y - 1 - py)
            digest.update(byteArrayOf(color.red.toByte(), color.green.toByte(), color.blue.toByte(), color.alpha.toByte()))
            val pixelLuminance = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
            luminance += pixelLuminance
            minimumLuminance = minOf(minimumLuminance, pixelLuminance)
            maximumLuminance = maxOf(maximumLuminance, pixelLuminance)
            if (pixelLuminance <= NEAR_BLACK_LUMINANCE) nearBlackPixels++
            minimumAlpha = minOf(minimumAlpha, color.alpha)
            maximumAlpha = maxOf(maximumAlpha, color.alpha)
        }
        result.putObject("region").apply {
            put("x", x); put("y", y); put("width", width); put("height", height)
            put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            put("averageLuminance", luminance / (width * height) / 255.0)
            put("minimumLuminance", minimumLuminance / 255.0)
            put("maximumLuminance", maximumLuminance / 255.0)
            put("nearBlackPixelCount", nearBlackPixels)
            put("minimumAlpha", minimumAlpha)
            put("maximumAlpha", maximumAlpha)
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

    /**
     * Recovers a dead live client through the same protocol action as the
     * respawn menu. Keeping this health- and state-gated prevents the debug
     * control plane from becoming a general gameplay action surface.
     */
    private fun respawn(context: RenderContext): DebugOperationResult {
        val session = context.session
        val health = session.player.healthCondition.hp
        if (session.state != PlaySessionStates.DEAD || health > 0.0f) {
            throw DebugOperationException("invalid_state", "client is not dead")
        }
        session.util.respawn()
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("requested", true)
            put("sessionState", session.state.name.lowercase())
            put("health", health)
            put("frame", context.frameNumber)
        })
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
        val blockLight = ArrayList<Int>(volume)
        val skyLight = ArrayList<Int>(volume)
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
                val light = chunk?.light?.get(position.inChunkPosition)
                blockLight += light?.block ?: 0
                skyLight += light?.sky ?: 0
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
        val blockLightJson = result.putArray("blockLight")
        blockLight.forEach(blockLightJson::add)
        val skyLightJson = result.putArray("skyLight")
        skyLight.forEach(skyLightJson::add)
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

    private fun terrainDiagnostics(context: RenderContext, body: JsonNode): DebugOperationResult {
        val diagnosticGeneration = context.frameNumber
        val request = TerrainDiagnosticDebugOperation.resolveRequest(
            body = body,
            diagnosticGeneration = diagnosticGeneration,
            worldEpoch = context.session.world.terrainEpoch,
        )
        if (request is de.bixilon.minosoft.debug.terrain.TerrainDiagnosticRequestResolution.Rejected) {
            return TerrainDiagnosticDebugOperation.rejectionResult(request.rejection)
        }
        request as de.bixilon.minosoft.debug.terrain.TerrainDiagnosticRequestResolution.Accepted
        return when (val capture = TerrainProductionDiagnosticCapture.capture(context, request.query)) {
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Captured ->
                TerrainDiagnosticDebugOperation.execute(capture.snapshot)
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Rejected ->
                TerrainDiagnosticDebugOperation.rejectionResult(capture.rejection)
            de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Unavailable ->
                TerrainDiagnosticDebugOperation.unavailableProvider(diagnosticGeneration)
        }
    }

    private fun terrainDiagnosticOperation(
        context: RenderContext,
        body: JsonNode,
        endpointGeneration: Int,
        operation: String,
    ): DebugOperationResult {
        val diagnosticGeneration = context.frameNumber
        val resolution = when (operation) {
            TerrainDiagnosticOperations.PAGE -> TerrainDiagnosticDebugOperation.resolvePageRequest(
                body,
                diagnosticGeneration,
                context.session.world.terrainEpoch,
            )
            TerrainDiagnosticOperations.PAGES -> TerrainDiagnosticDebugOperation.resolveRequest(
                body,
                diagnosticGeneration,
                context.session.world.terrainEpoch,
            )
            TerrainDiagnosticOperations.SUMMARY,
            TerrainDiagnosticOperations.COVERAGE -> if (body.isObject && body.isEmpty) {
                de.bixilon.minosoft.debug.terrain.TerrainDiagnosticRequestResolution.Accepted(null)
            } else {
                de.bixilon.minosoft.debug.terrain.TerrainDiagnosticRequestResolution.Rejected(
                    TerrainDiagnosticRejection(
                        code = TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
                        subject = "request",
                        diagnosticGeneration = diagnosticGeneration,
                    ),
                )
            }
            else -> throw DebugOperationException("unsupported_operation", "unsupported terrain diagnostic operation")
        }
        if (resolution is de.bixilon.minosoft.debug.terrain.TerrainDiagnosticRequestResolution.Rejected) {
            return TerrainDiagnosticDebugOperation.rejectionResult(resolution.rejection)
        }
        resolution as de.bixilon.minosoft.debug.terrain.TerrainDiagnosticRequestResolution.Accepted
        if (operation == TerrainDiagnosticOperations.PAGES && resolution.query == null) {
            return TerrainDiagnosticDebugOperation.rejectionResult(
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
                    subject = "pageQuery",
                    diagnosticGeneration = diagnosticGeneration,
                ),
            )
        }
        return when (val capture = TerrainProductionDiagnosticCapture.capture(context, resolution.query)) {
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Captured ->
                TerrainDiagnosticDebugOperation.operationResult(
                    operation,
                    endpointGeneration,
                    capture.snapshot,
                )
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Rejected ->
                TerrainDiagnosticDebugOperation.rejectionResult(capture.rejection)
            de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Unavailable ->
                TerrainDiagnosticDebugOperation.unavailableProvider(diagnosticGeneration)
        }
    }

    private fun flushTerrainIdle(
        request: DebugRequestContext,
        body: JsonNode,
        context: RenderContext,
    ): CompletableFuture<DebugOperationResult> {
        val parsed = TerrainDiagnosticDebugOperation.parseFlushIdleRequest(body)
            ?: throw DebugOperationException(
                "invalid_request",
                "terrain flush-idle requires condition BUILDS, UPLOADS, RETIREMENT, or ALL and timeoutMs 1..30000",
            )
        val session = context.session
        val worldEpoch = session.world.terrainEpoch
        val pipelineGeneration = context.renderer.pipeline.terrainSelection().generation
        val startNanos = System.nanoTime()
        val deadlineNanos = try {
            Math.addExact(startNanos, TimeUnit.MILLISECONDS.toNanos(parsed.timeoutMillis))
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val future = CompletableFuture<DebugOperationResult>()
        lateinit var poll: () -> Unit

        fun enqueuePoll() {
            CompletableFuture.delayedExecutor(1L, TimeUnit.MILLISECONDS).execute {
                if (!future.isDone) context.queue += { poll() }
            }
        }

        fun completeAtIdle(state: TerrainIdleState) {
            when (val capture = TerrainProductionDiagnosticCapture.capture(context)) {
                is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Captured -> {
                    val waitedNanos = Math.subtractExact(System.nanoTime(), startNanos).coerceAtLeast(0L)
                    future.complete(
                        TerrainDiagnosticDebugOperation.specializedOperationResult(
                            TerrainDiagnosticOperations.FLUSH_IDLE,
                            request.endpoint().generation,
                            capture.snapshot,
                        ) {
                            put("condition", parsed.condition.name)
                            put("waitedNanos", waitedNanos)
                            putObject("idleState").apply {
                                put("queuedBuilds", state.queuedBuilds)
                                put("outstandingBuilds", state.outstandingBuilds)
                                put("activeBuilds", state.activeBuilds)
                                put("completionDepth", state.completionDepth)
                                put("pendingUploads", state.pendingUploads)
                                put("pendingSubmissions", state.pendingSubmissions)
                                put("pendingFences", state.pendingFences)
                                put("retiredPages", state.retiredPages)
                                put("retiredBytes", state.retiredBytes)
                            }
                        },
                    )
                }
                is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Rejected ->
                    future.complete(TerrainDiagnosticDebugOperation.rejectionResult(capture.rejection))
                de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Unavailable ->
                    future.completeExceptionally(DebugOperationException("not_ready", "terrain runtime is unavailable"))
            }
        }

        poll = poll@{
            if (future.isDone) return@poll
            if (
                renderContextOrNull() !== context || context.session !== session ||
                session.world.terrainEpoch != worldEpoch ||
                context.renderer.pipeline.terrainSelection().generation != pipelineGeneration
            ) {
                future.completeExceptionally(
                    DebugOperationException("not_ready", "terrain generation changed while waiting for idle"),
                )
                return@poll
            }
            fun collectSubmissionCompletions() {
                context.renderer[ChunkRenderer]?.regionTerrain?.collectSubmissionCompletions()
                context.renderer.filterIsInstance<DistantTerrainRenderer>()
                    .singleOrNull()
                    ?.collectSubmissionCompletions()
            }
            collectSubmissionCompletions()
            var state = terrainIdleState(context)
            if (state.requiresGpuDrain(parsed.condition)) {
                glFinish()
                collectSubmissionCompletions()
                state = terrainIdleState(context)
            }
            if (state.matches(parsed.condition)) {
                completeAtIdle(state)
                return@poll
            }
            if (System.nanoTime() >= deadlineNanos || request.isExpired) {
                future.completeExceptionally(
                    DebugOperationException(
                        "deadline_exceeded",
                        "terrain ${parsed.condition.name.lowercase()} did not become idle before the bounded deadline",
                    ),
                )
                return@poll
            }
            enqueuePoll()
        }

        context.queue += { poll() }
        return future
    }

    private fun terrainIdleState(context: RenderContext): TerrainIdleState {
        val chunks = context.renderer[ChunkRenderer]
            ?: throw DebugOperationException("not_ready", "chunk renderer is not available")
        val shared = TerrainProcessBuildService.shared.snapshot().runtime
        val near = chunks.regionTerrain?.metrics()
        val distant = context.renderer.filterIsInstance<DistantTerrainRenderer>().singleOrNull()?.hierarchyDiagnostics()
        val distantRetiredPages = distant?.let {
            Math.addExact(
                it.retiredCpuLeasedPages,
                Math.addExact(
                    it.retiredPendingSubmissionPages,
                    Math.addExact(it.retiredFailedSubmissionPages, it.retiredDeviceInvalidatedPages),
                ),
            )
        } ?: 0
        return TerrainIdleState(
            queuedBuilds = Math.addExact(
                Math.addExact(chunks.meshingQueue.size, shared.queueDepth),
                distant?.queuedBuildPages ?: 0,
            ),
            outstandingBuilds = shared.outstanding,
            activeBuilds = shared.active,
            completionDepth = shared.completionDepth,
            pendingUploads = Math.addExact(chunks.loadingQueue.size, distant?.pendingUploadPages ?: 0),
            pendingSubmissions = Math.addExact(
                near?.pendingSubmissionCount ?: 0,
                distant?.pendingSubmissionCount ?: 0,
            ),
            pendingFences = Math.addExact(
                near?.pendingSubmissionFences ?: 0,
                distant?.pendingSubmissionFences ?: 0,
            ),
            retiredPages = Math.addExact(near?.retiredPages ?: 0, distantRetiredPages),
            retiredBytes = Math.addExact(near?.retiredBytes ?: 0L, distant?.retiredBytes ?: 0L),
        )
    }

    private fun terrainCompare(
        context: RenderContext,
        endpointGeneration: Int,
        body: JsonNode,
    ): DebugOperationResult {
        val fixture = TerrainDiagnosticDebugOperation.parseCompareFixture(body)
            ?: throw DebugOperationException("invalid_request", "unknown or malformed terrain comparison fixture")
        val comparison = TerrainDualBuildFixtures.compare(fixture)
        return when (val capture = TerrainProductionDiagnosticCapture.capture(context)) {
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Captured ->
                TerrainDiagnosticDebugOperation.specializedOperationResult(
                    TerrainDiagnosticOperations.COMPARE,
                    endpointGeneration,
                    capture.snapshot,
                ) {
                    put("fixture", comparison.fixture.wireName)
                    put("equivalent", comparison.equivalent)
                    put("referenceDigest", comparison.referenceDigest)
                    put("candidateDigest", comparison.candidateDigest)
                    putArray("differences").apply {
                        comparison.differences.sortedBy { it.name }.forEach { add(it.name) }
                    }
                    put("published", false)
                }
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Rejected ->
                TerrainDiagnosticDebugOperation.rejectionResult(capture.rejection)
            de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Unavailable ->
                throw DebugOperationException("not_ready", "terrain runtime is unavailable")
        }
    }

    private fun terrainFault(
        context: RenderContext,
        endpointGeneration: Int,
        body: JsonNode,
    ): DebugOperationResult {
        val request = TerrainDiagnosticDebugOperation.parseFaultRequest(body)
            ?: throw DebugOperationException(
                "invalid_request",
                "terrain fault requires ARM with fault, RESTORE with restorationToken, or STATUS",
            )
        return when (val capture = TerrainProductionDiagnosticCapture.capture(context)) {
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Captured -> {
                val snapshot = capture.snapshot
                val chunks = context.renderer[ChunkRenderer]
                    ?: throw DebugOperationException("not_ready", "chunk renderer is unavailable")
                val scope = TerrainAcceptanceFaultScope(
                    worldEpoch = snapshot.worldIdentity.worldEpoch,
                    pipelineGeneration = snapshot.pipeline.generation,
                    shaderGeneration = snapshot.pipeline.shaderPipelineGeneration,
                    nearLayoutGeneration = snapshot.pipeline.nearLayout.generation,
                )
                val fault = try {
                    when (request.action) {
                        TerrainFaultAction.ARM -> chunks.terrainFaults.arm(checkNotNull(request.fault), scope)
                        TerrainFaultAction.RESTORE -> chunks.terrainFaults.restore(
                            checkNotNull(request.restorationToken),
                            scope,
                        )
                        TerrainFaultAction.STATUS -> chunks.terrainFaults.snapshot(scope)
                    }
                } catch (error: IllegalStateException) {
                    throw DebugOperationException("not_ready", error.message ?: "terrain fault is already armed")
                } catch (error: IllegalArgumentException) {
                    throw DebugOperationException("invalid_request", error.message ?: "invalid terrain fault request")
                }
                TerrainDiagnosticDebugOperation.specializedOperationResult(
                    TerrainDiagnosticOperations.FAULT,
                    endpointGeneration,
                    snapshot,
                ) {
                    put("action", request.action.name)
                    put("faultGeneration", fault.generation)
                    put("active", fault.active)
                    fault.fault?.let { put("fault", it.wireName) } ?: putNull("fault")
                    fault.restorationToken?.let { put("restorationToken", it) } ?: putNull("restorationToken")
                    put("consumedCount", fault.consumedCount)
                    put("invalidatedCount", fault.invalidatedCount)
                }
            }
            is de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Rejected ->
                TerrainDiagnosticDebugOperation.rejectionResult(capture.rejection)
            de.bixilon.minosoft.debug.terrain.TerrainProductionDiagnosticCaptureResult.Unavailable ->
                throw DebugOperationException("not_ready", "terrain runtime is unavailable")
        }
    }

    private fun renderSubstrate(context: RenderContext): DebugOperationResult {
        val graph = context.renderer.pipeline.generation
        val shader = context.shaderPipeline.selection()
        val shaderStats = context.shaderPipeline.stats()
        val shaderDiagnostics = context.shaderPipeline.diagnostics()
        val result = DebugJson.MAPPER.createObjectNode().apply {
            put("frame", context.frameNumber)
            put("graphGeneration", graph.number)
            put("passCount", graph.passes.size)
            put("shaderGeneration", shader.generation)
            put("shaderOwner", shader.owner.value)
            put("shaderPack", shader.packName)
            put("shaderFingerprint", shader.fingerprint)
            if (shader.programDirectory == null) {
                putNull("shaderProgramDirectory")
            } else {
                put("shaderProgramDirectory", shader.programDirectory)
            }
            put("shaderSunPathRotation", shader.sunPathRotation)
            putObject("shaderSmoothing").apply {
                put("wetnessHalfLife", shader.smoothingDirectives?.wetnessHalfLife)
                put("drynessHalfLife", shader.smoothingDirectives?.drynessHalfLife)
                put("eyeBrightnessHalfLife", shader.smoothingDirectives?.eyeBrightnessHalfLife)
            }
            put("shaderParticlesOrdering", shader.particlesOrdering?.name?.lowercase())
            put("shaderSeparateEntityDraws", shader.separateEntityDraws)
            put("shaderSkipAllRendering", shader.skipAllRendering)
            putObject("shaderShadowRouting").apply {
                put("enabled", shader.shadowDirectives?.enabled)
                put("terrain", shader.shadowDirectives?.terrain)
                put("translucentTerrain", shader.shadowDirectives?.translucentTerrain)
                put("entities", shader.shadowDirectives?.entities)
                put("player", shader.shadowDirectives?.player)
                put("blockEntities", shader.shadowDirectives?.blockEntities)
                put("lightBlockEntities", shader.shadowDirectives?.lightBlockEntities)
                put("distance", shader.shadowDirectives?.distance)
                put("nearPlane", shader.shadowDirectives?.nearPlane)
                put("farPlane", shader.shadowDirectives?.farPlane)
                put("mapFov", shader.shadowDirectives?.mapFov)
                put("intervalSize", shader.shadowDirectives?.intervalSize)
                put("distanceRenderMultiplier", shader.shadowDirectives?.distanceRenderMultiplier)
                put("entityShadowDistanceMultiplier", shader.shadowDirectives?.entityShadowDistanceMultiplier)
                put("voxelDistance", shader.shadowDirectives?.voxelDistance)
                put("cullingMode", shader.shadowDirectives?.cullingMode?.name?.lowercase())
                put("voxelizationDetected", shader.shadowDirectives?.voxelizationDetected)
                put("terrainDistanceLimit", shader.shadowDirectives?.terrainDistanceLimit)
                put("entityDistanceLimit", shader.shadowDirectives?.entityDistanceLimit)
            }
            putObject("shaderResources").apply {
                put("activeLeases", shaderStats.activeLeases)
                put("retiredAwaitingLeases", shaderStats.retiredAwaitingLeases)
                put("closed", shaderStats.closed)
            }
            putObject("shaderPrograms").apply {
                if (shaderDiagnostics.selectedProfile == null) {
                    putNull("selectedProfile")
                } else {
                    put("selectedProfile", shaderDiagnostics.selectedProfile)
                }
                put("compiledScenePrograms", shaderDiagnostics.compiledScenePrograms)
                put("compiledShadowScenePrograms", shaderDiagnostics.compiledShadowScenePrograms)
                putObject("alphaTests").apply {
                    shaderDiagnostics.programAlphaTests.toSortedMap().forEach { (program, test) ->
                        put(program, test)
                    }
                }
                putObject("blendOverrides").apply {
                    shaderDiagnostics.programBlendOverrides.toSortedMap().forEach { (program, blend) ->
                        put(program, blend)
                    }
                }
                putObject("appliedBlendOverrides").apply {
                    shaderDiagnostics.appliedBlendOverrides.toSortedMap().forEach { (override, count) ->
                        put(override, count)
                    }
                }
                putArray("compiledSceneRoutes").also { routes ->
                    shaderDiagnostics.compiledSceneRoutes.sorted().forEach(routes::add)
                }
                putArray("compiledShadowSceneRoutes").also { routes ->
                    shaderDiagnostics.compiledShadowSceneRoutes.sorted().forEach(routes::add)
                }
                put("logicalShaderBuffers", shaderDiagnostics.logicalShaderBuffers)
                put("physicalShaderTextures", shaderDiagnostics.physicalShaderTextures)
                putObject("customShaderTextures").apply {
                    shaderDiagnostics.customShaderTextures.toSortedMap().forEach { (sampler, descriptor) ->
                        put(sampler, descriptor)
                    }
                }
                putObject("customShaderResources").apply {
                    shaderDiagnostics.customShaderResources.toSortedMap().forEach { (resource, descriptor) ->
                        put(resource, descriptor)
                    }
                }
                put("textureArrayUniformUploads", shaderDiagnostics.textureArrayUniformUploads)
                putObject("textureArraySizes").apply {
                    shaderDiagnostics.textureArraySizes.toSortedMap().forEach { (index, size) ->
                        put(index.toString(), size)
                    }
                }
                putObject("shadowTerrainPrograms").apply {
                    shaderDiagnostics.shadowTerrainPrograms.toSortedMap().forEach { (material, program) ->
                        put(material, program)
                    }
                }
                putObject("buffers").apply {
                    shaderDiagnostics.shaderBuffers.toSortedMap().forEach { (buffer, descriptor) ->
                        put(buffer, descriptor)
                    }
                }
                putObject("programOutputs").apply {
                    shaderDiagnostics.programOutputs.toSortedMap().forEach { (program, outputs) ->
                        put(program, outputs)
                    }
                }
                putObject("programFlips").apply {
                    shaderDiagnostics.programFlips.toSortedMap().forEach { (program, flips) ->
                        put(program, flips)
                    }
                }
                putObject("programStages").apply {
                    shaderDiagnostics.programStages.toSortedMap().forEach { (program, stages) ->
                        put(program, stages)
                    }
                }
                putObject("programSamplers").apply {
                    shaderDiagnostics.programSamplers.toSortedMap().forEach { (program, samplers) ->
                        put(program, samplers)
                    }
                }
                putObject("programShadowComparisonSamplers").apply {
                    shaderDiagnostics.programShadowComparisonSamplers.toSortedMap().forEach { (program, samplers) ->
                        put(program, samplers)
                    }
                }
                if (shaderDiagnostics.frameState == null) {
                    putNull("frameState")
                } else {
                    put("frameState", shaderDiagnostics.frameState)
                }
                putObject("frameInputValues").apply {
                    shaderDiagnostics.frameInputValues.toSortedMap().forEach { (name, value) ->
                        put(name, value)
                    }
                }
                putObject("shadowCulling").apply {
                    shaderDiagnostics.shadowCulling.toSortedMap().forEach { (name, value) ->
                        put(name, value)
                    }
                }
                put("frameUniformUploads", shaderDiagnostics.frameUniformUploads)
                put("drawUniformUploads", shaderDiagnostics.drawUniformUploads)
                put("diagnosticTraceSampleRate", shaderDiagnostics.diagnosticTraceSampleRate)
                shaderDiagnostics.activePassCutoff?.let { put("activePassCutoff", it) }
                    ?: putNull("activePassCutoff")
                putObject("drawStateBinds").apply {
                    shaderDiagnostics.drawStateBinds.toSortedMap().forEach { (state, count) ->
                        put(state, count)
                    }
                }
                putObject("entityColorBinds").apply {
                    shaderDiagnostics.entityColorBinds.toSortedMap().forEach { (color, count) ->
                        put(color, count)
                    }
                }
                putObject("renderStageBinds").apply {
                    shaderDiagnostics.renderStageBinds.toSortedMap().forEach { (stage, count) ->
                        put(stage, count)
                    }
                }
                putObject("depthSnapshots").apply {
                    shaderDiagnostics.depthSnapshots.toSortedMap().forEach { (snapshot, count) ->
                        put(snapshot, count)
                    }
                }
                putObject("fullscreenProgramExecutions").apply {
                    shaderDiagnostics.fullscreenProgramExecutions.toSortedMap().forEach { (program, count) ->
                        put(program, count)
                    }
                }
                putObject("selectedTerrainBinds").apply {
                    shaderDiagnostics.selectedTerrainBinds.toSortedMap().forEach { (program, count) -> put(program, count) }
                }
                putObject("selectedSceneBinds").apply {
                    shaderDiagnostics.selectedSceneBinds.toSortedMap().forEach { (program, count) -> put(program, count) }
                }
                putObject("selectedSceneContracts").apply {
                    shaderDiagnostics.selectedSceneContracts.toSortedMap().forEach { (contract, count) -> put(contract, count) }
                }
                putObject("submittedSceneDraws").apply {
                    shaderDiagnostics.submittedSceneDraws.toSortedMap().forEach { (contract, count) -> put(contract, count) }
                }
                putObject("submittedSceneVertices").apply {
                    shaderDiagnostics.submittedSceneVertices.toSortedMap().forEach { (contract, count) -> put(contract, count) }
                }
                putArray("submittedMainSceneVertexAbis").apply {
                    shaderDiagnostics.submittedMainSceneVertexAbis.sorted().forEach { add(it) }
                }
                putArray("submittedMainSceneStateAbis").apply {
                    shaderDiagnostics.submittedMainSceneStateAbis.sorted().forEach { add(it) }
                }
                putArray("unsubmittedCompiledSceneVertexAbis").apply {
                    shaderDiagnostics.unsubmittedCompiledSceneVertexAbis.sorted().forEach { add(it) }
                }
                putArray("unsubmittedCompiledSceneStateAbis").apply {
                    shaderDiagnostics.unsubmittedCompiledSceneStateAbis.sorted().forEach { add(it) }
                }
                putObject("rejectedSceneBinds").apply {
                    shaderDiagnostics.rejectedSceneBinds.toSortedMap().forEach { (program, count) -> put(program, count) }
                }
                putObject("fallbackSceneBinds").apply {
                    shaderDiagnostics.fallbackSceneBinds.toSortedMap().forEach { (program, count) -> put(program, count) }
                }
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

        (context.system as? OpenGlRenderSystem)?.let { system ->
            val snapshot = system.resources.snapshot()
            val work = system.work.snapshot()
            val previousWork = lastOpenGlWorkCapture?.takeIf { it.system === system }?.snapshot
            lastOpenGlWorkCapture = OpenGlWorkCapture(system, work)
            result.putOpenGlWork("openGlWork", work)
            if (previousWork != null) result.putOpenGlWork("openGlWorkDelta", work, previousWork)
            val capabilities = OpenGlCapabilityDiagnostics.capture()
            val gpuTimings = system.gpuTimingDiagnostics()
            result.putObject("gpuResources").apply {
                put("backend", "opengl")
                put("created", snapshot.created)
                put("deleted", snapshot.deleted)
                put("live", snapshot.live)
                putObject("passTimings").apply {
                    put("sampleRate", gpuTimings.sampleRate)
                    put("pendingQueries", gpuTimings.pendingQueries)
                    put("droppedSamples", gpuTimings.droppedSamples)
                    putObject("passes").apply {
                        gpuTimings.passes.toSortedMap().forEach { (name, timing) ->
                            putObject(name).apply {
                                put("samples", timing.samples)
                                put("lastNanos", timing.lastNanos)
                                put("totalNanos", timing.totalNanos)
                                put("minimumNanos", timing.minimumNanos)
                                put("maximumNanos", timing.maximumNanos)
                                put("medianUpperBoundNanos", timing.medianUpperBoundNanos)
                                put("p95UpperBoundNanos", timing.p95UpperBoundNanos)
                                putArray("bucketUpperBoundsNanos").also { bounds ->
                                    timing.bucketUpperBoundsNanos.forEach(bounds::add)
                                }
                                putArray("buckets").also { buckets -> timing.buckets.forEach(buckets::add) }
                            }
                        }
                    }
                }
                putObject("capabilities").apply {
                    put("version", capabilities.version)
                    put("vendor", capabilities.vendor)
                    put("renderer", capabilities.renderer)
                    put("openGl40", capabilities.openGl40)
                    put("openGl42", capabilities.openGl42)
                    put("openGl43", capabilities.openGl43)
                    put("openGl44", capabilities.openGl44)
                    put("arbClearTexture", capabilities.arbClearTexture)
                    put("arbDrawBuffersBlend", capabilities.arbDrawBuffersBlend)
                    put("irisTessellation", capabilities.irisTessellation)
                    put("irisPerBufferBlending", capabilities.irisPerBufferBlending)
                    put("irisRenderTargetImages", capabilities.irisRenderTargetImages)
                    put("irisCustomImages", capabilities.irisCustomImages)
                    put("irisCompute", capabilities.irisCompute)
                    put("irisShaderStorageBuffers", capabilities.irisShaderStorageBuffers)
                }
                val types = putArray("types")
                snapshot.types.forEach { type ->
                    types.addObject().apply {
                        put("type", type.type.name.lowercase())
                        put("created", type.created)
                        put("deleted", type.deleted)
                        put("live", type.live)
                    }
                }
            }
        }
        (context.textures as? OpenGlTextureManager)?.static
            ?.materialAnimationDiagnostics()
            ?.let { animations ->
                result.putObject("materialAnimations").apply {
                    put("textures", animations.textures)
                    put("channels", animations.channels)
                    put("advances", animations.advances)
                    put("uploads", animations.uploads)
                    putArray("resources").also { resources ->
                        animations.resources.forEach(resources::add)
                    }
                    putArray("states").also { states ->
                        animations.states.forEach { state ->
                            states.addObject().apply {
                                put("kind", state.kind)
                                put("resource", state.resource)
                                put("uploadedFrameIndex", state.uploadedFrameIndex)
                            }
                        }
                    }
                }
            }

        context.renderer[ChunkRenderer]?.let { chunks ->
            val terrain = chunks.terrain.selection()
            val descriptor = chunks.terrain.descriptor()
            val terrainStats = chunks.terrain.stats()
            result.putObject("terrain").apply {
                put("runtimeMode", "unified")
                put("semanticArtifacts", true)
                put("regionStorageSelected", true)
                put("distantHierarchySelected", true)
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
                putTerrainPerformance(
                    name = "productionRuntime",
                    snapshot = chunks.terrainPerformance.snapshot(),
                    includeBuckets = true,
                )
                val nearOwnership = chunks.loaded.ownershipSnapshot()
                nearOwnership.lifecycle?.let { coverage ->
                    putObject("nearCoverage").apply {
                        put("revision", nearOwnership.revision)
                        put("lifecycleRevision", coverage.lifecycleRevision)
                        put("worldEpoch", coverage.worldEpoch)
                        put("providerGeneration", coverage.providerGeneration)
                        put("coveredChunks", nearOwnership.chunks.size)
                        putObject("states").apply {
                            coverage.cells.groupingBy { it.state.name.lowercase() }
                                .eachCount()
                                .toSortedMap()
                                .forEach { (state, count) -> put(state, count) }
                        }
                        putArray("cells").also { cells ->
                            coverage.cells.forEach { cell ->
                                cells.addObject().apply {
                                    put("x", cell.page.x)
                                    put("y", cell.page.y)
                                    put("z", cell.page.z)
                                    put("detailLevel", cell.page.detailLevel)
                                    put("state", cell.state.name.lowercase())
                                    put("surfaceRelevant", cell.surfaceRelevant)
                                    put("contributesCoverage", cell.contributesCoverage)
                                    put("transitionAgeFrames", cell.transitionAgeFrames)
                                    put("coverageAgeFrames", cell.coverageAgeFrames)
                                }
                            }
                        }
                    }
                }
                chunks.regionTerrain?.metrics()?.let { region ->
                    putObject("regionStorage").apply {
                        put("deviceCapacityBytes", region.deviceCapacityBytes)
                        put("regions", region.regions)
                        put("activePages", region.activePages)
                        put("retiredPages", region.retiredPages)
                        put("residentBytes", region.residentBytes)
                        put("retiredBytes", region.retiredBytes)
                        put("vertexAllocatedBytes", region.vertexAllocatedBytes)
                        put("indexAllocatedBytes", region.indexAllocatedBytes)
                        put("vertexHighWaterBytes", region.vertexHighWaterBytes)
                        put("indexHighWaterBytes", region.indexHighWaterBytes)
                        put("stagingCapacityBytes", region.stagingCapacityBytes)
                        put("uploadedBytes", region.uploadedBytes)
                        put("uploadNanos", region.uploadNanos)
                        put("publications", region.publications)
                        put("allocationFailures", region.allocationFailures)
                        put("uploadFailures", region.uploadFailures)
                        put("cpuLeaseCount", region.cpuLeaseCount)
                        put("pendingSubmissionCount", region.pendingSubmissionCount)
                        put("failedSubmissionCount", region.failedSubmissionCount)
                        put("invalidatedSubmissionCount", region.invalidatedSubmissionCount)
                        put("retiredCpuLeasedPages", region.retiredCpuLeasedPages)
                        put("retiredPendingSubmissionPages", region.retiredPendingSubmissionPages)
                        put("retiredFailedSubmissionPages", region.retiredFailedSubmissionPages)
                        put("retiredDeviceInvalidatedPages", region.retiredDeviceInvalidatedPages)
                        put("deviceInvalidations", region.deviceInvalidations)
                        put("batchCacheEntries", region.batchCacheEntries)
                        put("batchBuilds", region.batchBuilds)
                        put("batchHits", region.batchHits)
                        put("batchEvictions", region.batchEvictions)
                        put("drawBatches", region.drawBatches)
                        put("drawCommands", region.drawCommands)
                        put("drawVertices", region.drawVertices)
                        put("pendingSubmissionFences", region.pendingSubmissionFences)
                    }
                }
                val visibleMeshes = chunks.visibility.meshes
                visibleMeshes.lock.locked {
                    putObject("visibleMeshes").apply {
                        ChunkMeshTypes.VALUES.forEach { type ->
                            val meshes = visibleMeshes.meshes[type.ordinal]
                            putObject(type.name.lowercase()).apply {
                                put("meshes", meshes.size)
                                put("vertices", meshes.sumOf { it.buffer.vertices })
                                putObject("states").apply {
                                    meshes.groupingBy { it.state.name.lowercase() }
                                        .eachCount()
                                        .toSortedMap()
                                        .forEach { (state, count) -> put(state, count) }
                                }
                                putObject("occlusion").apply {
                                    meshes.groupingBy { it.occlusion.name.lowercase() }
                                        .eachCount()
                                        .toSortedMap()
                                        .forEach { (state, count) -> put(state, count) }
                                }
                            }
                        }
                        put("blockEntities", visibleMeshes.entities.size)
                    }
                }
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

    private fun configureTerrainTelemetry(context: RenderContext, body: JsonNode): DebugOperationResult {
        val renderer = context.renderer[ChunkRenderer]
            ?: throw DebugOperationException("not_ready", "chunk renderer is not available")
        val enabledNode = body.get("enabled")
        if (enabledNode == null || !enabledNode.isBoolean) {
            throw DebugOperationException("invalid_request", "enabled must be a boolean")
        }
        val queuedBuilds = renderer.meshingQueue.size
        val outstandingBuilds = renderer.meshingQueue.tasks.size
        val pendingUploads = renderer.loadingQueue.size
        if (queuedBuilds != 0 || outstandingBuilds != 0 || pendingUploads != 0) {
            throw DebugOperationException(
                "not_ready",
                "terrain telemetry can only change at an idle boundary " +
                    "(queued=$queuedBuilds, outstanding=$outstandingBuilds, uploads=$pendingUploads)",
            )
        }
        val telemetry = renderer.terrainPerformance
        val previous = telemetry.enabled
        telemetry.enabled = enabledNode.booleanValue()
        telemetry.queueDepth(0)
        telemetry.outstandingBuilds(0)
        telemetry.pendingUploads(0)
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("previousEnabled", previous)
            putTerrainPerformance("terrain", telemetry.snapshot(), includeBuckets = true)
        })
    }

    private fun ObjectNode.putTerrainPerformance(
        name: String,
        snapshot: TerrainPerformanceSnapshot,
        includeBuckets: Boolean,
    ) {
        putObject(name).apply {
            put("enabled", snapshot.enabled)
            put("configuredWorkers", snapshot.configuredWorkers)
            put("queuedBuilds", snapshot.queuedBuilds)
            put("outstandingBuilds", snapshot.outstandingBuilds)
            put("activeWorkers", snapshot.activeWorkers)
            put("observationNanos", snapshot.observationNanos)
            put("cumulativeWorkerUtilization", snapshot.cumulativeWorkerUtilization)
            put("pendingUploads", snapshot.pendingUploads)
            put("queueHighWater", snapshot.queueHighWater)
            put("activeWorkerHighWater", snapshot.activeWorkerHighWater)
            put("pendingUploadHighWater", snapshot.pendingUploadHighWater)
            put("currentWorkerUtilization", if (snapshot.configuredWorkers == 0) 0.0 else {
                snapshot.activeWorkers.toDouble() / snapshot.configuredWorkers
            })
            put("requestedBuilds", snapshot.requestedBuilds)
            put("startedBuilds", snapshot.startedBuilds)
            put("successfulBuilds", snapshot.successfulBuilds)
            put("cancelledBuilds", snapshot.cancelledBuilds)
            put("staleBuilds", snapshot.staleBuilds)
            put("rejectedBuilds", snapshot.rejectedBuilds)
            put("failedBuilds", snapshot.failedBuilds)
            put("failedUploads", snapshot.failedUploads)
            put("outputBytes", snapshot.outputBytes)
            put("uploadedBytes", snapshot.uploadedBytes)
            put("visibleSections", snapshot.visibleSections)
            putObject("buildCauses").apply {
                snapshot.requestedByCause.forEach { (cause, requested) ->
                    putObject(cause.wireName).apply {
                        put("requested", requested)
                        put("started", snapshot.startedByCause.getValue(cause))
                        put("suppressed", snapshot.suppressedByCause.getValue(cause))
                    }
                }
            }
            putObject("phases").apply {
                snapshot.phases.forEach { (phase, latency) ->
                    putObject(phase.wireName).apply {
                        put("samples", latency.samples)
                        put("totalNanos", latency.totalNanos)
                        put("maximumNanos", latency.maximumNanos)
                        put("medianNanos", latency.medianNanos)
                        put("p95Nanos", latency.p95Nanos)
                        if (includeBuckets) {
                            putArray("bucketUpperBoundsNanos").also { bounds ->
                                latency.bucketUpperBoundsNanos.forEach(bounds::add)
                            }
                            putArray("buckets").also { buckets ->
                                latency.buckets.forEach(buckets::add)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs the production content-fidelity transaction on the render thread.
     * A named rejection checkpoint is deliberately limited to rollback
     * acceptance and cannot mutate the mounted asset or data-pack stacks.
     */
    private fun reloadContent(context: RenderContext, body: JsonNode): DebugOperationResult {
        val rejectAt = body["rejectAt"]?.let { value ->
            if (!value.isTextual) {
                throw DebugOperationException("invalid_request", "rejectAt must be a string")
            }
            ContentReloadRejectionPoint.entries.singleOrNull { it.wireName == value.asText() }
                ?: throw DebugOperationException(
                    "invalid_request",
                    "rejectAt must be one of: ${ContentReloadRejectionPoint.entries.joinToString { it.wireName }}",
                )
        }
        val generationBefore = context.session.contentFidelity.generationId
        val gpuBefore = (context.system as? OpenGlRenderSystem)?.resources?.snapshot()
        var generation: Long? = null
        try {
            FabricResourceReloadEvents.run(
                session = context.session,
                type = FabricResourceReloadType.CONTENT_FIDELITY,
                prepare = { Unit },
                apply = {
                    generation = rejectAt?.let(context.models.skeletal::reloadContentFidelityForAcceptance)
                        ?: context.models.skeletal.reloadContentFidelity()
                },
            )
        } catch (error: Throwable) {
            if (rejectAt == null || error !is ContentReloadRejectedException || error.rejectionPoint != rejectAt) {
                throw error
            }
            val generationAfter = context.session.contentFidelity.generationId
            val gpuAfter = (context.system as? OpenGlRenderSystem)?.resources?.snapshot()
            return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
                put("accepted", false)
                put("expectedRejection", true)
                put("rejectionPoint", rejectAt.wireName)
                put("generationBefore", generationBefore)
                put("generationAfter", generationAfter)
                put("generationUnchanged", generationAfter == generationBefore)
                put("frame", context.frameNumber)
                if (gpuBefore != null && gpuAfter != null) {
                    putGpuReloadDelta(this, gpuBefore, gpuAfter)
                }
            })
        }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("accepted", true)
            put("generationBefore", generationBefore)
            put("generation", requireNotNull(generation))
            put("frame", context.frameNumber)
        })
    }

    private fun putGpuReloadDelta(
        result: ObjectNode,
        before: OpenGlResourceSnapshot,
        after: OpenGlResourceSnapshot,
    ) {
        val created = after.created - before.created
        val deleted = after.deleted - before.deleted
        val balanced = created == deleted && before.types.indices.all { index ->
            before.types[index].type == after.types[index].type &&
                before.types[index].live == after.types[index].live
        }
        result.put("gpuBalanced", balanced)
        result.putObject("gpuResourceDelta").apply {
            put("created", created)
            put("deleted", deleted)
            put("live", after.live - before.live)
            val types = putArray("types")
            before.types.zip(after.types).forEach { (old, new) ->
                types.addObject().apply {
                    put("type", old.type.name.lowercase())
                    put("created", new.created - old.created)
                    put("deleted", new.deleted - old.deleted)
                    put("live", new.live - old.live)
                }
            }
        }
    }

    /**
     * Executes a mounted function only for the source-native local connection.
     * The explicit origin/camera pair makes rendered fixture runs repeatable
     * without adding an unrestricted world mutation surface to remote servers.
     */
    private fun executeLocalContent(context: RenderContext, body: JsonNode): DebugOperationResult {
        val function = body["function"]?.takeIf(JsonNode::isTextual)?.asText()
            ?: throw DebugOperationException("invalid_request", "function is required")
        if (!function.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")) || function.length > 256) {
            throw DebugOperationException("invalid_request", "function is not a bounded resource location")
        }
        val arguments = linkedMapOf<String, String>()
        val argumentsNode = body.path("arguments")
        if (!argumentsNode.isMissingNode && !argumentsNode.isObject) {
            throw DebugOperationException("invalid_request", "arguments must be an object")
        }
        if (argumentsNode.size() > MAX_FUNCTION_ARGUMENTS) {
            throw DebugOperationException("limit_exceeded", "too many function arguments")
        }
        var argumentBytes = 0
        argumentsNode.properties().forEach { (key, value) ->
            if (!key.matches(Regex("[A-Za-z0-9_.-]+")) || key.length > 128 || !value.isTextual) {
                throw DebugOperationException("invalid_request", "function arguments must use bounded string keys and values")
            }
            val text = value.asText()
            argumentBytes += key.toByteArray().size + text.toByteArray().size
            if (argumentBytes > MAX_FUNCTION_ARGUMENT_BYTES) {
                throw DebugOperationException("limit_exceeded", "function arguments exceed $MAX_FUNCTION_ARGUMENT_BYTES bytes")
            }
            arguments[key] = text
        }

        val origin = localPose(body.path("origin"), "origin")
        val camera = localPose(body.path("camera"), "camera")
        val connection = context.session.connection as? LocalConnection
            ?: throw DebugOperationException("not_ready", "content.execute-local requires an active local world")
        val execution = try {
            connection.executeDataPackFunction(
                reference = function,
                arguments = arguments,
                origin = origin.position,
                originRotation = origin.rotation,
                camera = camera.position,
                cameraRotation = camera.rotation,
            )
        } catch (error: IllegalStateException) {
            throw DebugOperationException("not_ready", error.message ?: "local data-pack runtime is not ready")
        } catch (error: IllegalArgumentException) {
            throw DebugOperationException("invalid_request", error.message ?: "local data-pack function was rejected")
        }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("function", function)
            put("executed", execution.executed)
            put("contentGeneration", execution.generation)
            put("dataPackTick", execution.tick)
            put("frame", context.frameNumber)
            set<ObjectNode>("origin", localPoseJson(origin))
            set<ObjectNode>("camera", localPoseJson(camera))
        })
    }

    private fun localPose(node: JsonNode, name: String): LocalPose {
        if (!node.isObject) throw DebugOperationException("invalid_request", "$name pose is required")
        val x = boundedCoordinate(node, "x", name)
        val y = boundedCoordinate(node, "y", name)
        val z = boundedCoordinate(node, "z", name)
        val yaw = finiteDouble(node, "yaw", name).toFloat()
        val pitch = finiteDouble(node, "pitch", name).toFloat()
        if (pitch !in -90.0f..90.0f) throw DebugOperationException("invalid_request", "$name pitch must be within -90..90")
        return LocalPose(Vec3d(x, y, z), EntityRotation(yaw, pitch))
    }

    private fun optionalLocalPose(node: JsonNode, name: String): LocalPose? {
        if (node.isMissingNode || node.isNull) return null
        return localPose(node, name)
    }

    /**
     * Places a bounded set of real blocks into the source-native local world.
     * The explicit optional origin/camera pair keeps preview captures repeatable
     * without adding an unrestricted world mutation surface to remote servers.
     */
    private fun placeLocalBlocks(context: RenderContext, body: JsonNode): DebugOperationResult {
        val session = context.session
        val connection = session.connection as? LocalConnection
            ?: throw DebugOperationException("not_ready", "content.place-blocks requires an active local world")
        val blocksNode = body["blocks"]
        if (!blocksNode.isArray || blocksNode.isEmpty) {
            throw DebugOperationException("invalid_request", "blocks must be a non-empty array")
        }
        if (blocksNode.size() > MAX_PLACE_BLOCKS) {
            throw DebugOperationException("limit_exceeded", "blocks exceed the $MAX_PLACE_BLOCKS placement limit")
        }
        val placements = blocksNode.map { node ->
            if (!node.isObject) throw DebugOperationException("invalid_request", "each block must be an object")
            LocalConnection.LocalBlockPlacement(blockPosition(node), blockState(session, node["state"]))
        }
        val chunks = placements.mapTo(hashSetOf()) { it.position.chunkPosition }
        if (chunks.size > MAX_PLACE_BLOCKS_CHUNKS) {
            throw DebugOperationException("limit_exceeded", "block placements touch ${chunks.size} chunks, exceeding $MAX_PLACE_BLOCKS_CHUNKS")
        }
        val placed = try {
            connection.placeBlocks(placements)
        } catch (error: IllegalStateException) {
            throw DebugOperationException("not_ready", error.message ?: "local world is not ready for block placement")
        } catch (error: IllegalArgumentException) {
            throw DebugOperationException("invalid_request", error.message ?: "block placement was rejected")
        }
        val origin = optionalLocalPose(body.path("origin"), "origin")
        val camera = optionalLocalPose(body.path("camera"), "camera")
        origin?.let { teleportLocalPose(session, it) }
        camera?.let { teleportLocalPose(session, it) }
        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("placed", placed)
            put("frame", context.frameNumber)
            set<ObjectNode>("origin", origin?.let(::localPoseJson))
            set<ObjectNode>("camera", camera?.let(::localPoseJson))
        })
    }

    /**
     * Places one deterministic page from the complete legal state set of a block.
     * Unused slots are cleared in the same replacement, so repeated and out-of-order
     * requests for one block/page size are independent of the previously viewed page.
     */
    private fun placeBlockStateSculpture(context: RenderContext, body: JsonNode): DebugOperationResult {
        val session = context.session
        val connection = session.connection as? LocalConnection
            ?: throw DebugOperationException("not_ready", "content.place-block-state-sculpture requires an active local world")
        val blockName = body["block"]?.takeIf(JsonNode::isTextual)?.asText()
            ?: throw DebugOperationException("invalid_request", "block must be a resource identifier")
        if (blockName.isBlank() || blockName.length > 256) {
            throw DebugOperationException("invalid_request", "block must contain 1..256 characters")
        }
        val identifier = try {
            ResourceLocation.of(blockName)
        } catch (error: IllegalArgumentException) {
            throw DebugOperationException("invalid_request", error.message ?: "invalid block resource identifier")
        }
        val block = session.registries.block[identifier]
            ?: throw DebugOperationException("invalid_request", "unknown block $identifier")
        val pageIndex = boundedSculptureInteger(body, "page", 0, 0, Int.MAX_VALUE)
        val pageSize = boundedSculptureInteger(
            body,
            "pageSize",
            BlockStateSculptureCatalog.DEFAULT_PAGE_SIZE,
            BlockStateSculptureCatalog.MIN_PAGE_SIZE,
            BlockStateSculptureCatalog.MAX_PAGE_SIZE,
        )
        val page = try {
            BlockStateSculptureCatalog.page(block, pageIndex, pageSize)
        } catch (error: IllegalArgumentException) {
            throw DebugOperationException("invalid_request", error.message ?: "invalid sculpture page")
        }
        val air = session.registries.block[ResourceLocation.of("minecraft:air")]?.states?.default
            ?: throw DebugOperationException("not_ready", "the local registry has no air block")
        val origin = optionalLocalPose(body.path("origin"), "origin")
        val camera = optionalLocalPose(body.path("camera"), "camera")
        val placements = page.entries.flatMap { entry ->
            listOf(LocalConnection.LocalBlockPlacement(entry.position, entry.state)) +
                entry.context.map { LocalConnection.LocalBlockPlacement(it.position, it.state) }
        }
        try {
            // Publish only final cell values. Replaying an unchanged page then
            // preserves the settled meshes and lighting through World.set.
            val replacements = page.slots.associateWithTo(linkedMapOf()) { air }
            for (placement in placements) replacements[placement.position] = placement.state
            connection.placeBlocks(replacements.map { (position, state) ->
                LocalConnection.LocalBlockPlacement(position, state)
            })
        } catch (error: IllegalStateException) {
            throw DebugOperationException("not_ready", error.message ?: "local world is not ready for sculpture placement")
        } catch (error: IllegalArgumentException) {
            throw DebugOperationException("invalid_request", error.message ?: "sculpture placement was rejected")
        }
        origin?.let { teleportLocalPose(session, it) }
        camera?.let { teleportLocalPose(session, it) }

        return DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().apply {
            put("schema", 1)
            put("block", block.identifier.toString())
            put("page", page.pageIndex + 1)
            put("pageIndex", page.pageIndex)
            put("pageSize", page.pageSize)
            put("totalPages", page.totalPages)
            put("totalStates", page.totalStates)
            put("stateStart", page.entries.first().catalogIndex)
            put("stateEnd", page.entries.last().catalogIndex + 1)
            put("columns", page.columns)
            put("rows", page.rows)
            put("spacing", page.spacing)
            put("catalogSha256", page.fingerprint)
            put("placed", placements.size)
            put("catalogStates", page.entries.size)
            put("frame", context.frameNumber)
            set<ObjectNode>("origin", origin?.let(::localPoseJson))
            set<ObjectNode>("camera", camera?.let(::localPoseJson))
            putArray("states").apply {
                for (entry in page.entries) addObject().apply {
                    put("index", entry.catalogIndex)
                    put("key", entry.key)
                    set<ObjectNode>("position", positionJson(entry.position))
                    set<ObjectNode>("state", blockStateJson(entry.state))
                    putArray("context").apply {
                        for (context in entry.context) addObject().apply {
                            set<ObjectNode>("position", positionJson(context.position))
                            set<ObjectNode>("state", blockStateJson(context.state))
                        }
                    }
                }
            }
        })
    }

    private fun boundedSculptureInteger(body: JsonNode, field: String, default: Int, minimum: Int, maximum: Int): Int {
        val node = body[field] ?: return default
        if (!node.isIntegralNumber || !node.canConvertToInt()) {
            throw DebugOperationException("invalid_request", "$field must be an integer")
        }
        val value = node.intValue()
        if (value !in minimum..maximum) {
            throw DebugOperationException("invalid_request", "$field must be within $minimum..$maximum")
        }
        return value
    }

    private fun blockStateJson(state: BlockState) = DebugJson.MAPPER.createObjectNode().apply {
        put("Name", state.block.identifier.toString())
        putObject("Properties").apply {
            for ((property, value) in state.properties.entries.sortedBy { it.key.name }) {
                put(property.name, BlockStateSculptureCatalog.canonicalValue(state, property, value))
            }
        }
    }

    private fun blockState(session: PlaySession, node: JsonNode): BlockState {
        val raw: Any = when {
            node.isTextual -> node.asText()
            node.isObject -> DebugJson.MAPPER.convertValue(node, Map::class.java)
            else -> throw DebugOperationException("invalid_request", "block state must be an identifier or an object")
        }
        return session.parseLocalBlockState(raw)
            ?: throw DebugOperationException("invalid_request", "unknown block state")
    }

    private fun teleportLocalPose(session: PlaySession, pose: LocalPose) {
        session.player.physics.forceTeleport(pose.position)
        session.player.physics.forceSetRotation(pose.rotation)
        session.player.physics.forceSetHeadYaw(pose.rotation.yaw)
    }

    private fun boundedCoordinate(node: JsonNode, field: String, name: String): Double {
        val value = finiteDouble(node, field, name)
        if (value !in -30_000_000.0..30_000_000.0) {
            throw DebugOperationException("invalid_request", "$name $field exceeds the world coordinate boundary")
        }
        return value
    }

    private fun finiteDouble(node: JsonNode, field: String, name: String): Double {
        val value = node[field]?.takeIf(JsonNode::isNumber)?.doubleValue()
            ?: throw DebugOperationException("invalid_request", "$name $field must be numeric")
        if (!value.isFinite()) throw DebugOperationException("invalid_request", "$name $field must be finite")
        return value
    }

    private fun localPoseJson(pose: LocalPose) = DebugJson.MAPPER.createObjectNode().apply {
        put("x", pose.position.x); put("y", pose.position.y); put("z", pose.position.z)
        put("yaw", pose.rotation.yaw); put("pitch", pose.rotation.pitch)
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

    private fun onRenderAsync(
        work: (RenderContext) -> CompletableFuture<DebugOperationResult>,
    ): CompletableFuture<DebugOperationResult> {
        val context = renderContextOrNull() ?: throw DebugOperationException("not_ready", "no active render context")
        val future = CompletableFuture<DebugOperationResult>()
        context.queue += {
            if (!future.isDone) {
                try {
                    work(context).whenComplete { result, error ->
                        if (error == null) future.complete(result) else future.completeExceptionally(error)
                    }
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
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

    private data class LocalPose(
        val position: Vec3d,
        val rotation: EntityRotation,
    )

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

    private fun ObjectNode.putOpenGlWork(
        name: String,
        value: OpenGlWorkSnapshot,
        previous: OpenGlWorkSnapshot? = null,
    ) = putObject(name).apply {
        fun delta(current: Long, old: Long = 0L) = if (previous == null) current else current - old
        put("physicalDrawCalls", delta(value.physicalDrawCalls, previous?.physicalDrawCalls ?: 0L))
        put("drawArrays", delta(value.drawArrays, previous?.drawArrays ?: 0L))
        put("drawElements", delta(value.drawElements, previous?.drawElements ?: 0L))
        put("drawElementsBaseVertex", delta(value.drawElementsBaseVertex, previous?.drawElementsBaseVertex ?: 0L))
        put("multiDrawElementsBaseVertex", delta(value.multiDrawElementsBaseVertex, previous?.multiDrawElementsBaseVertex ?: 0L))
        put("logicalDrawCommands", delta(value.logicalDrawCommands, previous?.logicalDrawCommands ?: 0L))
        put("submittedElements", delta(value.submittedElements, previous?.submittedElements ?: 0L))
        put("programRequests", delta(value.programRequests, previous?.programRequests ?: 0L))
        put("programChanges", delta(value.programChanges, previous?.programChanges ?: 0L))
        put("framebufferBindRequests", delta(value.framebufferBindRequests, previous?.framebufferBindRequests ?: 0L))
        put("framebufferBinds", delta(value.framebufferBinds, previous?.framebufferBinds ?: 0L))
        put("framebufferAttachmentChanges", delta(value.framebufferAttachmentChanges, previous?.framebufferAttachmentChanges ?: 0L))
        put("drawBufferChanges", delta(value.drawBufferChanges, previous?.drawBufferChanges ?: 0L))
        put("readBufferChanges", delta(value.readBufferChanges, previous?.readBufferChanges ?: 0L))
        put("framebufferCompletenessChecks", delta(value.framebufferCompletenessChecks, previous?.framebufferCompletenessChecks ?: 0L))
        put("activeTextureRequests", delta(value.activeTextureRequests, previous?.activeTextureRequests ?: 0L))
        put("activeTextureChanges", delta(value.activeTextureChanges, previous?.activeTextureChanges ?: 0L))
        put("textureBindRequests", delta(value.textureBindRequests, previous?.textureBindRequests ?: 0L))
        put("textureBinds", delta(value.textureBinds, previous?.textureBinds ?: 0L))
        put("textureBinds2d", delta(value.textureBinds2d, previous?.textureBinds2d ?: 0L))
        put("textureBinds2dArray", delta(value.textureBinds2dArray, previous?.textureBinds2dArray ?: 0L))
        put("textureBindsOther", delta(value.textureBindsOther, previous?.textureBindsOther ?: 0L))
        put("imageBindRequests", delta(value.imageBindRequests, previous?.imageBindRequests ?: 0L))
        put("imageBinds", delta(value.imageBinds, previous?.imageBinds ?: 0L))
        put("samplerParameterChanges", delta(value.samplerParameterChanges, previous?.samplerParameterChanges ?: 0L))
        put("vaoBindRequests", delta(value.vaoBindRequests, previous?.vaoBindRequests ?: 0L))
        put("vaoBinds", delta(value.vaoBinds, previous?.vaoBinds ?: 0L))
        put("bufferBindRequests", delta(value.bufferBindRequests, previous?.bufferBindRequests ?: 0L))
        put("bufferBinds", delta(value.bufferBinds, previous?.bufferBinds ?: 0L))
        put("uniformUploads", delta(value.uniformUploads, previous?.uniformUploads ?: 0L))
        put("scalarUniformUploads", delta(value.scalarUniformUploads, previous?.scalarUniformUploads ?: 0L))
        put("vectorUniformUploads", delta(value.vectorUniformUploads, previous?.vectorUniformUploads ?: 0L))
        put("matrixUniformUploads", delta(value.matrixUniformUploads, previous?.matrixUniformUploads ?: 0L))
        put("samplerUniformUploads", delta(value.samplerUniformUploads, previous?.samplerUniformUploads ?: 0L))
        put("uniformBlockBindings", delta(value.uniformBlockBindings, previous?.uniformBlockBindings ?: 0L))
        put("uniformBufferUploads", delta(value.uniformBufferUploads, previous?.uniformBufferUploads ?: 0L))
        put("uniformBufferUploadBytes", delta(value.uniformBufferUploadBytes, previous?.uniformBufferUploadBytes ?: 0L))
    }

    private data class OpenGlWorkCapture(
        val system: OpenGlRenderSystem,
        val snapshot: OpenGlWorkSnapshot,
    )

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
        lastOpenGlWorkCapture = null
        // A client generation owns these presentation checkpoints. Restore
        // only values still owned by this channel, then release the session
        // references so a later generation can prepare its own reference.
        preparedVisualReferenceTime?.let { prepared ->
            val world = prepared.session.world
            if (world.presentationTimeOverride === prepared.appliedOverride) {
                world.presentationTimeOverride = prepared.previousOverride
            }
        }
        preparedVisualReferenceWeather?.let { prepared ->
            val world = prepared.session.world
            if (world.presentationWeatherOverride === prepared.appliedOverride) {
                world.presentationWeatherOverride = prepared.previousOverride
            }
        }
        preparedVisualReferenceTime = null
        preparedVisualReferenceWeather = null
        val current = channel ?: return
        channel = null
        PlaySession.collectSessions().asSequence()
            .mapNotNull { it.rendering?.context }
            .mapNotNull { runCatching { it.renderer[ChunkRenderer] }.getOrNull() }
            .forEach { it.terrainFaults.invalidate() }
        try { current.close() } catch (error: Throwable) { error.printStackTrace() }
    }
}
