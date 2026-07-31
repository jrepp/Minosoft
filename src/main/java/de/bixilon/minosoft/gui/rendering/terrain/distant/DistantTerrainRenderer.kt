/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.terrain.distant

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.NativeTerrainOwnershipSnapshot
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.light.LightmapBuffer
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDistantFrameState
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.LayerSettings
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.types.CameraPositionShader
import de.bixilon.minosoft.gui.rendering.shader.types.LightShader
import de.bixilon.minosoft.gui.rendering.shader.types.ViewProjectionShader
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.system.base.layer.OpaqueLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.TranslucentLayer
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantLodRenderCellDiagnostic
import de.bixilon.minosoft.terrain.distant.DistantLodRenderDiagnostics
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.DistantLodTileSource
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderConfig
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderSource
import de.bixilon.minosoft.terrain.distant.DistantTerrainInterop
import de.bixilon.minosoft.terrain.distant.maximumContiguousDistantRadius
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFaceDirection
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantMeshQuad
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.interop.DistantTerrainProvider
import de.bixilon.minosoft.terrain.runtime.TerrainProcessBuildService
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildOutcome
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildUrgency
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainCancellationToken
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSchedulerTenantId
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

internal class DistantTerrainRenderer(
    override val context: RenderContext,
    private val source: DistantTerrainRenderSource,
    private val config: DistantTerrainRenderConfig,
) : WorldRenderer, DistantTerrainProvider {
    override val generation = NEXT_PROVIDER_GENERATION.getAndUpdate { Math.incrementExact(it) }
    override val descriptor = DistantTerrainInterop.descriptor
    override val layers = LayerSettings()
    private val solidShader = context.system.shader.create(minosoft("distant/terrain")) {
        DistantTerrainShader(it, SceneProgramFamily.DISTANT_TERRAIN)
    }
    private val waterShader = context.system.shader.create(minosoft("distant/terrain")) {
        DistantTerrainShader(it, SceneProgramFamily.DISTANT_WATER)
    }
    private val hierarchical = if (DistantHierarchicalTerrainRuntime.enabled && context.system is OpenGlRenderSystem) {
        DistantHierarchicalTerrainRuntime(
            context,
            source,
            config,
            solidShader,
            waterShader,
            generation,
            DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION,
        )
    } else {
        null
    }
    private var solid: Mesh? = null
    private var water: Mesh? = null
    private var renderedRevision = Long.MIN_VALUE
    private var renderedOrigin = BlockPosition()
    private var renderedCameraChunk = ChunkPosition()
    private var renderedEnabled = true
    private var renderedDistanceChunks = -1
    private var renderedConfiguredDistanceChunks = -1
    private var renderedNativeOwnershipRevision = Long.MIN_VALUE
    private val buildLease = TerrainProcessBuildService.shared.register<AutoCloseable, DistantMeshBuildResult>(
        ownerId = "distant:${context.session.sessionId}",
        tenant = TerrainSchedulerTenantId("distant:${context.session.sessionId}"),
        contextFactory = { AutoCloseable {} },
        disposer = DistantMeshBuildResult::drop,
    )
    private var pendingBuild: TerrainCancellationToken? = null
    private var pendingIdentity: TerrainBuildIdentity? = null
    private var completedBuild: DistantMeshBuildResult? = null
    @Volatile private var closed = false

    override fun registerLayers() {
        layers.registerSemantic(
            layer = OpaqueLayer,
            shader = solidShader,
            renderer = { hierarchical?.drawSolid(shadow = false) ?: solid?.draw() },
            semantic = PipelineSemantic.DISTANT_TERRAIN,
            owner = { OWNER },
            passId = RenderPassId("minosoft:distant-terrain/solid"),
            auxiliaryRenderers = mapOf(
                IrisShaderPackPlanner.SHADOW_VIEW to { hierarchical?.drawSolid(shadow = true) ?: solid?.draw() },
            ),
            skip = { hierarchical?.hasSolid()?.not() ?: (solid == null) },
        )
        layers.registerSemantic(
            layer = TranslucentLayer,
            shader = waterShader,
            renderer = { hierarchical?.drawWater() ?: water?.draw() },
            semantic = PipelineSemantic.DISTANT_WATER,
            owner = { OWNER },
            passId = RenderPassId("minosoft:distant-terrain/water"),
            skip = { hierarchical?.hasWater()?.not() ?: (water == null) },
        )
    }

    override fun postInit(latch: AbstractLatch) {
        solidShader.load()
        waterShader.load()
    }

    override fun prePrepareDraw() {
        val viewProjection = distantViewProjection(
            hostProjection = context.camera.matrix.projectionMatrix,
            view = context.camera.matrix.viewMatrix,
            near = context.camera.matrix.nearPlane,
            renderDistanceChunks = config.renderDistanceChunks,
        )
        solidShader.viewProjectionMatrix = viewProjection
        waterShader.viewProjectionMatrix = viewProjection
        val fogColor = context.camera.fog.state.color
        val distantFogEnabled = context.camera.fog.state.enabled && fogColor != null
        solidShader.distantFogEnabled = distantFogEnabled
        waterShader.distantFogEnabled = distantFogEnabled
        fogColor?.let {
            solidShader.distantFogColor = it
            waterShader.distantFogColor = it
        }
        val origin = context.camera.offset.offset
        val eye = context.camera.view.view.eyePosition
        val cameraChunk = ChunkPosition(
            floor(eye.x / 16.0).toInt(),
            floor(eye.z / 16.0).toInt(),
        )
        val nativeOwnership = context.renderer[ChunkRenderer]?.loaded?.ownershipSnapshot()
            ?: NativeTerrainOwnershipSnapshot(0L, emptySet())
        val seamDistance = (context.session.world.view.viewDistance + 1) * 16.0f
        if (hierarchical != null) {
            hierarchical.prepare(cameraChunk, seamDistance / 16.0f, nativeOwnership)
            val effectiveDistance = config.renderDistanceChunks * 16.0f
            solidShader.farFogStart = max(seamDistance, effectiveDistance * FAR_FOG_START_RATIO)
            waterShader.farFogStart = max(seamDistance, effectiveDistance * FAR_FOG_START_RATIO)
            solidShader.farFogEnd = effectiveDistance
            waterShader.farFogEnd = effectiveDistance
            return
        }
        drainBuildCompletions()
        installCompletedBuild(origin)
        val effectiveDistance = (renderedDistanceChunks.takeIf { it > 0 }
            ?: config.renderDistanceChunks) * 16.0f
        solidShader.farFogStart = max(seamDistance, effectiveDistance * FAR_FOG_START_RATIO)
        waterShader.farFogStart = max(seamDistance, effectiveDistance * FAR_FOG_START_RATIO)
        solidShader.farFogEnd = effectiveDistance
        waterShader.farFogEnd = effectiveDistance
        if (!source.presentationEnabled) {
            clearMeshes()
            renderedEnabled = false
            return
        }
        if (pendingBuild != null) return
        val snapshotRevision = source.revision
        if (
            snapshotRevision == renderedRevision &&
            origin == renderedOrigin &&
            cameraChunk == renderedCameraChunk &&
            renderedEnabled &&
            renderedConfiguredDistanceChunks == config.renderDistanceChunks &&
            renderedNativeOwnershipRevision == nativeOwnership.revision
        ) {
            return
        }
        val snapshot = source.snapshot()
        val request = DistantMeshBuildRequest(
            identity = buildIdentity(snapshot.revision, cameraChunk, nativeOwnership.revision),
            revision = snapshot.revision,
            origin = origin,
            cameraChunk = cameraChunk,
            seamDistance = seamDistance,
            distanceChunks = maximumContiguousDistantCoverageRadius(
                tiles = snapshot.tiles,
                cameraChunk = cameraChunk,
                seamDistanceChunks = seamDistance / 16.0f,
                maximumDistanceChunks = minOf(
                    config.renderDistanceChunks,
                    maximumContiguousDistantRadius(config.maximumTiles),
                ),
                coveredChunks = nativeOwnership.chunks,
            ),
            configuredDistanceChunks = config.renderDistanceChunks,
            nativeOwnershipRevision = nativeOwnership.revision,
            tiles = snapshot.tiles,
            sources = snapshot.sources,
            nativeChunks = nativeOwnership.chunks,
            excludedChunks = emptySet(),
        )
        val cancellation = TerrainCancellationToken()
        pendingIdentity = request.identity
        pendingBuild = buildLease.submit(
            identity = request.identity,
            urgency = TerrainBuildUrgency.DEFERRED,
            cancellation = cancellation,
        ) { _, token -> build(request, token) }
        if (pendingBuild == null) pendingIdentity = null
    }

    override fun postDraw() {
        hierarchical?.finishFrame()
    }

    private fun build(
        request: DistantMeshBuildRequest,
        cancellation: TerrainCancellationToken,
    ): DistantMeshBuildResult {
        val cancelled = { closed || !source.presentationEnabled || cancellation.isCancelled }
        val planned = DistantLodMeshPlanner.plan(
            tiles = request.tiles,
            cameraChunk = request.cameraChunk,
            seamDistance = request.seamDistance,
            maximumDistanceChunks = request.distanceChunks,
            // Conservative Phase 5 rollback: keep distant geometry underneath
            // ready near terrain. Whole-tile exclusion can expose a hole while
            // an asynchronous retirement rebuild is still pending.
            excludedChunks = request.excludedChunks,
            sources = request.sources,
            cancelled = cancelled,
        )
        if (cancelled()) return DistantMeshBuildResult.cancelled(request)
        val solidBuilder = DistantTerrainMeshBuilder(
            context,
            planned.count { !it.material.water } * MAX_FACES_PER_CELL,
        )
        val waterBuilder = DistantTerrainMeshBuilder(
            context,
            planned.count { it.material.water } * MAX_FACES_PER_CELL,
        )
        planned.forEach { quad ->
            if (cancelled()) {
                solidBuilder.drop()
                waterBuilder.drop()
                return DistantMeshBuildResult.cancelled(request)
            }
            val builder = if (quad.material.water) waterBuilder else solidBuilder
            builder.add(quad, request.origin)
        }
        val solid = solidBuilder.takeIf { it.vertices > 0 }?.bake()
        val water = waterBuilder.takeIf { it.vertices > 0 }?.bake()
        if (solidBuilder.vertices == 0) solidBuilder.drop()
        if (waterBuilder.vertices == 0) waterBuilder.drop()
        if (cancelled()) {
            solid?.drop()
            water?.drop()
            return DistantMeshBuildResult.cancelled(request)
        }
        return DistantMeshBuildResult(
            request,
            solid,
            water,
            createDistantLodRenderDiagnostics(
                revision = request.revision,
                nativeOwnershipRevision = request.nativeOwnershipRevision,
                tileCount = request.tiles.size,
                renderReadyNativeChunks = request.nativeChunks.size,
                excludedTiles = request.tiles.count { it.position in request.excludedChunks },
                planned = planned,
            ),
        )
    }

    private fun drainBuildCompletions() {
        buildLease.drain(MAX_COMPLETIONS_PER_FRAME) { completion ->
            if (completion.identity == pendingIdentity) {
                pendingBuild = null
                pendingIdentity = null
            }
            when (val outcome = completion.outcome) {
                is TerrainBuildOutcome.Success -> {
                    completedBuild?.drop()
                    completedBuild = outcome.value
                }
                is TerrainBuildOutcome.Cancelled -> outcome.completedValue?.drop()
                is TerrainBuildOutcome.Failure ->
                    Log.log(LogMessageType.RENDERING, LogLevels.WARN, outcome.error)
            }
        }
    }

    private fun installCompletedBuild(currentOrigin: BlockPosition) {
        val result = completedBuild ?: return
        completedBuild = null
        val currentIdentity = result.request.identity.copy(
            page = result.request.identity.page.copy(worldEpoch = context.session.world.terrainEpoch),
            requestRevision = source.revision,
            capturedModelRevision = source.revision,
            providerGeneration = generation,
            layoutGeneration = DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION,
            materialGeneration = context.shaderPipeline.selection().generation,
            sourceDataRevision = source.revision,
        )
        if (
            result.cancelled ||
            closed ||
            !source.presentationEnabled ||
            result.request.identity.mismatch(currentIdentity) != null ||
            result.request.origin != currentOrigin ||
            result.request.configuredDistanceChunks != config.renderDistanceChunks
        ) {
            result.drop()
            return
        }
        try {
            result.solid?.load()
            result.water?.load()
        } catch (error: Throwable) {
            result.drop()
            Log.log(LogMessageType.RENDERING, LogLevels.WARN, error)
            return
        }
        val previousSolid = solid
        val previousWater = water
        solid = result.solid
        water = result.water
        previousSolid?.unload()
        previousWater?.unload()
        renderedRevision = result.request.revision
        renderedOrigin = result.request.origin
        renderedCameraChunk = result.request.cameraChunk
        renderedEnabled = true
        renderedDistanceChunks = result.request.distanceChunks
        renderedConfiguredDistanceChunks = result.request.configuredDistanceChunks
        renderedNativeOwnershipRevision = result.request.nativeOwnershipRevision
        source.publishDiagnostics(result.diagnostics)
    }

    private fun clearMeshes() {
        var failure: Throwable? = null
        try {
            solid?.discardCandidate()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            water?.discardCandidate()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        solid = null
        water = null
        if (failure != null) throw failure
    }

    override fun unload() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            hierarchical?.close()
        } catch (error: Throwable) {
            failure = error
        }
        pendingBuild?.cancel()
        pendingBuild = null
        pendingIdentity = null
        try {
            completedBuild?.drop()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        completedBuild = null
        for (cleanup in listOf<() -> Unit>(buildLease::close, ::clearMeshes, solidShader::unload, waterShader::unload)) {
            try {
                cleanup()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (failure != null) throw failure
    }

    override fun close() = unload()

    companion object {
        val OWNER = RenderOwnerId("minosoft:distant-terrain")
        private const val MAX_FACES_PER_CELL = 5
        private const val MAX_COMPLETIONS_PER_FRAME = 8
        private const val FAR_FOG_START_RATIO = 0.85f
        private val NEXT_PROVIDER_GENERATION = AtomicLong(1L)
    }

    private fun buildIdentity(
        revision: Long,
        cameraChunk: ChunkPosition,
        nativeOwnershipRevision: Long,
    ): TerrainBuildIdentity {
        return TerrainBuildIdentity(
            page = TerrainPageKey(
                domain = TerrainDomain.DISTANT,
                detailLevel = 0,
                x = cameraChunk.x.toLong(),
                y = 0L,
                z = cameraChunk.z.toLong(),
                worldEpoch = context.session.world.terrainEpoch,
            ),
            requestRevision = revision,
            capturedModelRevision = revision,
            providerGeneration = generation,
            layoutGeneration = DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION,
            materialGeneration = context.shaderPipeline.selection().generation,
            coverageGeneration = maxOf(nativeOwnershipRevision, 0L),
            prioritySequence = revision,
            sourceDataRevision = revision,
        )
    }

    private data class DistantMeshBuildRequest(
        val identity: TerrainBuildIdentity,
        val revision: Long,
        val origin: BlockPosition,
        val cameraChunk: ChunkPosition,
        val seamDistance: Float,
        val distanceChunks: Int,
        val configuredDistanceChunks: Int,
        val nativeOwnershipRevision: Long,
        val tiles: List<DistantLodTile>,
        val sources: Map<ChunkPosition, DistantLodTileSource>,
        val nativeChunks: Set<ChunkPosition>,
        val excludedChunks: Set<ChunkPosition>,
    )

    private data class DistantMeshBuildResult(
        val request: DistantMeshBuildRequest,
        val solid: Mesh?,
        val water: Mesh?,
        val diagnostics: DistantLodRenderDiagnostics,
        val cancelled: Boolean = false,
    ) {
        private val dropped = AtomicBoolean()

        fun drop() {
            if (!dropped.compareAndSet(false, true)) return
            var failure: Throwable? = null
            try {
                solid?.discardCandidate()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                water?.discardCandidate()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            if (failure != null) throw failure
        }

        companion object {
            fun cancelled(request: DistantMeshBuildRequest) =
                DistantMeshBuildResult(
                    request,
                    null,
                    null,
                    DistantLodRenderDiagnostics.empty(
                        revision = request.revision,
                        nativeOwnershipRevision = request.nativeOwnershipRevision,
                        tileCount = request.tiles.size,
                        renderReadyNativeChunks = request.nativeChunks.size,
                        excludedTiles = request.tiles.count { it.position in request.excludedChunks },
                    ),
                    cancelled = true,
                )
        }
    }
}

internal class DistantTerrainRendererBuilder(
    private val config: DistantTerrainRenderConfig,
    private val sourceFactory: (PlaySession) -> DistantTerrainRenderSource,
) : RendererBuilder<DistantTerrainRenderer> {
    override fun build(session: PlaySession, context: RenderContext) =
        DistantTerrainRenderer(context, sourceFactory(session), config)
}

internal class DistantTerrainShader(
    native: NativeShader,
    family: SceneProgramFamily,
) : Shader(native), CameraPositionShader, LightShader, ViewProjectionShader {
    init {
        require(family == SceneProgramFamily.DISTANT_TERRAIN || family == SceneProgramFamily.DISTANT_WATER) {
            "Distant terrain shader requires a distant solid or water route"
        }
    }

    override val sceneContract = SceneShaderContract(
        family,
        SceneVertexAbi.DISTANT_TERRAIN,
        SceneStateAbi.DISTANT_TERRAIN,
    )
    override var cameraPosition: Vec3f by cameraPosition()
    override val lightmap: LightmapBuffer by lightmap()
    override var viewProjectionMatrix: Mat4f by viewProjectionMatrix()
    var pageOffset: Vec3f by uniform("uPageOffset", Vec3f())
    var distantFogEnabled: Boolean by uniform("uDistantFogEnabled", false)
    var distantWater: Boolean by uniform("uDistantWater", family == SceneProgramFamily.DISTANT_WATER)
    var distantFogColor: RGBAColor by uniform("uDistantFogColor", RGBAColor(0, 0, 0))
    var farFogStart: Float by uniform("uDistantFarFogStart", Float.MAX_VALUE)
    var farFogEnd: Float by uniform("uDistantFarFogEnd", Float.MAX_VALUE)
}

internal fun distantViewProjection(
    hostProjection: Mat4f,
    view: Mat4f,
    near: Float,
    renderDistanceChunks: Int,
): Mat4f {
    require(renderDistanceChunks > 0) { "Distant LOD render distance must be positive" }
    val renderDistanceBlocks = Math.multiplyExact(renderDistanceChunks, 16)
    val projection = IrisDistantFrameState.create(
        hostProjection = hostProjection,
        near = near,
        renderDistance = renderDistanceBlocks,
    ).projection
    return projection * view
}

internal data class DistantLodQuad(
    val chunk: ChunkPosition,
    val x: Int,
    val z: Int,
    val size: Int,
    val y: Int,
    val material: DistantLodMaterial,
    val minimumY: Int = y,
    val maximumY: Int = y,
    val surface: DistantLodSurface = if (material.water) {
        DistantLodSurface.WATER
    } else {
        DistantLodSurface.TERRAIN
    },
    val source: DistantLodTileSource = DistantLodTileSource.NATIVE,
    val skirts: List<DistantLodSkirt> = emptyList(),
)

internal enum class DistantLodSurface(val wireName: String) {
    TERRAIN("terrain"),
    WATER("water"),
    WATER_BED("water-bed"),
}

internal enum class DistantLodEdge {
    NORTH,
    SOUTH,
    WEST,
    EAST,
}

internal data class DistantLodSkirt(
    val edge: DistantLodEdge,
    val offset: Int,
    val length: Int,
    val bottomY: Int,
)

internal enum class DistantLodMaterial(
    val dhId: Int,
    val color: RGBAColor,
    val water: Boolean = false,
) {
    UNKNOWN(0, RGBAColor(112, 112, 112)),
    LEAVES(1, RGBAColor(72, 128, 58)),
    STONE(2, RGBAColor(120, 120, 120)),
    WOOD(3, RGBAColor(126, 94, 58)),
    METAL(4, RGBAColor(164, 166, 170)),
    DIRT(5, RGBAColor(120, 86, 54)),
    LAVA(6, RGBAColor(255, 102, 12)),
    DEEPSLATE(7, RGBAColor(70, 70, 76)),
    SNOW(8, RGBAColor(238, 244, 247)),
    SAND(9, RGBAColor(218, 204, 143)),
    TERRACOTTA(10, RGBAColor(156, 88, 63)),
    NETHER_STONE(11, RGBAColor(104, 40, 42)),
    WATER(12, RGBAColor(48, 96, 196, 184), water = true),
    GRASS(13, RGBAColor(92, 146, 62)),
    AIR(14, RGBAColor(0, 0, 0, 0)),
    ILLUMINATED(15, RGBAColor(255, 194, 92));

    companion object {
        fun of(resource: ResourceLocation?): DistantLodMaterial {
            // A retained/network tile may know a valid surface height before
            // its palette entry is available. Keep neutral coverage rather
            // than turning that valid column into a hole.
            val path = resource?.path ?: return UNKNOWN
            return when {
                path == "air" || path.endsWith("_air") -> AIR
                "water" in path -> WATER
                "lava" in path -> LAVA
                "leaves" in path -> LEAVES
                "grass" in path || "moss" in path -> GRASS
                "snow" in path || "ice" in path -> SNOW
                "sand" in path || "gravel" in path -> SAND
                "terracotta" in path -> TERRACOTTA
                "deepslate" in path -> DEEPSLATE
                "netherrack" in path || "nether_" in path -> NETHER_STONE
                "log" in path || "wood" in path || "planks" in path -> WOOD
                "iron" in path || "gold" in path || "copper" in path || "metal" in path -> METAL
                "dirt" in path || "mud" in path || "clay" in path -> DIRT
                "torch" in path || "lamp" in path || "lantern" in path || "glow" in path ||
                    "light" in path -> ILLUMINATED
                "stone" in path || "ore" in path -> STONE
                else -> UNKNOWN
            }
        }
    }
}

internal object DistantLodMeshPlanner {
    const val CELL_SIZE = 4
    const val MEDIUM_CELL_SIZE = 8
    const val FAR_CELL_SIZE = 16

    fun plan(
        tiles: List<DistantLodTile>,
        cameraChunk: ChunkPosition,
        seamDistance: Float,
        maximumDistanceChunks: Int = Int.MAX_VALUE,
        excludedChunks: Set<ChunkPosition> = emptySet(),
        sources: Map<ChunkPosition, DistantLodTileSource> = emptyMap(),
        cancelled: () -> Boolean = { false },
    ): List<DistantLodQuad> {
        require(maximumDistanceChunks > 0) { "Distant LOD render distance must be positive" }
        val seamChunks = seamDistance / 16.0f
        val seamSquared = seamChunks * seamChunks
        val maximumSquared = maximumDistanceChunks.toFloat() * maximumDistanceChunks.toFloat()
        val tilesByPosition = tiles.associateBy(DistantLodTile::position)
        val cells = buildList {
            for (tile in tiles) {
                if (cancelled()) return@buildList
                if (tile.position in excludedChunks) continue
                val dx = tile.position.x + 0.5f - cameraChunk.x
                val dz = tile.position.z + 0.5f - cameraChunk.z
                val distanceSquared = dx * dx + dz * dz
                if (distanceSquared > maximumSquared) continue
                // Keep one stable base lattice across the visible LOD radius.
                // Switching 4 -> 8 -> 16 at circular distance thresholds made
                // the median heights discontinuous and exposed a terrain band
                // on high-variance worlds. Relief/coastlines still refine
                // locally below this base size.
                val size = CELL_SIZE
                val forceFine = size > CELL_SIZE && requiresFineTile(tile, tilesByPosition)
                val minimumSize = if (requiresExactTile(tile, tilesByPosition)) {
                    MIN_CELL_SIZE
                } else {
                    CELL_SIZE
                }
                for (z in 0 until 16 step size) {
                    for (x in 0 until 16 step size) {
                        val centerX = tile.position.x + (x + size * 0.5f) / 16.0f - cameraChunk.x
                        val centerZ = tile.position.z + (z + size * 0.5f) / 16.0f - cameraChunk.z
                        if (centerX * centerX + centerZ * centerZ <= seamSquared) continue
                        emitCell(
                            destination = this,
                            tile = tile,
                            x = x,
                            z = z,
                            size = size,
                            forceFine = forceFine,
                            minimumSize = minimumSize,
                            source = sources[tile.position] ?: DistantLodTileSource.NATIVE,
                        )
                    }
                }
            }
        }
        if (cancelled()) return emptyList()
        val stabilizedCells = stabilizeExtremeRelief(cells, cancelled)
        if (cancelled()) return emptyList()
        val surfaceCoverage = DistantCoverage()
        val waterCoverage = DistantCoverage()
        for (cell in stabilizedCells) {
            if (cancelled()) return emptyList()
            if (cell.surface == DistantLodSurface.WATER_BED) continue
            val worldX = cell.chunk.x * 16 + cell.x
            val worldZ = cell.chunk.z * 16 + cell.z
            surfaceCoverage.put(worldX, worldZ, cell)
            if (cell.surface == DistantLodSurface.WATER) {
                waterCoverage.put(worldX, worldZ, cell)
            }
        }
        if (cancelled()) return emptyList()
        return stabilizedCells.map { cell ->
            if (cell.surface == DistantLodSurface.WATER_BED) return@map cell
            val worldX = cell.chunk.x * 16 + cell.x
            val worldZ = cell.chunk.z * 16 + cell.z
            val coverage = if (cell.surface == DistantLodSurface.WATER) waterCoverage else surfaceCoverage
            val skirts = ArrayList<DistantLodSkirt>(cell.size * 4)
            fun addSkirt(edge: DistantLodEdge, offset: Int, x: Int, z: Int) {
                val neighbor = coverage[x, z] ?: return
                if (neighbor.y >= cell.y) return
                val bottomY = max(neighbor.y, cell.y - MAX_SKIRT_DROP)
                val previous = skirts.lastOrNull()
                if (
                    previous != null &&
                    previous.edge == edge &&
                    previous.bottomY == bottomY &&
                    previous.offset + previous.length == offset
                ) {
                    skirts[skirts.lastIndex] = previous.copy(length = previous.length + MIN_CELL_SIZE)
                } else {
                    skirts += DistantLodSkirt(edge, offset, MIN_CELL_SIZE, bottomY)
                }
            }
            for (edge in DistantLodEdge.entries) {
                for (offset in 0 until cell.size step MIN_CELL_SIZE) {
                    when (edge) {
                        DistantLodEdge.NORTH ->
                            addSkirt(edge, offset, worldX + offset, worldZ - MIN_CELL_SIZE)
                        DistantLodEdge.SOUTH ->
                            addSkirt(edge, offset, worldX + offset, worldZ + cell.size)
                        DistantLodEdge.WEST ->
                            addSkirt(edge, offset, worldX - MIN_CELL_SIZE, worldZ + offset)
                        DistantLodEdge.EAST ->
                            addSkirt(edge, offset, worldX + cell.size, worldZ + offset)
                    }
                }
            }
            cell.copy(skirts = skirts)
        }
    }

    /**
     * A detached surface tile does not retain the vertical bands DH's native
     * database uses to describe extreme cliffs. Leaving a larger discontinuity
     * open makes the upper surface float; closing it as one curtain invents a
     * full-height wall. Limit the rendered surface graph to the same bounded
     * step that skirts can close, while retaining the sampled min/max heights
     * on each cell for diagnostics.
     */
    private fun stabilizeExtremeRelief(
        cells: List<DistantLodQuad>,
        cancelled: () -> Boolean,
    ): List<DistantLodQuad> {
        val coverage = DistantCoverage()
        val indices = IdentityHashMap<DistantLodQuad, Int>()
        val heights = IntArray(cells.size)
        val queue = PriorityQueue(compareBy<DistantReliefEntry> { it.y }.thenBy { it.index })
        cells.forEachIndexed { index, cell ->
            heights[index] = cell.y
            if (cell.surface == DistantLodSurface.WATER_BED) return@forEachIndexed
            indices[cell] = index
            coverage.put(cell.chunk.x * 16 + cell.x, cell.chunk.z * 16 + cell.z, cell)
            queue += DistantReliefEntry(index, cell.y)
        }
        fun lowerNeighbor(sourceY: Int, worldX: Int, worldZ: Int) {
            val neighbor = coverage[worldX, worldZ] ?: return
            val index = indices[neighbor] ?: return
            if (cells[index].surface != DistantLodSurface.TERRAIN) return
            val maximumY = sourceY + MAX_RENDERED_NEIGHBOR_STEP
            if (heights[index] <= maximumY) return
            heights[index] = maximumY
            queue += DistantReliefEntry(index, maximumY)
        }
        while (queue.isNotEmpty()) {
            if (cancelled()) return emptyList()
            val entry = queue.remove()
            if (entry.y != heights[entry.index]) continue
            val cell = cells[entry.index]
            val worldX = cell.chunk.x * 16 + cell.x
            val worldZ = cell.chunk.z * 16 + cell.z
            for (offset in 0 until cell.size step MIN_CELL_SIZE) {
                lowerNeighbor(entry.y, worldX + offset, worldZ - MIN_CELL_SIZE)
                lowerNeighbor(entry.y, worldX + offset, worldZ + cell.size)
                lowerNeighbor(entry.y, worldX - MIN_CELL_SIZE, worldZ + offset)
                lowerNeighbor(entry.y, worldX + cell.size, worldZ + offset)
            }
        }
        return cells.mapIndexed { index, cell ->
            if (cell.surface == DistantLodSurface.TERRAIN && heights[index] != cell.y) {
                cell.copy(y = heights[index])
            } else {
                cell
            }
        }
    }

    private fun emitCell(
        destination: MutableList<DistantLodQuad>,
        tile: DistantLodTile,
        x: Int,
        z: Int,
        size: Int,
        forceFine: Boolean,
        minimumSize: Int,
        source: DistantLodTileSource,
    ) {
        val samples = resolvedSamples(tile, x, z, size)
        if (samples.isEmpty()) return
        val subdivide = when {
            size > minimumSize && minimumSize < CELL_SIZE -> true
            size > CELL_SIZE -> forceFine || requiresSubdivision(samples, size)
            size > MIN_CELL_SIZE -> requiresColumnSubdivision(samples)
            else -> false
        }
        if (subdivide) {
            val half = size / 2
            emitCell(destination, tile, x, z, half, forceFine, minimumSize, source)
            emitCell(destination, tile, x + half, z, half, forceFine, minimumSize, source)
            emitCell(destination, tile, x, z + half, half, forceFine, minimumSize, source)
            emitCell(destination, tile, x + half, z + half, half, forceFine, minimumSize, source)
            return
        }
        val sorted = samples.sortedWith(COLUMN_ORDER)
        val column = sorted[sorted.size / 2]
        val material = DistantLodMaterial.of(column.material)
        if (material == DistantLodMaterial.AIR) return
        val minimumY = samples.minOf(DistantLodColumn::surfaceY)
        val maximumY = samples.maxOf(DistantLodColumn::surfaceY)
        val surface = if (material.water) DistantLodSurface.WATER else DistantLodSurface.TERRAIN
        destination += DistantLodQuad(
            tile.position,
            x,
            z,
            size,
            column.surfaceY,
            material,
            minimumY,
            maximumY,
            surface,
            source,
        )
        if (!material.water) return
        val beds = samples.filter {
            DistantLodMaterial.of(it.material).water &&
                it.solidY != Int.MIN_VALUE &&
                DistantLodMaterial.of(it.solidMaterial).let { bed -> bed != DistantLodMaterial.AIR && !bed.water }
        }.sortedWith(compareBy<DistantLodColumn> { it.solidY }.thenBy { it.solidMaterial?.toString().orEmpty() })
        if (beds.isEmpty()) return
        val bed = beds[beds.size / 2]
        destination += DistantLodQuad(
            tile.position,
            x,
            z,
            size,
            bed.solidY,
            DistantLodMaterial.of(bed.solidMaterial),
            beds.minOf(DistantLodColumn::solidY),
            beds.maxOf(DistantLodColumn::solidY),
            DistantLodSurface.WATER_BED,
            source,
        )
    }

    private fun samples(tile: DistantLodTile, x: Int, z: Int, size: Int): List<DistantLodColumn> =
        buildList(size * size) {
            for (sampleZ in z until z + size) {
                for (sampleX in x until x + size) {
                    val candidate = tile[sampleX, sampleZ]
                    if (
                        candidate.surfaceY != Int.MIN_VALUE &&
                        DistantLodMaterial.of(candidate.material) != DistantLodMaterial.AIR
                    ) {
                        add(candidate)
                    }
                }
            }
        }

    private fun resolvedSamples(
        tile: DistantLodTile,
        x: Int,
        z: Int,
        size: Int,
    ): List<DistantLodColumn> {
        val direct = samples(tile, x, z, size)
        if (direct.isNotEmpty()) return direct
        for (radius in 1 until 16) {
            val minimumX = (x - radius).coerceAtLeast(0)
            val maximumX = (x + size - 1 + radius).coerceAtMost(15)
            val minimumZ = (z - radius).coerceAtLeast(0)
            val maximumZ = (z + size - 1 + radius).coerceAtMost(15)
            val nearest = buildList {
                for (sampleZ in minimumZ..maximumZ) {
                    for (sampleX in minimumX..maximumX) {
                        if (
                            sampleX != minimumX &&
                            sampleX != maximumX &&
                            sampleZ != minimumZ &&
                            sampleZ != maximumZ
                        ) {
                            continue
                        }
                        val candidate = tile[sampleX, sampleZ]
                        if (
                            candidate.surfaceY != Int.MIN_VALUE &&
                            DistantLodMaterial.of(candidate.material) != DistantLodMaterial.AIR
                        ) {
                            add(candidate)
                        }
                    }
                }
            }
            if (nearest.isNotEmpty()) return nearest
            if (minimumX == 0 && maximumX == 15 && minimumZ == 0 && maximumZ == 15) break
        }
        return emptyList()
    }

    private fun requiresSubdivision(samples: List<DistantLodColumn>, size: Int): Boolean {
        val minimumY = samples.minOf(DistantLodColumn::surfaceY)
        val maximumY = samples.maxOf(DistantLodColumn::surfaceY)
        if (maximumY.toLong() - minimumY > if (size >= FAR_CELL_SIZE) 8L else 6L) return true
        val hasWater = samples.any { DistantLodMaterial.of(it.material).water }
        val hasLand = samples.any {
            DistantLodMaterial.of(it.material).let { material ->
                material != DistantLodMaterial.AIR && !material.water
            }
        }
        return hasWater && hasLand
    }

    private fun requiresColumnSubdivision(samples: List<DistantLodColumn>): Boolean {
        val minimumY = samples.minOf(DistantLodColumn::surfaceY)
        val maximumY = samples.maxOf(DistantLodColumn::surfaceY)
        if (maximumY.toLong() - minimumY > MAX_COLUMN_CELL_HEIGHT_RANGE) {
            val ordered = samples.map(DistantLodColumn::surfaceY).sorted()
            val median = ordered[ordered.size / 2]
            val outlierColumns = ordered.count {
                kotlin.math.abs(it.toLong() - median) > MAX_COLUMN_CELL_HEIGHT_RANGE
            }
            if (outlierColumns > 1) return true
        }
        val hasWater = samples.any { DistantLodMaterial.of(it.material).water }
        val hasLand = samples.any {
            DistantLodMaterial.of(it.material).let { material ->
                material != DistantLodMaterial.AIR && !material.water
            }
        }
        return hasWater && hasLand
    }

    private fun requiresFineTile(
        tile: DistantLodTile,
        tiles: Map<ChunkPosition, DistantLodTile>,
    ): Boolean {
        val all = samples(tile, 0, 0, 16)
        if (all.isEmpty()) return false
        if (requiresSubdivision(all, FAR_CELL_SIZE)) return true
        fun boundaryDiffers(x: Int, z: Int, neighborPosition: ChunkPosition, neighborX: Int, neighborZ: Int): Boolean {
            val neighbor = tiles[neighborPosition]?.get(neighborX, neighborZ) ?: return false
            val current = tile[x, z]
            if (current.surfaceY == Int.MIN_VALUE || neighbor.surfaceY == Int.MIN_VALUE) return false
            if (
                kotlin.math.abs(current.surfaceY.toLong() - neighbor.surfaceY) >
                MAX_COARSE_NEIGHBOR_DELTA
            ) {
                return true
            }
            return DistantLodMaterial.of(current.material).water != DistantLodMaterial.of(neighbor.material).water
        }
        for (offset in 0 until 16) {
            if (boundaryDiffers(0, offset, ChunkPosition(tile.position.x - 1, tile.position.z), 15, offset)) return true
            if (boundaryDiffers(15, offset, ChunkPosition(tile.position.x + 1, tile.position.z), 0, offset)) return true
            if (boundaryDiffers(offset, 0, ChunkPosition(tile.position.x, tile.position.z - 1), offset, 15)) return true
            if (boundaryDiffers(offset, 15, ChunkPosition(tile.position.x, tile.position.z + 1), offset, 0)) return true
        }
        return false
    }

    private fun requiresExactTile(
        tile: DistantLodTile,
        tiles: Map<ChunkPosition, DistantLodTile>,
    ): Boolean {
        val all = samples(tile, 0, 0, 16)
        if (all.isEmpty()) return false
        if (
            all.maxOf(DistantLodColumn::surfaceY).toLong() - all.minOf(DistantLodColumn::surfaceY) >
            MAX_EXACT_TILE_HEIGHT_RANGE
        ) {
            return true
        }
        fun boundaryIsExtreme(
            x: Int,
            z: Int,
            neighborPosition: ChunkPosition,
            neighborX: Int,
            neighborZ: Int,
        ): Boolean {
            val neighbor = tiles[neighborPosition]?.get(neighborX, neighborZ) ?: return false
            val current = tile[x, z]
            if (current.surfaceY == Int.MIN_VALUE || neighbor.surfaceY == Int.MIN_VALUE) return false
            return kotlin.math.abs(current.surfaceY.toLong() - neighbor.surfaceY) >
                MAX_EXACT_TILE_HEIGHT_RANGE
        }
        for (offset in 0 until 16) {
            if (boundaryIsExtreme(0, offset, ChunkPosition(tile.position.x - 1, tile.position.z), 15, offset)) return true
            if (boundaryIsExtreme(15, offset, ChunkPosition(tile.position.x + 1, tile.position.z), 0, offset)) return true
            if (boundaryIsExtreme(offset, 0, ChunkPosition(tile.position.x, tile.position.z - 1), offset, 15)) return true
            if (boundaryIsExtreme(offset, 15, ChunkPosition(tile.position.x, tile.position.z + 1), offset, 0)) return true
        }
        return false
    }

    private val COLUMN_ORDER = compareBy<DistantLodColumn> { it.surfaceY }
        .thenBy { it.material?.toString().orEmpty() }
    private data class DistantReliefEntry(val index: Int, val y: Int)
    private const val MIN_CELL_SIZE = 1
    private const val MAX_COLUMN_CELL_HEIGHT_RANGE = 16
    private const val MAX_EXACT_TILE_HEIGHT_RANGE = 128
    private const val MAX_RENDERED_NEIGHBOR_STEP = 4
    private const val MAX_SKIRT_DROP = 32
    private const val MAX_COARSE_NEIGHBOR_DELTA = 8
}

/**
 * Returns the largest complete circular LOD radius around the camera.
 *
 * Generation and network transfer enumerate bounded square rings, but a
 * retained database may still contain an older sparse outer annulus while
 * those rings converge. Publishing through the first missing tile lets
 * disconnected high terrain appear as bridges or floating peaks. Keep the
 * complete inner circle visible and grow it monotonically as coverage arrives.
 */
internal fun maximumContiguousDistantCoverageRadius(
    tiles: List<DistantLodTile>,
    cameraChunk: ChunkPosition,
    seamDistanceChunks: Float,
    maximumDistanceChunks: Int,
    coveredChunks: Set<ChunkPosition> = emptySet(),
): Int {
    require(seamDistanceChunks >= 0.0f) { "Distant LOD seam distance must not be negative" }
    require(maximumDistanceChunks > 0) { "Distant LOD render distance must be positive" }
    val positions = tiles.asSequence().map(DistantLodTile::position).toHashSet()
    val seamSquared = seamDistanceChunks * seamDistanceChunks
    val maximumSquared = maximumDistanceChunks.toFloat() * maximumDistanceChunks
    var nearestMissingSquared = Float.POSITIVE_INFINITY
    for (z in cameraChunk.z - maximumDistanceChunks..cameraChunk.z + maximumDistanceChunks) {
        for (x in cameraChunk.x - maximumDistanceChunks..cameraChunk.x + maximumDistanceChunks) {
            val dx = x + 0.5f - cameraChunk.x
            val dz = z + 0.5f - cameraChunk.z
            val distanceSquared = dx * dx + dz * dz
            if (distanceSquared <= seamSquared || distanceSquared > maximumSquared) continue
            val position = ChunkPosition(x, z)
            if (position in positions || position in coveredChunks) continue
            nearestMissingSquared = minOf(nearestMissingSquared, distanceSquared)
        }
    }
    if (!nearestMissingSquared.isFinite()) return maximumDistanceChunks
    val completeRadius = ceil(sqrt(nearestMissingSquared.toDouble())).toInt() - 1
    return completeRadius.coerceIn(1, maximumDistanceChunks)
}

private class DistantCoverage {
    private val coarse = HashMap<DistantCellKey, DistantLodQuad>()
    private val fine = HashMap<DistantCellKey, DistantLodQuad>()

    fun put(worldX: Int, worldZ: Int, cell: DistantLodQuad) {
        if (cell.size < DistantLodMeshPlanner.CELL_SIZE) {
            for (z in worldZ until worldZ + cell.size) {
                for (x in worldX until worldX + cell.size) {
                    fine[DistantCellKey(x, z)] = cell
                }
            }
            return
        }
        for (z in worldZ until worldZ + cell.size step DistantLodMeshPlanner.CELL_SIZE) {
            for (x in worldX until worldX + cell.size step DistantLodMeshPlanner.CELL_SIZE) {
                coarse[DistantCellKey(x, z)] = cell
            }
        }
    }

    operator fun get(worldX: Int, worldZ: Int): DistantLodQuad? {
        fine[DistantCellKey(worldX, worldZ)]?.let { return it }
        return coarse[
            DistantCellKey(
                Math.floorDiv(worldX, DistantLodMeshPlanner.CELL_SIZE) * DistantLodMeshPlanner.CELL_SIZE,
                Math.floorDiv(worldZ, DistantLodMeshPlanner.CELL_SIZE) * DistantLodMeshPlanner.CELL_SIZE,
            ),
        ]
    }
}

private class DistantCellKey(
    val x: Int,
    val z: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is DistantCellKey &&
            x == other.x &&
            z == other.z

    override fun hashCode(): Int {
        var hash = x * -0x61c88647
        hash = Integer.rotateLeft(hash, 16) xor (z * -0x7a143595)
        hash = hash xor (hash ushr 16)
        return hash
    }
}

internal fun createDistantLodRenderDiagnostics(
    revision: Long,
    nativeOwnershipRevision: Long,
    tileCount: Int,
    renderReadyNativeChunks: Int,
    excludedTiles: Int,
    planned: List<DistantLodQuad>,
): DistantLodRenderDiagnostics {
    val diagnostics = planned.map { cell ->
        DistantLodRenderCellDiagnostic(
            chunk = cell.chunk,
            x = cell.x,
            z = cell.z,
            size = cell.size,
            y = cell.y,
            minimumY = cell.minimumY,
            maximumY = cell.maximumY,
            material = cell.material.name.lowercase(),
            surface = cell.surface.wireName,
            source = cell.source,
            skirtSegments = cell.skirts.size,
            maximumSkirtDrop = cell.skirts.maxOfOrNull { cell.y - it.bottomY } ?: 0,
        )
    }
    return DistantLodRenderDiagnostics(
        revision = revision,
        nativeOwnershipRevision = nativeOwnershipRevision,
        tileCount = tileCount,
        renderReadyNativeChunks = renderReadyNativeChunks,
        excludedTiles = excludedTiles,
        cellCount = planned.size,
        terrainCells = planned.count { it.surface == DistantLodSurface.TERRAIN },
        waterCells = planned.count { it.surface == DistantLodSurface.WATER },
        waterBedCells = planned.count { it.surface == DistantLodSurface.WATER_BED },
        adaptiveCells = planned.count {
            it.size < DistantLodMeshPlanner.CELL_SIZE ||
                (it.size == DistantLodMeshPlanner.CELL_SIZE && it.maximumY != it.minimumY)
        },
        skirtSegments = diagnostics.sumOf(DistantLodRenderCellDiagnostic::skirtSegments),
        maximumSkirtDrop = diagnostics.maxOfOrNull(DistantLodRenderCellDiagnostic::maximumSkirtDrop) ?: 0,
        cellSizes = planned.groupingBy(DistantLodQuad::size).eachCount(),
        sources = planned.groupingBy(DistantLodQuad::source).eachCount(),
        cells = diagnostics.sortedWith(
            compareByDescending<DistantLodRenderCellDiagnostic> {
                max(it.maximumY - it.minimumY, it.maximumSkirtDrop)
            }.thenBy { it.chunk.x }.thenBy { it.chunk.z }.thenBy { it.x }.thenBy { it.z },
        ).take(MAX_DIAGNOSTIC_CELLS),
    )
}

private const val MAX_DIAGNOSTIC_CELLS = 256

private fun Mesh.discardCandidate() {
    when (state) {
        MeshStates.PREPARING -> drop()
        MeshStates.LOADED -> unload()
        MeshStates.UNLOADED -> Unit
    }
}

internal class DistantTerrainMeshBuilder(
    context: RenderContext,
    estimate: Int,
) : QuadMeshBuilder(context, DistantTerrainMeshStruct, estimate.coerceAtLeast(1)) {
    var vertices = 0
        private set

    fun add(quad: DistantLodQuad, origin: BlockPosition) {
        val minX = quad.chunk.x * 16 + quad.x - origin.x
        val minZ = quad.chunk.z * 16 + quad.z - origin.z
        val maxX = minX + quad.size
        val maxZ = minZ + quad.size
        val y = quad.y - origin.y + 1.0f
        val normalMaterial = (quad.material.dhId shl 3) or
            (if (quad.surface == DistantLodSurface.WATER_BED) WATER_BED_FLAG else 0) or
            UP_NORMAL
        val light = (SKY_LIGHT shl 4) or if (quad.material == DistantLodMaterial.ILLUMINATED) 15 else 0
        addVertex(minX.toFloat(), y, maxZ.toFloat(), quad.material.color, light, normalMaterial)
        addVertex(maxX.toFloat(), y, maxZ.toFloat(), quad.material.color, light, normalMaterial)
        addVertex(maxX.toFloat(), y, minZ.toFloat(), quad.material.color, light, normalMaterial)
        addVertex(minX.toFloat(), y, minZ.toFloat(), quad.material.color, light, normalMaterial)
        addIndexQuad(front = false, reverse = true)
        for (skirt in quad.skirts) {
            val start = skirt.offset.toFloat()
            val end = (skirt.offset + skirt.length).toFloat()
            val bottom = skirt.bottomY - origin.y + 1.0f
            val sideMaterial = when (quad.material) {
                DistantLodMaterial.GRASS,
                DistantLodMaterial.LEAVES,
                DistantLodMaterial.SNOW,
                -> DistantLodMaterial.STONE
                else -> quad.material
            }
            when (skirt.edge) {
                DistantLodEdge.NORTH -> addSide(
                    minX + start, minZ.toFloat(),
                    minX + end, minZ.toFloat(),
                    y, bottom, sideMaterial, light, NORTH_NORMAL,
                )
                DistantLodEdge.SOUTH -> addSide(
                    minX + end, maxZ.toFloat(),
                    minX + start, maxZ.toFloat(),
                    y, bottom, sideMaterial, light, SOUTH_NORMAL,
                )
                DistantLodEdge.WEST -> addSide(
                    minX.toFloat(), minZ + end,
                    minX.toFloat(), minZ + start,
                    y, bottom, sideMaterial, light, WEST_NORMAL,
                )
                DistantLodEdge.EAST -> addSide(
                    maxX.toFloat(), minZ + start,
                    maxX.toFloat(), minZ + end,
                    y, bottom, sideMaterial, light, EAST_NORMAL,
                )
            }
        }
    }

    fun add(quad: DistantMeshQuad) {
        val material = DistantLodMaterial.of(ResourceLocation.of(quad.material.value))
        val color = quad.tint?.resolvedRgb?.let { rgb ->
            RGBAColor(
                red = rgb ushr 16 and 0xFF,
                green = rgb ushr 8 and 0xFF,
                blue = rgb and 0xFF,
                alpha = material.color.alpha,
            )
        } ?: material.color
        val light = (quad.skyLight shl 4) or quad.blockLight
        val normal = when (quad.direction) {
            DistantFaceDirection.UP -> UP_NORMAL
            DistantFaceDirection.DOWN -> DOWN_NORMAL
            DistantFaceDirection.NORTH -> NORTH_NORMAL
            DistantFaceDirection.SOUTH -> SOUTH_NORMAL
            DistantFaceDirection.WEST -> WEST_NORMAL
            DistantFaceDirection.EAST -> EAST_NORMAL
        }
        val normalMaterial = (material.dhId shl 3) or normal
        fun vertex(u: Int, v: Int) {
            when (quad.direction) {
                DistantFaceDirection.UP,
                DistantFaceDirection.DOWN,
                -> addVertex(u.toFloat(), quad.plane.toFloat(), v.toFloat(), color, light, normalMaterial)

                DistantFaceDirection.NORTH,
                DistantFaceDirection.SOUTH,
                -> addVertex(u.toFloat(), v.toFloat(), quad.plane.toFloat(), color, light, normalMaterial)

                DistantFaceDirection.WEST,
                DistantFaceDirection.EAST,
                -> addVertex(quad.plane.toFloat(), v.toFloat(), u.toFloat(), color, light, normalMaterial)
            }
        }
        when (quad.direction) {
            DistantFaceDirection.UP -> {
                vertex(quad.minimumU, quad.maximumVExclusive)
                vertex(quad.maximumUExclusive, quad.maximumVExclusive)
                vertex(quad.maximumUExclusive, quad.minimumV)
                vertex(quad.minimumU, quad.minimumV)
            }

            DistantFaceDirection.DOWN -> {
                vertex(quad.minimumU, quad.minimumV)
                vertex(quad.maximumUExclusive, quad.minimumV)
                vertex(quad.maximumUExclusive, quad.maximumVExclusive)
                vertex(quad.minimumU, quad.maximumVExclusive)
            }

            DistantFaceDirection.NORTH,
            DistantFaceDirection.EAST,
            -> {
                vertex(quad.minimumU, quad.maximumVExclusive)
                vertex(quad.maximumUExclusive, quad.maximumVExclusive)
                vertex(quad.maximumUExclusive, quad.minimumV)
                vertex(quad.minimumU, quad.minimumV)
            }

            DistantFaceDirection.SOUTH,
            DistantFaceDirection.WEST,
            -> {
                vertex(quad.maximumUExclusive, quad.maximumVExclusive)
                vertex(quad.minimumU, quad.maximumVExclusive)
                vertex(quad.minimumU, quad.minimumV)
                vertex(quad.maximumUExclusive, quad.minimumV)
            }
        }
        addIndexQuad(front = false, reverse = true)
    }

    private fun addSide(
        firstX: Float,
        firstZ: Float,
        secondX: Float,
        secondZ: Float,
        top: Float,
        bottom: Float,
        material: DistantLodMaterial,
        light: Int,
        normal: Int,
    ) {
        if (bottom >= top) return
        val normalMaterial = (material.dhId shl 3) or normal
        addVertex(firstX, top, firstZ, material.color, light, normalMaterial)
        addVertex(secondX, top, secondZ, material.color, light, normalMaterial)
        addVertex(secondX, bottom, secondZ, material.color, light, normalMaterial)
        addVertex(firstX, bottom, firstZ, material.color, light, normalMaterial)
        addIndexQuad(front = false, reverse = true)
    }

    private fun addVertex(
        x: Float,
        y: Float,
        z: Float,
        color: RGBAColor,
        light: Int,
        normalMaterial: Int,
    ) {
        data.add(x, y, z, color.rgba.buffer(), light.buffer(), normalMaterial.buffer())
        vertices++
    }

    private companion object {
        const val UP_NORMAL = 1
        const val DOWN_NORMAL = 6
        const val WATER_BED_FLAG = 1 shl 11
        const val NORTH_NORMAL = 2
        const val SOUTH_NORMAL = 3
        const val WEST_NORMAL = 4
        const val EAST_NORMAL = 5
        const val SKY_LIGHT = 15
    }
}

internal data class DistantTerrainMeshStruct(
    val position: Vec3f,
    val color: Int,
    val light: Int,
    val normalMaterial: Int,
) {
    companion object : MeshStruct(DistantTerrainMeshStruct::class)
}
