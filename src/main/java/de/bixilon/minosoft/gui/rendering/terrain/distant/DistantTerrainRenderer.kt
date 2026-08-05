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
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.NativeTerrainOwnershipSnapshot
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.light.LightmapBuffer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldPassRegistry
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDistantFrameState
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.types.CameraPositionShader
import de.bixilon.minosoft.gui.rendering.shader.types.LightShader
import de.bixilon.minosoft.gui.rendering.shader.types.ViewProjectionShader
import de.bixilon.minosoft.gui.rendering.system.base.layer.OpaqueLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.TranslucentLayer
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantHierarchyRenderDiagnostics
import de.bixilon.minosoft.terrain.distant.DistantTerrainInterop
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderConfig
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderSource
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.interop.DistantTerrainProvider
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageQuery
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal class DistantTerrainRenderer(
    override val context: RenderContext,
    source: DistantTerrainRenderSource,
    private val config: DistantTerrainRenderConfig,
) : WorldRenderer, DistantTerrainProvider {
    override val generation = NEXT_PROVIDER_GENERATION.getAndUpdate { Math.incrementExact(it) }
    override val descriptor = DistantTerrainInterop.descriptor
    override val passes = WorldPassRegistry()
    private val solidShader = context.system.shader.create(minosoft("distant/terrain")) {
        DistantTerrainShader(it, SceneProgramFamily.DISTANT_TERRAIN)
    }
    private val waterShader = context.system.shader.create(minosoft("distant/terrain")) {
        DistantTerrainShader(it, SceneProgramFamily.DISTANT_WATER)
    }
    private val hierarchy = (context.system as? OpenGlRenderSystem)?.let {
        DistantHierarchicalTerrainRuntime(
            context,
            source,
            config,
            solidShader,
            waterShader,
            generation,
            DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION,
        )
    }
    @Volatile private var closed = false
    @Volatile private var effectiveRenderDistanceBlocks = Math.multiplyExact(config.renderDistanceChunks, 16)
    @Volatile private var frontierDrawable = false

    internal val renderDistanceBlocks: Int
        get() = effectiveRenderDistanceBlocks

    override fun registerPasses() {
        passes.addViews(
            layer = OpaqueLayer,
            shader = solidShader,
            renderer = { view -> hierarchy?.drawSolid(shadow = view == IrisShaderPackPlanner.SHADOW_VIEW) },
            semantic = PipelineSemantic.DISTANT_TERRAIN,
            owner = { OWNER },
            passId = RenderPassId("minosoft:distant-terrain/solid"),
            views = setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW),
            enabled = { view ->
                if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                    frontierDrawable && hierarchy?.hasShadow() == true
                } else {
                    frontierDrawable && hierarchy?.hasSolid() == true
                }
            },
        )
        passes.add(
            layer = TranslucentLayer,
            shader = waterShader,
            renderer = { hierarchy?.drawWater() },
            semantic = PipelineSemantic.DISTANT_WATER,
            owner = { OWNER },
            passId = RenderPassId("minosoft:distant-terrain/water"),
            skip = { !frontierDrawable || (hierarchy?.hasWater()?.not() ?: true) },
        )
    }

    override fun postInit(latch: AbstractLatch) {
        solidShader.load()
        waterShader.load()
    }

    internal fun hierarchyDiagnostics(): DistantHierarchyRenderDiagnostics? = hierarchy?.diagnostics()

    internal fun collectSubmissionCompletions() = hierarchy?.collectSubmissionCompletions()

    internal fun hierarchyPageDiagnostics(
        query: TerrainPageQuery,
        afterPage: TerrainPageKey?,
    ): List<TerrainPageDiagnosticSnapshot> = hierarchy?.diagnosticPages(query, afterPage).orEmpty()

    override fun prePrepareDraw() {
        val eye = context.camera.view.view.eyePosition
        val cameraChunk = ChunkPosition(
            floor(eye.x / 16.0).toInt(),
            floor(eye.z / 16.0).toInt(),
        )
        val nativeOwnership = context.renderer[ChunkRenderer]?.loaded?.ownershipSnapshot()
            ?: NativeTerrainOwnershipSnapshot(0L, emptySet())
        val seamDistanceChunks = context.session.world.view.viewDistance + 1.0f
        hierarchy?.prepare(cameraChunk, seamDistanceChunks, nativeOwnership)
        val effectiveDistanceChunks = hierarchy?.effectiveRenderDistanceChunks()
            ?: config.renderDistanceChunks
        effectiveRenderDistanceBlocks = Math.multiplyExact(effectiveDistanceChunks, 16)
        val viewProjection = distantViewProjection(
            hostProjection = context.camera.matrix.projectionMatrix,
            view = context.camera.matrix.viewMatrix,
            near = context.camera.matrix.nearPlane,
            renderDistanceChunks = effectiveDistanceChunks,
        )
        solidShader.viewProjectionMatrix = viewProjection
        waterShader.viewProjectionMatrix = viewProjection
        val fogColor = context.camera.fog.state.color
        val distantFogEnabled = context.camera.fog.state.enabled && fogColor != null
        solidShader.distantFogEnabled = distantFogEnabled
        waterShader.distantFogEnabled = distantFogEnabled
        val seamDistance = seamDistanceChunks * 16.0f
        val effectiveDistance = effectiveRenderDistanceBlocks.toFloat()
        frontierDrawable = distantFrontierDrawable(seamDistance, effectiveDistance)
        val fogRange = distantFogRange(
            environmentOverride = context.camera.fog.overridesSkyColor,
            cameraFogStart = context.camera.fog.state.start,
            cameraFogEnd = context.camera.fog.state.end,
            seamDistance = seamDistance,
            effectiveDistance = effectiveDistance,
        )
        solidShader.environmentFog = fogRange.environment
        waterShader.environmentFog = fogRange.environment
        fogColor?.let {
            solidShader.distantFogColor = it
            waterShader.distantFogColor = it
        }
        val waterFadeSpan = min(WATER_NEAR_FADE_BLOCKS, max(1.0f, effectiveDistance - seamDistance))
        solidShader.nearFadeStart = seamDistance
        waterShader.nearFadeStart = seamDistance
        solidShader.nearFadeEnd = seamDistance + waterFadeSpan
        waterShader.nearFadeEnd = seamDistance + waterFadeSpan
        solidShader.farFogStart = fogRange.start
        waterShader.farFogStart = fogRange.start
        solidShader.farFogEnd = fogRange.end
        waterShader.farFogEnd = fogRange.end
    }

    override fun postDraw() {
        hierarchy?.finishFrame()
    }

    override fun unload() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        cleanup { hierarchy?.close() }
        cleanup(solidShader::unload)
        cleanup(waterShader::unload)
        failure?.let { throw it }
    }

    override fun close() = unload()

    companion object {
        val OWNER = RenderOwnerId("minosoft:distant-terrain")
        private const val WATER_NEAR_FADE_BLOCKS = 32.0f
        private val NEXT_PROVIDER_GENERATION = AtomicLong(1L)
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
    var environmentFog: Boolean by uniform("uDistantEnvironmentFog", false)
    var distantWater: Boolean by uniform("uDistantWater", family == SceneProgramFamily.DISTANT_WATER)
    var distantFogColor: RGBAColor by uniform("uDistantFogColor", RGBAColor(0, 0, 0))
    var nearFadeStart: Float by uniform("uDistantNearFadeStart", 0.0f)
    var nearFadeEnd: Float by uniform("uDistantNearFadeEnd", 1.0f)
    var farFogStart: Float by uniform("uDistantFarFogStart", Float.MAX_VALUE)
    var farFogEnd: Float by uniform("uDistantFarFogEnd", Float.MAX_VALUE)
}

