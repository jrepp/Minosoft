/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.chunk

import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.kutil.profiler.stack.StackedProfiler.Companion.invoke
import de.bixilon.minosoft.config.key.KeyActions
import de.bixilon.minosoft.config.key.KeyBinding
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.positions.SectionHeight
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMesh
import de.bixilon.minosoft.gui.rendering.chunk.mesh.cache.ChunkCacheManager
import de.bixilon.minosoft.gui.rendering.chunk.mesh.types.ChunkMeshTypes
import de.bixilon.minosoft.gui.rendering.chunk.mesher.ChunkMesher
import de.bixilon.minosoft.gui.rendering.chunk.queue.culled.CulledQueue
import de.bixilon.minosoft.gui.rendering.chunk.queue.loading.MeshLoadingQueue
import de.bixilon.minosoft.gui.rendering.chunk.queue.loading.MeshUnloadingQueue
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.ChunkMeshingQueue
import de.bixilon.minosoft.gui.rendering.chunk.shader.ChunkShader
import de.bixilon.minosoft.gui.rendering.chunk.util.ChunkRendererChangeListener
import de.bixilon.minosoft.gui.rendering.chunk.visible.ChunkVisibilityManager
import de.bixilon.minosoft.gui.rendering.chunk.visible.VisibilityGraphInvalidReason
import de.bixilon.minosoft.gui.rendering.events.VisibilityGraphChangeEvent
import de.bixilon.minosoft.gui.rendering.renderer.renderer.AsyncRenderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.LayerSettings
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDrawState
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.system.base.layer.OpaqueLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.TranslucentLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.CutoutLayer
import de.bixilon.minosoft.gui.rendering.system.base.settings.RenderSettings
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendRegistry
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainPerformanceTelemetry
import de.bixilon.minosoft.gui.rendering.terrain.near.OpenGlNearTerrainRegionRuntime
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAcceptanceFaultController
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAcceptanceFaultScope

class ChunkRenderer(
    val session: PlaySession,
    override val context: RenderContext,
) : WorldRenderer, AsyncRenderer {
    override val layers = LayerSettings()
    private val profile = session.profiles.block
    private val shader = context.system.shader.create(minosoft("chunk")) { ChunkShader(it) }
    private val textShader = context.system.shader.create(minosoft("chunk")) { ChunkShader(it) }
    val world = session.world
    val terrainPerformance = TerrainPerformanceTelemetry()
    val terrainFaults = TerrainAcceptanceFaultController()
    val visibility = ChunkVisibilityManager(this)

    val culledQueue = CulledQueue(this)
    val meshingQueue = ChunkMeshingQueue(this)
    val loadingQueue = MeshLoadingQueue(this)
    val unloadingQueue = MeshUnloadingQueue(this)

    val mesher = ChunkMesher(this)
    val loaded = LoadedMeshes(this)
    val cache = ChunkCacheManager(this)
    val terrain = TerrainBackendRegistry(BuiltInChunkTerrainBackend(this))
    val regionTerrain = OpenGlNearTerrainRegionRuntime.create(context)


    var limitChunkTransferTime = true

    private fun registerMeshLayer(layer: RenderLayer, material: TerrainMaterialClass, type: ChunkMeshTypes) {
        layers.registerSemantic(
            layer = layer,
            shader = null,
            renderer = { terrain.submit(RenderViewId.MAIN, material) },
            semantic = material.semantic,
            owner = { terrain.selection().owner },
            passId = material.passId,
            skip = {
                val visible = visibility.meshes
                visible.lock.locked {
                    if (regionTerrain != null) {
                        !regionTerrain.hasVisibleMaterial(material, visible.sections) &&
                            visible.meshes[type.ordinal].isEmpty()
                    } else {
                        visible.meshes[type.ordinal].isEmpty()
                    }
                }
            },
        )
    }

    override fun registerLayers() {
        registerMeshLayer(OpaqueLayer, TerrainMaterialClass.OPAQUE, ChunkMeshTypes.OPAQUE)
        registerMeshLayer(CutoutLayer, TerrainMaterialClass.CUTOUT, ChunkMeshTypes.CUTOUT)
        registerMeshLayer(TranslucentLayer, TerrainMaterialClass.TRANSLUCENT, ChunkMeshTypes.TRANSLUCENT)
        registerMeshLayer(TextLayer, TerrainMaterialClass.EMISSIVE_ADDITIVE, ChunkMeshTypes.TEXT)
        layers.registerSemantic(
            OpaqueBlockEntitiesLayer,
            null,
            this::drawBlockEntities,
            semantic = PipelineSemantic.BLOCK_ENTITIES,
            passId = RenderPassId("minosoft:scene/block-entities-opaque"),
            skip = { visibility.meshes.entities.isEmpty() },
            auxiliaryRenderers = mapOf(
                IrisShaderPackPlanner.SHADOW_VIEW to {
                    drawBlockEntities(shadow = true)
                    Unit
                },
            ),
        )
        layers.registerSemantic(
            TranslucentBlockEntitiesLayer,
            null,
            this::drawTranslucentBlockEntities,
            semantic = PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
            passId = RenderPassId("minosoft:scene/block-entities-translucent"),
            skip = { visibility.meshes.entities.none(BlockEntityRenderer::hasTranslucentPass) },
        )
    }

    override fun postInit(latch: AbstractLatch) {
        shader.load()
        textShader.native.defines["DISABLE_MIPMAPS"] = ""
        textShader.load()


        session.events.listen<VisibilityGraphChangeEvent> { visibility.invalidate(VisibilityGraphInvalidReason.VISIBILITY_GRAPH) }

        ChunkRendererChangeListener.register(this)

        var paused = false
        context::state.observe(this) {
            if (it == RenderingStates.PAUSED) {
                unload(world)
                paused = true
            } else if (paused) {
                invalidate(world, TerrainBuildCause.WORLD_RESUME)
                paused = false
            }
        }
        context.camera.offset::offset.observe(this) {
            unload(world)
            invalidate(world, TerrainBuildCause.CAMERA_OFFSET)
        }

        context.input.bindings.register(minosoft("clear_chunk_cache"), KeyBinding(
            KeyActions.MODIFIER to setOf(KeyCodes.KEY_F3),
            KeyActions.PRESS to setOf(KeyCodes.KEY_A),
        )) {
            unload(world); invalidate(world, TerrainBuildCause.MANUAL_RELOAD)
            session.util.sendDebugMessage("Chunk cache invalidated!")
        }

        profile.rendering::antiMoirePattern.observe(this) { invalidate(world, TerrainBuildCause.RENDER_SETTING_CHANGE) }

        val profile = session.profiles.rendering
        profile.light::ambientOcclusion.observe(this) { invalidate(world, TerrainBuildCause.RENDER_SETTING_CHANGE) }
        profile.biome.blending::enabled.observe(this) { invalidate(world, TerrainBuildCause.RENDER_SETTING_CHANGE) }
        profile.biome.blending::radius.observe(this) { invalidate(world, TerrainBuildCause.RENDER_SETTING_CHANGE) }
        profile.biome.blending::algorithm.observe(this) { invalidate(world, TerrainBuildCause.RENDER_SETTING_CHANGE) }
        profile.performance::limitChunkTransferTime.observe(this) { this.limitChunkTransferTime = it }
    }

    fun unload(world: World) {
        culledQueue.clear()
        meshingQueue.clear()
        loadingQueue.clear()
        loaded.clear()
        cache.clear()
        meshingQueue.tasks.interrupt(false)

        context.queue += { unloadingQueue.work() }
        visibility.invalidate(VisibilityGraphInvalidReason.MESH_UPDATE)
    }

    fun unload(chunk: Chunk) {
        culledQueue -= chunk
        meshingQueue -= chunk.position
        meshingQueue.tasks.interrupt(chunk.position)

        loadingQueue -= chunk.position

        loaded -= chunk.position
    }

    fun unload(section: ChunkSection) {
        val position = SectionPosition.of(section.chunk.position, section.height)
        culledQueue -= section
        meshingQueue -= position
        meshingQueue.tasks.interrupt(position)

        loadingQueue -= position

        loaded -= position

        // TODO: potential race condition (what if section is between two stages?)
    }

    fun invalidate(world: World, cause: TerrainBuildCause = TerrainBuildCause.UNKNOWN) = world.lock.acquired {
        for (chunk in world.chunks.chunks.unsafe.values) {
            invalidate(chunk, cause)
        }
    }

    fun invalidate(chunk: Chunk, cause: TerrainBuildCause = TerrainBuildCause.UNKNOWN) {
        if (!chunk.neighbours.complete) {
            unload(chunk)
            return
        }
        if (chunk.position in visibility) {
            chunk.sections.forEach { invalidate(it, cause) }
            // no need to unload any other sections, sections can only be created but never deleted
            return
        }
        unload(chunk) // TODO: don't remove from culled queue
        culledQueue += chunk
    }

    fun invalidate(section: ChunkSection) = invalidate(section, TerrainBuildCause.UNKNOWN, true)

    fun invalidate(section: ChunkSection, cause: TerrainBuildCause, advanceRevision: Boolean = true) {
        if (advanceRevision) section.terrainRevision.updateAndGet(Math::incrementExact)
        val position = SectionPosition.of(section)
        if (context.state == RenderingStates.PAUSED || context.state == RenderingStates.STOPPED || context.state == RenderingStates.QUITTING) return
        if (section.blocks.isEmpty || !section.chunk.neighbours.complete) {
            return unload(section)
        }

        meshingQueue.tasks.interrupt(position)

        if (section in visibility) {
            meshingQueue.add(section, cause)
        } else {
            unload(section) // TODO: don't remove from culled queue
            culledQueue += section
        }
    }

    fun invalidate(chunk: Chunk?, height: SectionHeight, cause: TerrainBuildCause = TerrainBuildCause.UNKNOWN) {
        val section = chunk?.get(height) ?: return
        invalidate(section, cause)
    }

    fun invalidate(position: SectionPosition, cause: TerrainBuildCause = TerrainBuildCause.UNKNOWN) {
        invalidate(world.chunks[position.chunkPosition], position.y, cause)
    }

    override fun prepareDrawAsync() {
        terrain.prepare()
    }

    internal fun prepareTerrainCore() {
        regionTerrain?.prepareFrame()
        visibility.update()
        meshingQueue.work()
    }

    override fun postPrepareDraw() {
        terrain.finishPreparation()
    }

    internal fun finishTerrainPreparationCore() {
        context.profiler("meshingPublish") { meshingQueue.publishCompleted() }
        context.profiler("unloading") { unloadingQueue.work() }
        context.profiler("loading") { loadingQueue.work() }
    }


    override fun postDraw() {
        terrain.finishFrame()
    }

    internal fun finishTerrainFrameCore() {
        regionTerrain?.finishFrame()
        val meshes = visibility.meshes
        meshes.lock.locked { meshes.meshes[ChunkMeshTypes.OPAQUE.ordinal].firstOrNull() }?.updateOcclusion() // don't lock all meshes, updateOcclusion is a blocking operation

        meshes.lock.locked {
            for (type in ChunkMeshTypes) {
                meshes.meshes[type.ordinal].removeIf { it.updateOcclusion(); it.occlusion == ChunkMesh.OcclusionStates.INVISIBLE }
            }
        }
    }

    internal fun submitTerrainCore(view: RenderViewId, material: TerrainMaterialClass) {
        val (type, activeShader) = when (material) {
            TerrainMaterialClass.OPAQUE -> ChunkMeshTypes.OPAQUE to shader
            TerrainMaterialClass.CUTOUT -> ChunkMeshTypes.CUTOUT to shader
            TerrainMaterialClass.TRANSLUCENT -> ChunkMeshTypes.TRANSLUCENT to shader
            TerrainMaterialClass.EMISSIVE_ADDITIVE -> ChunkMeshTypes.TEXT to textShader
        }
        context.shaderPipeline.withPipeline { pipeline ->
            pipeline.bindTerrain(view, material, activeShader)
            val meshes = visibility.meshes
            val shadow = if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                context.shaderPipeline.plan()?.shadowDirectives
            } else {
                null
            }
            if (regionTerrain != null) {
                val selected = ArrayList<de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes>()
                val conventional = ArrayList<de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMesh>()
                if (shadow != null) {
                    val culling = context.shaderPipeline.shadowCulling()
                    val camera = context.session.camera.entity.physics.positionInfo.eyePosition
                    loaded.forEachLoaded { section ->
                        val allowed = culling?.allowsTerrainSection(
                            section.position.x,
                            section.position.y,
                            section.position.z,
                        ) ?: run {
                            val delta = camera - section.center
                            shadow.allowsTerrainSection(delta.x, delta.y, delta.z)
                        }
                        if (allowed) {
                            if (section.regionBacked) {
                                selected += section
                            } else {
                                section.meshes[type]?.let(conventional::add)
                            }
                        }
                    }
                } else {
                    meshes.lock.locked { selected += meshes.sections }
                }
                regionTerrain.submit(
                    view,
                    material,
                    selected,
                    context.shaderPipeline.selection().generation,
                )
                if (shadow != null) {
                    conventional.forEach { it.drawShadow() }
                } else {
                    meshes.lock.locked {
                        meshes.meshes[type.ordinal].forEach { it.draw() }
                    }
                }
            } else if (shadow != null) {
                val culling = context.shaderPipeline.shadowCulling()
                val camera = context.session.camera.entity.physics.positionInfo.eyePosition
                loaded.forEachLoaded { section ->
                    val allowed = culling?.allowsTerrainSection(
                        section.position.x,
                        section.position.y,
                        section.position.z,
                    ) ?: run {
                        val delta = camera - section.center
                        shadow.allowsTerrainSection(delta.x, delta.y, delta.z)
                    }
                    if (allowed) {
                        section.meshes[type]?.drawShadow()
                    }
                }
            } else {
                meshes.lock.locked {
                    meshes.meshes[type.ordinal].forEach { mesh ->
                        mesh.draw()
                    }
                }
            }
        }
    }

    private fun drawBlockEntities(shadow: Boolean = false) {
        val directives = if (shadow) context.shaderPipeline.plan()?.shadowDirectives else null
        val culling = if (shadow) context.shaderPipeline.shadowCulling() else null
        val camera = context.session.camera.entity.renderInfo.eyePosition
        fun draw(renderer: BlockEntityRenderer) {
            if (directives != null) {
                val entity = renderer.entity
                val position = entity.position
                if (
                    !directives.allowsBlockEntity(entity.state.luminance) ||
                    !(culling?.allowsBlockEntityBounds(position.x, position.y, position.z)
                        ?: directives.allowsBlockEntityBounds(
                            camera.x,
                            camera.y,
                            camera.z,
                            position.x,
                            position.y,
                            position.z,
                        ))
                ) {
                    return
                }
            }
            context.shaderPipeline.withDrawState(IrisDrawState(blockEntity = renderer.entity.state)) {
                if (shadow) renderer.drawShadow() else renderer.draw()
            }
        }
        if (shadow) {
            loaded.forEachLoaded { section ->
                if (
                    culling == null ||
                    culling.allowsTerrainSection(section.position.x, section.position.y, section.position.z)
                ) {
                    section.entities?.forEach(::draw)
                }
            }
        } else {
            val visible = visibility.meshes
            visible.lock.locked { visible.entities.forEach(::draw) }
        }
    }
    private fun drawTranslucentBlockEntities() = visibility.meshes.apply {
        lock.locked {
            entities.forEach { renderer ->
                if (renderer.hasTranslucentPass) {
                    context.shaderPipeline.withDrawState(
                        IrisDrawState(blockEntity = renderer.entity.state),
                        renderer::drawTranslucent,
                    )
                }
            }
        }
    }

    override fun unload() {
        terrainFaults.invalidate()
        terrain.close()
    }

    fun terrainFaultScope(): TerrainAcceptanceFaultScope {
        val selection = context.renderer.pipeline.terrainSelection()
        return TerrainAcceptanceFaultScope(
            worldEpoch = session.world.terrainEpoch,
            pipelineGeneration = selection.generation,
            shaderGeneration = selection.identity.shaderPipelineGeneration,
            nearLayoutGeneration = selection.identity.nearLayoutGeneration,
        )
    }

    internal fun closeTerrainCore() {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        cleanup(culledQueue::clear)
        cleanup { meshingQueue.tasks.interrupt(false) }
        cleanup(meshingQueue::close)
        cleanup(loadingQueue::clear)
        cleanup(loaded::close)
        cleanup(cache::clear)
        cleanup(unloadingQueue::drain)
        cleanup { regionTerrain?.close() }

        failure?.let { throw it }
    }

    private object TextLayer : RenderLayer {
        override val settings = RenderSettings(blending = true, depth = DepthFunctions.LESS_OR_EQUAL, polygonOffset = true, polygonOffsetFactor = -2.5f, polygonOffsetUnit = -2.5f)
        override val priority: Int get() = 1500
    }

    private object OpaqueBlockEntitiesLayer : RenderLayer {
        override val settings = RenderSettings(depth = DepthFunctions.LESS_OR_EQUAL) // TODO: blending?
        override val priority: Int get() = 500
    }

    private object TranslucentBlockEntitiesLayer : RenderLayer {
        override val settings = TranslucentLayer.settings.copy(
            faceCulling = false,
            depth = DepthFunctions.LESS_OR_EQUAL,
        )
        override val priority: Int get() = TranslucentLayer.priority - 2
    }

    companion object : RendererBuilder<ChunkRenderer> {

        override fun build(session: PlaySession, context: RenderContext) = ChunkRenderer(session, context)
    }
}

private val TerrainMaterialClass.semantic: PipelineSemantic
    get() = when (this) {
        TerrainMaterialClass.OPAQUE -> PipelineSemantic.TERRAIN_OPAQUE
        TerrainMaterialClass.CUTOUT -> PipelineSemantic.TERRAIN_CUTOUT
        TerrainMaterialClass.TRANSLUCENT -> PipelineSemantic.TERRAIN_TRANSLUCENT
        TerrainMaterialClass.EMISSIVE_ADDITIVE -> PipelineSemantic.TERRAIN_EMISSIVE
    }

private val TerrainMaterialClass.passId: RenderPassId
    get() = RenderPassId("minosoft:terrain/${name.lowercase().replace('_', '-')}")