internal data class DistantFogRange(
    val start: Float,
    val end: Float,
    val environment: Boolean,
)

internal fun distantFrontierDrawable(seamDistance: Float, effectiveDistance: Float): Boolean {
    require(seamDistance.isFinite() && seamDistance >= 0.0f)
    require(effectiveDistance.isFinite() && effectiveDistance > 0.0f)
    return effectiveDistance - seamDistance >= MINIMUM_FRONTIER_SPAN_BLOCKS
}

/**
 * Retains the extended DH horizon in ordinary air, but honors short-range camera media such as
 * water, lava, blindness, and void fog. Those media override the sky color and must also bound
 * distant geometry; borrowing only their color leaves terrain visible far beyond the native fog.
 */
internal fun distantFogRange(
    environmentOverride: Boolean,
    cameraFogStart: Float,
    cameraFogEnd: Float,
    seamDistance: Float,
    effectiveDistance: Float,
): DistantFogRange {
    require(cameraFogStart.isFinite() && cameraFogEnd.isFinite())
    require(seamDistance.isFinite() && seamDistance >= 0.0f)
    require(effectiveDistance.isFinite() && effectiveDistance > 0.0f)
    if (environmentOverride) {
        val start = cameraFogStart.coerceAtLeast(0.0f)
        return DistantFogRange(
            start = start,
            end = max(cameraFogEnd, start + MINIMUM_FOG_SPAN),
            environment = true,
        )
    }
    val start = max(seamDistance, effectiveDistance * DISTANT_FAR_FOG_START_RATIO)
    return DistantFogRange(
        start = min(start, effectiveDistance - MINIMUM_FOG_SPAN).coerceAtLeast(0.0f),
        end = effectiveDistance,
        environment = false,
    )
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

/**
 * Conservative hydrated distance used by both the DH projection and shader-pack frame ABI.
 * The nearest uncovered base chunk is measured independently in fixed angular sectors. A low
 * sector percentile ignores isolated holes without allowing an elongated strip or sparse far
 * outlier to expose a broad raw source frontier while bounded loaders converge.
 */
internal fun distantCoverageRenderDistanceChunks(
    pages: Collection<TerrainPageKey>,
    camera: ChunkPosition,
    seamDistanceChunks: Float,
    configuredDistanceChunks: Int,
): Int {
    require(seamDistanceChunks.isFinite() && seamDistanceChunks >= 0.0f)
    require(configuredDistanceChunks > 0)
    val minimum = if (seamDistanceChunks >= configuredDistanceChunks) {
        configuredDistanceChunks
    } else {
        ceil(seamDistanceChunks).toInt() + 1
    }
    if (pages.isEmpty()) return minimum
    val diameter = Math.multiplyExact(configuredDistanceChunks, 2)
    val covered = BooleanArray(Math.multiplyExact(diameter, diameter))
    val minimumChunkX = Math.subtractExact(camera.x, configuredDistanceChunks)
    val minimumChunkZ = Math.subtractExact(camera.z, configuredDistanceChunks)
    val maximumChunkXExclusive = Math.addExact(camera.x, configuredDistanceChunks)
    val maximumChunkZExclusive = Math.addExact(camera.z, configuredDistanceChunks)
    for (page in pages) {
        require(page.detailLevel in 0..MAXIMUM_COVERAGE_DETAIL_LEVEL) {
            "Distant coverage detail level is unsupported: ${page.detailLevel}"
        }
        val span = 1L shl page.detailLevel
        val pageMinimumX = Math.multiplyExact(page.x, span)
        val pageMinimumZ = Math.multiplyExact(page.z, span)
        val fromX = maxOf(pageMinimumX, minimumChunkX.toLong())
        val fromZ = maxOf(pageMinimumZ, minimumChunkZ.toLong())
        val toX = minOf(Math.addExact(pageMinimumX, span), maximumChunkXExclusive.toLong())
        val toZ = minOf(Math.addExact(pageMinimumZ, span), maximumChunkZExclusive.toLong())
        for (z in fromZ until toZ) for (x in fromX until toX) {
            val localX = Math.toIntExact(x - minimumChunkX)
            val localZ = Math.toIntExact(z - minimumChunkZ)
            covered[Math.addExact(Math.multiplyExact(localZ, diameter), localX)] = true
        }
    }
    val sectorFrontiersSquared = DoubleArray(COVERAGE_SECTORS) {
        configuredDistanceChunks.toDouble() * configuredDistanceChunks
    }
    for (localZ in 0 until diameter) for (localX in 0 until diameter) {
        if (covered[Math.addExact(Math.multiplyExact(localZ, diameter), localX)]) continue
        val deltaX = minimumChunkX.toDouble() + localX + 0.5 - camera.x
        val deltaZ = minimumChunkZ.toDouble() + localZ + 0.5 - camera.z
        val distanceSquared = deltaX * deltaX + deltaZ * deltaZ
        if (distanceSquared > configuredDistanceChunks.toDouble() * configuredDistanceChunks) continue
        val normalizedAngle = (atan2(deltaZ, deltaX) + PI) / (2.0 * PI)
        val sector = floor(normalizedAngle * COVERAGE_SECTORS).toInt().coerceIn(0, COVERAGE_SECTORS - 1)
        if (distanceSquared < sectorFrontiersSquared[sector]) {
            sectorFrontiersSquared[sector] = distanceSquared
        }
    }
    sectorFrontiersSquared.sort()
    val percentileIndex = floor((COVERAGE_SECTORS - 1) * COVERAGE_FRONTIER_PERCENTILE).toInt()
    val frontier = kotlin.math.sqrt(sectorFrontiersSquared[percentileIndex]).toInt()
    return frontier.coerceIn(minimum, configuredDistanceChunks)
}

private const val MAXIMUM_COVERAGE_DETAIL_LEVEL = 30
private const val COVERAGE_SECTORS = 64
private const val COVERAGE_FRONTIER_PERCENTILE = 0.1
private const val DISTANT_FAR_FOG_START_RATIO = 0.85f
private const val MINIMUM_FOG_SPAN = 0.001f
private const val MINIMUM_FRONTIER_SPAN_BLOCKS = 32.0f
