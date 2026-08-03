/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.terrain.distant

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.types.fluid.FluidHolder
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.Rendering
import de.bixilon.minosoft.gui.rendering.chunk.NativeTerrainOwnershipSnapshot
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.terrain.storage.OpenGlTerrainRegionDevice
import de.bixilon.minosoft.gui.rendering.terrain.storage.OpenGlTerrainStagingBuffer
import de.bixilon.minosoft.gui.rendering.terrain.storage.OpenGlTerrainSubmissionCompletion
import de.bixilon.minosoft.gui.rendering.terrain.storage.TerrainRegionFrameSubmission
import de.bixilon.minosoft.terrain.distant.DistantCompatibilityMaterial
import de.bixilon.minosoft.terrain.distant.DistantCompatibilityMaterialResolver
import de.bixilon.minosoft.terrain.distant.DistantHierarchyPageDiagnostic
import de.bixilon.minosoft.terrain.distant.DistantHierarchyRegionDiagnostic
import de.bixilon.minosoft.terrain.distant.DistantHierarchyRenderDiagnostics
import de.bixilon.minosoft.terrain.distant.DistantLodChanges
import de.bixilon.minosoft.terrain.distant.DistantLodRenderDiagnostics
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.DistantLodTileSource
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderConfig
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderSource
import de.bixilon.minosoft.terrain.distant.DistantTerrainInterop
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageHierarchy
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageHierarchyIndex
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageBuildState
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMeshArtifact
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMesher
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageNeighbourhood
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelectionMetadata
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelectionRequest
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelector
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantSelectionCamera
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantRenderPublication
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantSourceBatchMutation
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantSourceMutationStatus
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantSourcePageUpdate
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantSourceRemoval
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPageReducer
import de.bixilon.minosoft.terrain.distant.toTopOnlyCompatibilityPage
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoveragePageMask
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTransitionPolicy
import de.bixilon.minosoft.terrain.runtime.TerrainProcessBuildService
import de.bixilon.minosoft.terrain.runtime.TerrainFailureCategory
import de.bixilon.minosoft.terrain.runtime.TerrainFailurePhase
import de.bixilon.minosoft.terrain.runtime.TerrainPageFailureRegistry
import de.bixilon.minosoft.terrain.runtime.TerrainRetryEligibility
import de.bixilon.minosoft.terrain.runtime.TerrainRetrySignal
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageQuery
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageRangeDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageRevisionDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageVisibilityDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildOutcome
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildEstimate
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildUrgency
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainCancellationToken
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSchedulerTenantId
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionExtent
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionKey
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionStorage
import de.bixilon.minosoft.terrain.runtime.storage.TerrainResidencyTarget
import de.bixilon.minosoft.terrain.runtime.storage.TerrainSelectionPublication
import de.bixilon.minosoft.terrain.runtime.storage.TerrainUploadBudgetItem
import de.bixilon.minosoft.terrain.runtime.storage.TerrainUploadBudgetScheduler
import de.bixilon.minosoft.terrain.runtime.storage.TerrainUploadUrgency
import de.bixilon.minosoft.terrain.runtime.storage.TerrainViewKey
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicLong
import java.util.TreeSet
import org.lwjgl.opengl.GL11.glFinish
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max

internal data class DistantCoverageDrawSelection(
    val selected: List<TerrainPageKey>,
    val drawable: List<TerrainPageKey>,
) {
    val maskedPages: Int = Math.subtractExact(selected.size, drawable.size)
}

internal fun distantCoverageDrawSelection(
    selected: List<TerrainPageKey>,
    coverage: TerrainCoverageSnapshot?,
    transitionPolicy: TerrainCoverageTransitionPolicy,
): DistantCoverageDrawSelection {
    val stableSelection = java.util.List.copyOf(selected)
    val mask = TerrainCoveragePageMask(coverage, transitionPolicy)
    return DistantCoverageDrawSelection(
        selected = stableSelection,
        drawable = stableSelection.filter(mask::drawDistant),
    )
}

/** Production bridge for the distant page hierarchy and semantic mesher. */
internal class DistantHierarchicalTerrainRuntime(
    private val context: RenderContext,
    private val source: DistantTerrainRenderSource,
    private val config: DistantTerrainRenderConfig,
    private val solidShader: DistantTerrainShader,
    private val waterShader: DistantTerrainShader,
    private val providerGeneration: Long,
    private val layoutGeneration: Long,
) : AutoCloseable {
    private data class Pending(
        val identity: TerrainBuildIdentity,
        val cancellation: TerrainCancellationToken,
        val purpose: BuildPurpose,
    )

    private enum class BuildPurpose {
        SOURCE,
        ARTIFACT,
    }

    private sealed interface BuildInput

    private data class BaseInput(
        val tile: DistantLodTile,
        val verticalPage: DistantVerticalPage?,
        val neighbourTiles: List<DistantLodTile>,
        val neighbourVerticalPages: Map<TerrainPageKey, DistantVerticalPage>,
        val materials: Map<ResourceLocation, DistantCompatibilityMaterial?>,
    ) : BuildInput

    private data class DerivedInput(
        val children: List<DistantVerticalPage>,
        val neighbours: List<DistantVerticalPage>,
    ) : BuildInput

    private data class MeshInput(
        val page: DistantVerticalPage,
        val neighbours: List<DistantVerticalPage>,
    ) : BuildInput

    private data class BuildRequest(
        val identity: TerrainBuildIdentity,
        val originY: Int,
        val purpose: BuildPurpose,
        val input: BuildInput,
    )

    private data class BuildResult(
        val request: BuildRequest,
        val page: DistantVerticalPage,
        val artifact: DistantPageMeshArtifact?,
        val selectionMetadata: DistantPageSelectionMetadata,
    )

    private data class CpuPage(
        val source: DistantVerticalPage,
        val artifact: DistantPageMeshArtifact,
        val identity: TerrainBuildIdentity,
        val publicationVersion: Long,
    )

    private data class GpuPage(
        val publicationVersion: Long,
        val digest: String,
        val materialGeneration: Long,
        val region: Region?,
        val hasSolid: Boolean,
        val hasWater: Boolean,
    )

    private data class DrawGroup(
        val region: Region,
        val materialGeneration: Long,
        val pages: List<TerrainPageKey>,
    )

    private data class DrawPlan(
        val selection: List<TerrainPageKey>,
        val gpuRevision: Long,
        val groups: List<DrawGroup>,
        val hasSolid: Boolean,
        val hasWater: Boolean,
    ) {
        companion object {
            val EMPTY = DrawPlan(emptyList(), -1L, emptyList(), false, false)
        }
    }

    private data class SelectionInput(
        val cameraChunkX: Int,
        val cameraChunkZ: Int,
        val eyeBlockX: Long,
        val eyeBlockY: Long,
        val eyeBlockZ: Long,
        val verticalFieldOfViewBits: Long,
        val viewportHeightPixels: Int,
        val seamDistanceBits: Int,
        val dataRevision: Long,
        val nearCoverageRevision: Long,
    )

    private data class Region(
        val key: TerrainRegionKey,
        val shard: Int,
        val device: OpenGlTerrainRegionDevice,
        val storage: TerrainRegionStorage,
    )

    private val lease = TerrainProcessBuildService.shared.register<AutoCloseable, BuildResult>(
        ownerId = "distant-hierarchy:${context.session.sessionId}",
        tenant = TerrainSchedulerTenantId("distant-hierarchy:${context.session.sessionId}"),
        contextFactory = { AutoCloseable {} },
        disposer = {},
    )
    private val requestSequence = AtomicLong()
    private val failureClockOrigin = System.nanoTime()
    private var worldEpoch = context.session.world.terrainEpoch
    private var index = createIndex(worldEpoch)
    private var mainSelector = DistantPageSelector(worldEpoch)
    private var shadowSelector = DistantPageSelector(worldEpoch)
    private val sourceTiles = LinkedHashMap<TerrainPageKey, DistantLodTile>()
    private val sourceVerticalPages = LinkedHashMap<TerrainPageKey, DistantVerticalPage>()
    private val tileSourceRevisions = HashMap<TerrainPageKey, Long>()
    private val pending = HashMap<TerrainPageKey, Pending>()
    private var buildPriority = DistantTerrainBuildPriority(0, 0, 0.0f)
    private var pendingSourceIngest = TreeSet(buildPriority.comparator)
    private var needsSourceBuild = TreeSet(buildPriority.comparator)
    private var needsArtifactBuild = TreeSet(buildPriority.comparator)
    private val failures = TerrainPageFailureRegistry(failureCapacity())
    private val failurePurposes = HashMap<TerrainPageKey, BuildPurpose>()
    private val cpuPages = LinkedHashMap<TerrainPageKey, DistantVerticalPage>()
    private val cpuArtifacts = LinkedHashMap<TerrainPageKey, CpuPage>()
    private val cpuSelectionMetadata = LinkedHashMap<TerrainPageKey, DistantPageSelectionMetadata>()
    private val gpuPages = LinkedHashMap<TerrainPageKey, GpuPage>()
    private val gpuPublicationVersions = LinkedHashMap<TerrainPageKey, Long>()
    private val system = context.system as OpenGlRenderSystem
    private val staging = OpenGlTerrainStagingBuffer(VERTEX_CAPACITY_BYTES)
    private val regions = LinkedHashMap<TerrainRegionKey, MutableList<Region>>()
    private val selectionPublication = TerrainSelectionPublication()
    private val deviceRuntime = context.terrainSubmissions.deviceRuntime
    private val completion = OpenGlTerrainSubmissionCompletion(context, deviceRuntime)
    private val frameSubmission = TerrainRegionFrameSubmission(context) { completion }
    private var sourceRevision = Long.MIN_VALUE
    private val sourceKinds = LinkedHashMap<ChunkPosition, DistantLodTileSource>()
    private var nativeOwnership = NativeTerrainOwnershipSnapshot(0L, emptySet())
    private var mainSelection: List<TerrainPageKey> = emptyList()
    private var shadowSelection: List<TerrainPageKey> = emptyList()
    private var mainDrawSelection: List<TerrainPageKey> = emptyList()
    private var shadowDrawSelection: List<TerrainPageKey> = emptyList()
    private var mainMaskedPages = 0
    private var shadowMaskedPages = 0
    private var mainDrawPlan = DrawPlan.EMPTY
    private var shadowDrawPlan = DrawPlan.EMPTY
    private var gpuRevision = 0L
    private var coverageDistanceSelection: List<TerrainPageKey> = emptyList()
    private var coverageDistanceCamera: ChunkPosition? = null
    private var coverageDistanceSeamBits = 0
    private var effectiveRenderDistanceChunks = config.renderDistanceChunks
    private var selectionDataRevision = 0L
    private var lastSelectionInput: SelectionInput? = null
    private var lastSelectionFrame = 0L
    private var resetSelectionAfterIngest = false
    private var diagnosticFrame = 0
    private var shaderGeneration = context.shaderPipeline.selection().generation
    private var storageCapacityRevision = 0L
    private var closed = false

    fun prepare(
        cameraChunk: ChunkPosition,
        seamDistanceChunks: Float,
        ownership: NativeTerrainOwnershipSnapshot,
    ) {
        if (closed) return
        prepareStorageFrame()
        if (worldEpoch != context.session.world.terrainEpoch) resetWorld(context.session.world.terrainEpoch)
        updateBuildPriority(cameraChunk, seamDistanceChunks)
        nativeOwnership = ownership
        invalidateChangedPipelineGeneration()
        if (!source.presentationEnabled) {
            // Cancelled builds still own routed shared-runtime capacity until
            // this tenant consumes their completions. Keep draining while the
            // presentation is disabled so a toggle cannot strand mailbox
            // capacity or prevent a bounded idle boundary.
            drainCompletions()
            clearContent()
            updateEffectiveRenderDistance(cameraChunk, seamDistanceChunks)
            return
        }
        if (source.revision != sourceRevision) synchronize(source.changesSince(sourceRevision))
        drainSourceIngest()
        drainCompletions()
        retryFailures(failureClockNanos())
        retryCapacityFailures()
        updateSelections(cameraChunk, seamDistanceChunks)
        drainUploads()
        promoteSelections()
        updateEffectiveRenderDistance(cameraChunk, seamDistanceChunks)
        evictUnselectedPages()
        scheduleQueued()
    }

    fun hasSolid(): Boolean = mainDrawPlan.hasSolid

    fun hasWater(): Boolean = mainDrawPlan.hasWater

    fun hasShadow(): Boolean = shadowDrawPlan.hasSolid

    fun effectiveRenderDistanceChunks(): Int = effectiveRenderDistanceChunks

    /** Advances prior-frame ownership at a context-current diagnostic boundary. */
    fun collectSubmissionCompletions() {
        if (closed) return
        completion.collect()
        regions.values.asSequence().flatten().forEach { it.storage.collectRetired() }
    }

    fun drawSolid(shadow: Boolean) {
        val plan = if (shadow) shadowDrawPlan else mainDrawPlan
        draw(plan, solidShader, solid = true, view = if (shadow) SHADOW_VIEW else MAIN_VIEW)
    }

    fun drawWater() = draw(mainDrawPlan, waterShader, solid = false, view = MAIN_VIEW)

    private fun draw(
        plan: DrawPlan,
        shader: DistantTerrainShader,
        solid: Boolean,
        view: TerrainViewKey,
    ) {
        if (plan.groups.isEmpty()) return
        val origin = context.camera.offset.offset
        val material = if (solid) {
            DistantTerrainRegionArtifactEncoder.solidMaterial
        } else {
            DistantTerrainRegionArtifactEncoder.waterMaterial
        }
        try {
            for (group in plan.groups) {
                val region = group.region
                val key = region.key
                frameSubmission.draw(
                    device = region.device,
                    storage = region.storage,
                    material = material,
                    view = view,
                    orderedPages = group.pages,
                    layoutGeneration = layoutGeneration,
                    materialGeneration = group.materialGeneration,
                    beforeDraw = {
                        val pageSizeBlocks = Math.multiplyExact(16L, 1L shl key.detailLevel)
                        shader.pageOffset = Vec3f(
                            (Math.multiplyExact(Math.multiplyExact(key.x, REGION_EXTENT.x.toLong()), pageSizeBlocks) - origin.x).toFloat(),
                            (context.session.world.dimension.minY - origin.y).toFloat(),
                            (Math.multiplyExact(Math.multiplyExact(key.z, REGION_EXTENT.z.toLong()), pageSizeBlocks) - origin.z).toFloat(),
                        )
                    },
                )
            }
        } finally {
            shader.pageOffset = Vec3f()
        }
    }

    fun finishFrame() {
        frameSubmission.finishFrame()
        diagnosticFrame = Math.addExact(diagnosticFrame, 1)
        if (diagnosticFrame >= DIAGNOSTIC_FRAME_INTERVAL) {
            diagnosticFrame = 0
            publishDiagnostics()
        }
    }

    private fun synchronize(changes: DistantLodChanges) {
        if (changes.reset && changes.updates.any { it.verticalPage != null }) resetSelectionAfterIngest = true
        val updateKeys = if (changes.reset) {
            changes.updates.mapTo(linkedSetOf()) { pageKey(it.tile.position) }
        } else {
            emptySet()
        }
        val removed = if (changes.reset) {
            sourceTiles.keys - updateKeys
        } else {
            changes.removals.mapTo(linkedSetOf(), ::pageKey)
        }
        for (key in removed) {
            sourceKinds.remove(ChunkPosition(key.x.toInt(), key.z.toInt()))
            removePage(key)
        }

        for (update in changes.updates) {
            val tile = update.tile
            val key = pageKey(tile.position)
            val verticalPage = update.verticalPage
            if (sourceTiles[key] === tile && sourceVerticalPages[key] === verticalPage) continue
            cancelPending(key)
            sourceTiles[key] = tile
            if (verticalPage == null) sourceVerticalPages.remove(key) else sourceVerticalPages[key] = verticalPage
            sourceKinds[tile.position] = update.source
            tileSourceRevisions[key] = verticalPage?.sourceRevision
                ?: Math.addExact(tileSourceRevisions[key] ?: 0L, 1L)
            failures.clear(key)
            failurePurposes.remove(key)
            if (verticalPage == null) {
                needsSourceBuild += key
            } else {
                pendingSourceIngest += key
            }
        }
        sourceRevision = changes.revision
    }

    private fun removePage(key: TerrainPageKey) {
        val cpuPageCount = cpuPages.size
        sourceTiles.remove(key)
        sourceVerticalPages.remove(key)
        tileSourceRevisions.remove(key)
        pendingSourceIngest.remove(key)
        needsSourceBuild.remove(key)
        needsArtifactBuild.remove(key)
        failures.clear(key)
        failurePurposes.remove(key)
        cancelPending(key)
        cpuPages.remove(key)
        cpuArtifacts.remove(key)
        cpuSelectionMetadata.remove(key)
        when (val removal = index.removeSource(key)) {
            is DistantSourceRemoval.Removed -> {
                for (removed in removal.removedPages) {
                    if (removed == key) continue
                    cancelPending(removed)
                    cpuPages.remove(removed)
                    cpuArtifacts.remove(removed)
                    cpuSelectionMetadata.remove(removed)
                }
                for (changed in removal.sourceChangedPages.filter { it.detailLevel > 0 }) {
                    if (DistantPageHierarchy.children(changed).all(cpuPages::containsKey)) continue
                    cancelPending(changed)
                    cpuPages.remove(changed)
                    cpuArtifacts.remove(changed)
                    cpuSelectionMetadata.remove(changed)
                }
            }

            DistantSourceRemoval.Absent -> Unit
        }
        if (cpuPages.size != cpuPageCount) invalidateSelectionData()
    }

    private fun cancelPending(key: TerrainPageKey) {
        pending.remove(key)?.cancellation?.cancel()
        pendingSourceIngest.remove(key)
        needsSourceBuild.remove(key)
        needsArtifactBuild.remove(key)
    }

    private fun updateBuildPriority(camera: ChunkPosition, seamDistanceChunks: Float) {
        val next = DistantTerrainBuildPriority(camera.x, camera.z, seamDistanceChunks)
        if (next == buildPriority) return
        buildPriority = next
        pendingSourceIngest = TreeSet(next.comparator).also { it += pendingSourceIngest }
        needsSourceBuild = TreeSet(next.comparator).also { it += needsSourceBuild }
        needsArtifactBuild = TreeSet(next.comparator).also { it += needsArtifactBuild }
    }

    private fun drainSourceIngest() {
        val pages = ArrayList<DistantVerticalPage>(MAX_SOURCE_INGEST_PER_FRAME)
        val iterator = pendingSourceIngest.iterator()
        var remaining = MAX_SOURCE_INGEST_PER_FRAME
        while (remaining > 0 && iterator.hasNext()) {
            remaining--
            val key = iterator.next()
            iterator.remove()
            val page = sourceVerticalPages[key] ?: continue
            if (page.key != key) {
                needsSourceBuild += key
                continue
            }
            pages += page
        }
        if (pages.isEmpty()) return
        val updates = pages.map { page ->
            DistantSourcePageUpdate(
                page = page.key,
                sourceRevision = page.sourceRevision,
                completeness = page.completeness,
                semanticDigest = de.bixilon.minosoft.terrain.distant.DistantSemanticDigest(
                    1,
                    "SHA-256",
                    page.semanticDigest,
                ),
            )
        }
        when (val mutation = index.publishSourceBatchMutation(updates)) {
            is DistantSourceBatchMutation.Published -> {
                clearFailures(mutation.sourceChangedPages + mutation.dirtiedPages)
                for (page in pages) {
                    cpuPages[page.key] = page
                    cpuSelectionMetadata[page.key] = selectionMetadata(page)
                }
                invalidateSelectionData()
            }

            DistantSourceBatchMutation.Unchanged -> {
                for (page in pages) {
                    cpuPages[page.key] = page
                    cpuSelectionMetadata[page.key] = selectionMetadata(page)
                }
                invalidateSelectionData()
            }

            is DistantSourceBatchMutation.Rejected -> {
                var changed = false
                for (page in pages) {
                    changed = installSourcePage(page, invalidateSelection = false) || changed
                }
                if (changed) invalidateSelectionData()
            }
        }
        if (pendingSourceIngest.isEmpty() && resetSelectionAfterIngest) {
            resetSelectionAfterIngest = false
            mainSelector = DistantPageSelector(worldEpoch)
            shadowSelector = DistantPageSelector(worldEpoch)
            lastSelectionInput = null
        }
    }

    private fun scheduleQueued() {
        scheduleQueued(
            needsSourceBuild,
            BuildPurpose.SOURCE,
            alreadyAdmitted = 0,
            admissionLimit = MAX_SOURCE_SUBMISSIONS_PER_FRAME,
        )
        scheduleQueued(
            needsArtifactBuild,
            BuildPurpose.ARTIFACT,
            alreadyAdmitted = 0,
            admissionLimit = MAX_ARTIFACT_SUBMISSIONS_PER_FRAME,
        )
    }

    private fun scheduleQueued(
        queue: MutableSet<TerrainPageKey>,
        purpose: BuildPurpose,
        alreadyAdmitted: Int,
        admissionLimit: Int,
    ): Int {
        var admitted = alreadyAdmitted
        var purposePending = pending.values.count { it.purpose == purpose }
        val pendingLimit = when (purpose) {
            BuildPurpose.SOURCE -> MAX_PENDING_SOURCE_PAGES
            BuildPurpose.ARTIFACT -> MAX_PENDING_ARTIFACT_PAGES
        }
        val iterator = queue.iterator()
        while (iterator.hasNext() && admitted < admissionLimit && purposePending < pendingLimit &&
            pending.size < MAX_PENDING_PAGES
        ) {
            val key = iterator.next()
            if (key in pending) {
                iterator.remove()
                continue
            }
            val hierarchyPage = index[key]
            if (failures[key] != null) {
                iterator.remove()
                continue
            }
            if (purpose == BuildPurpose.ARTIFACT && hierarchyPage?.dirty == false) {
                iterator.remove()
                continue
            }
            val sourceDataRevision = hierarchyPage?.sourceRevision
                ?: tileSourceRevisions[key]?.takeIf { key.detailLevel == 0 }
                ?: run {
                    iterator.remove()
                    continue
                }
            if (purpose == BuildPurpose.SOURCE && cpuPages[key]?.sourceRevision == sourceDataRevision) {
                iterator.remove()
                continue
            }
            val input = buildInput(key, purpose) ?: run {
                if (purpose != BuildPurpose.SOURCE || key.detailLevel == 0) iterator.remove()
                continue
            }
            val sequence = requestSequence.updateAndGet { Math.incrementExact(it) }
            val identity = buildIdentity(key, sequence, sourceDataRevision)
            val request = BuildRequest(identity, context.session.world.dimension.minY, purpose, input)
            val cancellation = TerrainCancellationToken()
            val accepted = lease.submit(
                identity,
                urgency = if (purpose == BuildPurpose.ARTIFACT || key.detailLevel > 0 ||
                    buildPriority.isSeamCritical(key, SEAM_URGENCY_BAND_CHUNKS)
                ) {
                    TerrainBuildUrgency.NEXT_FRAME
                } else {
                    TerrainBuildUrgency.DEFERRED
                },
                estimate = when (purpose) {
                    BuildPurpose.SOURCE -> SOURCE_BUILD_ESTIMATE
                    BuildPurpose.ARTIFACT -> ARTIFACT_BUILD_ESTIMATE
                },
                cancellation = cancellation,
            ) { _, token -> build(request, token) }
            if (accepted == null) break
            pending[key] = Pending(identity, cancellation, purpose)
            iterator.remove()
            admitted++
            purposePending++
        }
        return admitted
    }

    private fun buildInput(key: TerrainPageKey, purpose: BuildPurpose): BuildInput? {
        if (purpose == BuildPurpose.ARTIFACT) {
            val page = cpuPages[key] ?: return null
            val neighbours = DistantPageHierarchy.cardinalNeighbours(key).mapNotNull(cpuPages::get)
            return MeshInput(page, neighbours)
        }
        if (key.detailLevel == 0) {
            val tile = sourceTiles[key] ?: return null
            val neighbours = DistantPageHierarchy.cardinalNeighbours(key).mapNotNull(sourceTiles::get)
            val neighbourVerticalPages = DistantPageHierarchy.cardinalNeighbours(key)
                .mapNotNull { neighbour -> sourceVerticalPages[neighbour]?.let { neighbour to it } }
                .toMap()
            return BaseInput(
                tile,
                sourceVerticalPages[key],
                neighbours,
                neighbourVerticalPages,
                resolveMaterials(listOf(tile) + neighbours),
            )
        }
        val children = DistantPageHierarchy.children(key).map { child -> cpuPages[child] ?: return null }
        val neighbours = DistantPageHierarchy.cardinalNeighbours(key).mapNotNull(cpuPages::get)
        return DerivedInput(children, neighbours)
    }

    private fun build(request: BuildRequest, cancellation: TerrainCancellationToken): BuildResult {
        cancellation.throwIfCancelled()
        val (page, neighbours) = when (val input = request.input) {
            is BaseInput -> {
                val resolver = DistantCompatibilityMaterialResolver(input.materials::get)
                val page = input.verticalPage?.withBuildIdentity(
                    request.identity.page,
                    requireNotNull(request.identity.sourceDataRevision),
                ) ?: input.tile.toTopOnlyCompatibilityPage(resolver).verticalPage(
                    request.identity.page.worldEpoch, request.originY, requireNotNull(request.identity.sourceDataRevision),
                )
                val neighbours = input.neighbourTiles.map { tile ->
                    val neighbourKey = pageKey(tile.position)
                    input.neighbourVerticalPages[neighbourKey]?.withBuildIdentity(neighbourKey, 0L)
                        ?: tile.toTopOnlyCompatibilityPage(resolver).verticalPage(
                            request.identity.page.worldEpoch, request.originY, sourceRevision = 0L,
                        )
                }
                page to neighbours
            }

            // The contract reducer accepts only footprint-majority intervals,
            // and its mesher leaves unknown boundaries open, so sparse child
            // relief, fluid, or missing data cannot become a coarse slab/wall.
            is DerivedInput -> DistantVerticalPageReducer.reduce(
                request.identity.page,
                input.children,
                requireNotNull(request.identity.sourceDataRevision),
            ) to input.neighbours

            is MeshInput -> input.page to input.neighbours
        }
        cancellation.throwIfCancelled()
        val artifact = if (request.purpose == BuildPurpose.ARTIFACT) {
            DistantPageMesher.mesh(page, DistantPageNeighbourhood(page, neighbours))
        } else {
            null
        }
        cancellation.throwIfCancelled()
        return BuildResult(request, page, artifact, selectionMetadata(page))
    }

    private fun drainCompletions() {
        lease.drain(MAX_COMPLETIONS_PER_FRAME) { completion ->
            val expected = pending[completion.identity.page]
            if (expected?.identity != completion.identity) return@drain
            pending.remove(completion.identity.page)
            when (val outcome = completion.outcome) {
                is TerrainBuildOutcome.Success -> installResult(outcome.value)
                is TerrainBuildOutcome.Cancelled -> Unit
                is TerrainBuildOutcome.Failure -> recordFailure(
                    completion.identity.page,
                    outcome.error,
                    expected.purpose,
                )
            }
        }
    }

    private fun drainUploads() {
        val missing = selectionPublication.missing(gpuPublicationVersions)
        val items = missing.mapIndexedNotNull { index, target ->
            if (failures[target.page]?.phase == TerrainFailurePhase.UPLOAD) return@mapIndexedNotNull null
            val page = cpuArtifacts[target.page]
                ?.takeIf { it.publicationVersion == target.publicationVersion }
                ?: return@mapIndexedNotNull null
            val quads = page.artifact.quads.size.toLong()
            TerrainUploadBudgetItem(
                value = target to page,
                urgency = if (index == 0) TerrainUploadUrgency.SAME_FRAME else TerrainUploadUrgency.NEXT_FRAME,
                sequence = page.identity.prioritySequence,
                estimatedCpuNanos = Math.multiplyExact(quads, ESTIMATED_UPLOAD_NANOS_PER_QUAD),
                estimatedUploadBytes = Math.multiplyExact(quads, BYTES_PER_QUAD),
            )
        }
        val selected = TerrainUploadBudgetScheduler.select(
            items,
            cpuBudgetNanos = MAX_UPLOAD_CPU_NANOS_PER_FRAME,
            uploadBudgetBytes = MAX_UPLOAD_BYTES_PER_FRAME,
        ).selected.take(MAX_UPLOADS_PER_FRAME)
        for (item in selected) {
            val (target, page) = item.value
            try {
                if (upload(target, page)) {
                    failures.clear(target.page)
                } else {
                    failures.record(
                        page = target.page,
                        category = TerrainFailureCategory.PRESSURE,
                        phase = TerrainFailurePhase.UPLOAD,
                        retryEligibility = TerrainRetryEligibility.AfterCapacityChange(storageCapacityRevision),
                    )
                }
            } catch (error: Throwable) {
                failures.recordTransient(
                    page = target.page,
                    phase = TerrainFailurePhase.UPLOAD,
                    monotonicNanos = failureClockNanos(),
                    maximumAttempts = MAX_FAILURES_PER_SOURCE,
                    backoffNanos = FAILURE_RETRY_BACKOFF_NANOS,
                )
                Log.log(LogMessageType.RENDERING, LogLevels.WARN, error)
            }
        }
    }

    private fun installResult(result: BuildResult) {
        val key = result.request.identity.page
        if (!inputIsCurrent(result.request.input)) return
        val currentIdentity = result.request.identity.copy(
            page = result.request.identity.page.copy(worldEpoch = context.session.world.terrainEpoch),
            providerGeneration = providerGeneration,
            layoutGeneration = layoutGeneration,
            materialGeneration = context.shaderPipeline.selection().generation,
            sourceDataRevision = index[key]?.sourceRevision ?: tileSourceRevisions[key],
        )
        if (result.request.identity.mismatch(currentIdentity) != null) return

        when (result.request.purpose) {
            BuildPurpose.SOURCE -> installSourceResult(result)
            BuildPurpose.ARTIFACT -> installArtifactResult(result)
        }
    }

    private fun installSourceResult(result: BuildResult) {
        val key = result.request.identity.page
        if (result.request.input is BaseInput) {
            val sourceOutcome = index.publishSourceMutation(
                key,
                result.page.sourceRevision,
                result.page.completeness,
                de.bixilon.minosoft.terrain.distant.DistantSemanticDigest(1, "SHA-256", result.page.semanticDigest),
            )
            when (sourceOutcome.status) {
                DistantSourceMutationStatus.CAPACITY_REJECTED,
                DistantSourceMutationStatus.CONFLICT,
                DistantSourceMutationStatus.STALE,
                -> return

                DistantSourceMutationStatus.PUBLISHED ->
                    clearFailures(sourceOutcome.sourceChangedPages + sourceOutcome.dirtiedPages)

                DistantSourceMutationStatus.UNCHANGED -> Unit
            }
        }
        cpuPages[key] = result.page
        cpuSelectionMetadata[key] = result.selectionMetadata
        invalidateSelectionData()
        failures.clear(key)
        failurePurposes.remove(key)
    }

    private fun installArtifactResult(result: BuildResult) {
        val key = result.request.identity.page
        val artifact = checkNotNull(result.artifact) { "Distant artifact build completed without geometry" }
        val hierarchyPage = index[key] ?: return
        val publication = index.publishRender(key, hierarchyPage.sourceRevision, hierarchyPage.dirtyRevision)
        if (publication !is DistantRenderPublication.Published) return
        val previousArtifact = cpuArtifacts[key]
        cpuArtifacts[key] = CpuPage(
            source = result.page,
            artifact = artifact,
            identity = result.request.identity,
            publicationVersion = publication.page.renderRevision,
        )
        invalidateSelectionData()
        if (result.page.key.detailLevel > 0 && previousArtifact?.source?.semanticDigest != result.page.semanticDigest) {
            index.invalidateRenderPages(DistantPageHierarchy.cardinalNeighbours(key))
        }
        failures.clear(key)
        failurePurposes.remove(key)
    }

    private fun installSourcePage(page: DistantVerticalPage, invalidateSelection: Boolean = true): Boolean {
        val key = page.key
        val sourceOutcome = index.publishSourceMutation(
            key,
            page.sourceRevision,
            page.completeness,
            de.bixilon.minosoft.terrain.distant.DistantSemanticDigest(1, "SHA-256", page.semanticDigest),
        )
        when (sourceOutcome.status) {
            DistantSourceMutationStatus.CAPACITY_REJECTED,
            DistantSourceMutationStatus.CONFLICT,
            DistantSourceMutationStatus.STALE,
            -> return false

            DistantSourceMutationStatus.PUBLISHED ->
                clearFailures(sourceOutcome.sourceChangedPages + sourceOutcome.dirtiedPages)

            DistantSourceMutationStatus.UNCHANGED -> Unit
        }
        cpuPages[key] = page
        cpuSelectionMetadata[key] = selectionMetadata(page)
        if (invalidateSelection) invalidateSelectionData()
        return true
    }

    private fun recordFailure(key: TerrainPageKey, error: Throwable, purpose: BuildPurpose) {
        failures.recordTransient(
            page = key,
            phase = TerrainFailurePhase.BUILD,
            monotonicNanos = failureClockNanos(),
            maximumAttempts = MAX_FAILURES_PER_SOURCE,
            backoffNanos = FAILURE_RETRY_BACKOFF_NANOS,
        )
        failurePurposes[key] = purpose
        Log.log(LogMessageType.RENDERING, LogLevels.WARN, error)
    }

    private fun retryFailures(monotonicNanos: Long) {
        val eligible = failures.releaseEligible(TerrainRetrySignal.ClockAdvanced(monotonicNanos))
        for (key in eligible) {
            when (failurePurposes.remove(key)) {
                BuildPurpose.SOURCE -> needsSourceBuild += key
                BuildPurpose.ARTIFACT -> needsArtifactBuild += key
                null -> Unit
            }
        }
    }

    private fun retryCapacityFailures() {
        failures.releaseEligible(TerrainRetrySignal.CapacityChanged(storageCapacityRevision))
    }

    private fun clearFailures(pages: Collection<TerrainPageKey>) {
        for (page in pages) {
            failures.clear(page)
            failurePurposes.remove(page)
        }
    }

    private fun failureClockNanos(): Long = (System.nanoTime() - failureClockOrigin).coerceAtLeast(0L)

    private fun invalidateChangedPipelineGeneration() {
        val nextShader = context.shaderPipeline.selection().generation
        if (nextShader == shaderGeneration) return
        shaderGeneration = nextShader
        pending.values.forEach { it.cancellation.cancel() }
        pending.clear()
        needsSourceBuild.clear()
        needsArtifactBuild.clear()
        failures.clear()
        failurePurposes.clear()
        index.invalidateRenderPages(index.snapshot().pages.keys)
            .filter(cpuPages::containsKey)
            .forEach(needsArtifactBuild::add)
    }

    private fun inputIsCurrent(input: BuildInput): Boolean = when (input) {
        is BaseInput -> sourceTiles[pageKey(input.tile.position)] === input.tile &&
            sourceVerticalPages[pageKey(input.tile.position)] === input.verticalPage &&
            input.neighbourVerticalPages.all { (key, page) -> sourceVerticalPages[key] === page }
        is DerivedInput -> input.children.all { cpuPages[it.key] === it } &&
            input.neighbours.all { cpuPages[it.key] === it }
        is MeshInput -> cpuPages[input.page.key] === input.page &&
            input.neighbours.all { cpuPages[it.key] === it }
    }

    private fun DistantVerticalPage.withBuildIdentity(
        buildKey: TerrainPageKey,
        revision: Long,
    ) = DistantVerticalPage(
        key = buildKey,
        width = width,
        originY = originY,
        sourceRevision = revision,
        completeness = completeness,
        columns = columns,
    )

    private fun upload(target: TerrainResidencyTarget, page: CpuPage): Boolean {
        if (gpuPages[target.page]?.publicationVersion == target.publicationVersion) return true
        val key = TerrainRegionKey.containing(target.page, REGION_EXTENT)
        val hasSolid = page.artifact.quads.any { it.fluid == null }
        val hasWater = page.artifact.quads.any { it.fluid != null }
        val artifact = DistantTerrainRegionArtifactEncoder.encode(
            identity = page.identity,
            page = page.source,
            artifact = page.artifact,
            extent = REGION_EXTENT,
        )
        if (artifact.streams.isEmpty()) {
            artifact.use {
                replaceGpuPage(
                    target.page,
                    GpuPage(
                        publicationVersion = target.publicationVersion,
                        digest = artifact.digest.encodedValue,
                        materialGeneration = artifact.identity.materialGeneration,
                        region = null,
                        hasSolid = hasSolid,
                        hasWater = hasWater,
                    ),
                )
            }
            return true
        }
        val shards = regions.getOrPut(key) { mutableListOf() }
        var created = false
        val region = shards.firstOrNull { it.storage.canPublish(artifact) } ?: run {
            if (regionCount() >= MAX_REGIONS) {
                if (shards.isEmpty()) regions.remove(key)
                artifact.close()
                return false
            }
            created = true
            createRegion(key, shards.size).also(shards::add)
        }
        var publicationFailure: Throwable? = null
        val published = try {
            checkNotNull(region.storage.publish(artifact)) {
                "Distant region admission changed before publication"
            }
        } catch (error: Throwable) {
            publicationFailure = error
            if (created) {
                shards.remove(region)
                if (shards.isEmpty()) regions.remove(key)
                try {
                    region.device.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        } finally {
            try {
                artifact.close()
            } catch (cleanup: Throwable) {
                publicationFailure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
        replaceGpuPage(
            target.page,
            GpuPage(
                publicationVersion = target.publicationVersion,
                digest = published.digest.encodedValue,
                materialGeneration = published.materialGeneration,
                region = region,
                hasSolid = hasSolid,
                hasWater = hasWater,
            ),
        )
        return true
    }

    private fun replaceGpuPage(key: TerrainPageKey, page: GpuPage) {
        val previous = gpuPages.put(key, page)
        gpuPublicationVersions[key] = page.publicationVersion
        gpuRevision = Math.addExact(gpuRevision, 1L)
        if (previous == null) return
        if (previous.region !== page.region) previous.region?.storage?.remove(key)
    }

    private fun regionCount(): Int = regions.values.sumOf { it.size }

    private fun updateSelections(camera: ChunkPosition, seamDistanceChunks: Float) {
        val eye = context.camera.view.view.eyePosition
        val verticalFieldOfView = context.session.profiles.rendering.camera.fov.toDouble()
        val viewportHeight = context.window.size.y.coerceAtLeast(1)
        val nearCoverage = nativeOwnership.lifecycle
        val input = SelectionInput(
            cameraChunkX = camera.x,
            cameraChunkZ = camera.z,
            eyeBlockX = floor(eye.x).toLong(),
            eyeBlockY = floor(eye.y).toLong(),
            eyeBlockZ = floor(eye.z).toLong(),
            verticalFieldOfViewBits = verticalFieldOfView.toBits(),
            viewportHeightPixels = viewportHeight,
            seamDistanceBits = seamDistanceChunks.toRawBits(),
            dataRevision = selectionDataRevision,
            nearCoverageRevision = nearCoverage?.revision ?: 0L,
        )
        val previousInput = lastSelectionInput
        if (input == previousInput) return
        if (previousInput != null && input.copy(dataRevision = previousInput.dataRevision) == previousInput &&
            context.frameNumber - lastSelectionFrame < DATA_RESELECTION_INTERVAL_FRAMES
        ) {
            return
        }
        val selectionCamera = DistantSelectionCamera(
            eye.x,
            eye.y,
            eye.z,
            verticalFieldOfViewDegrees = verticalFieldOfView,
        )
        val selected = index.readView { view ->
            val roots = coarsestAvailableRoots(view.pages.keys)
            if (roots.isEmpty()) return@readView null
            val changeBudget = max(MAX_SELECTION_CHANGES, view.pages.size * 2)
            val visitBudget = max(MAX_NODE_VISITS, view.pages.size * 2)
            fun request(quality: Double) = DistantPageSelectionRequest(
                camera = selectionCamera,
                viewportHeightPixels = viewportHeight,
                basePageSizeBlocks = 16,
                roots = roots,
                refineErrorPixels = REFINE_ERROR_PIXELS,
                coarsenErrorPixels = COARSEN_ERROR_PIXELS,
                qualityScale = quality,
                seamPressure = 1.0,
                maximumNodeVisits = visitBudget,
                maximumSelectionChanges = changeBudget,
                requireCompleteChildren = false,
            )
            val mainBudget = max(MAX_MAIN_SELECTION_PAGES, roots.size)
            val shadowBudget = max(MAX_SHADOW_SELECTION_PAGES, roots.size)
            mainSelector.select(
                view,
                cpuSelectionMetadata,
                request(quality = 1.0),
                maximumPages = mainBudget,
                nearCoverage = nearCoverage,
            ).pages to shadowSelector.select(
                view,
                cpuSelectionMetadata,
                request(quality = SHADOW_QUALITY),
                maximumPages = shadowBudget,
                nearCoverage = nearCoverage,
            ).pages
        }
        if (selected == null) {
            selectionPublication.desire(MAIN_VIEW, emptyList())
            selectionPublication.desire(SHADOW_VIEW, emptyList())
            lastSelectionInput = input
            lastSelectionFrame = context.frameNumber
            return
        }
        val (main, shadow) = selected
        val desiredMain = visiblePages(main, camera, config.renderDistanceChunks)
        val desiredShadow = visiblePages(
            shadow,
            camera,
            minOf(config.renderDistanceChunks, SHADOW_DISTANCE_CHUNKS),
        )
        val demandedArtifacts = (desiredMain.asSequence() + desiredShadow.asSequence()).toSet()
        needsArtifactBuild.retainAll(demandedArtifacts)
        val demandedSources = linkedSetOf<TerrainPageKey>()
        demandedArtifacts.forEach { collectSourceDependencies(it, demandedSources) }
        needsSourceBuild.removeIf { it.detailLevel > 0 && it !in demandedSources }
        needsSourceBuild += demandedSources
        demandedArtifacts.forEach(::queueArtifactBuild)
        desireWhenArtifactsExist(MAIN_VIEW, desiredMain)
        desireWhenArtifactsExist(SHADOW_VIEW, desiredShadow)
        lastSelectionInput = input
        lastSelectionFrame = context.frameNumber
    }

    private fun queueArtifactBuild(key: TerrainPageKey) {
        val page = index[key] ?: return
        if (key !in cpuPages) return
        if (page.dirty || key !in cpuArtifacts) needsArtifactBuild += key
    }

    private fun collectSourceDependencies(key: TerrainPageKey, output: MutableSet<TerrainPageKey>) {
        if (key in cpuPages || !output.add(key) || key.detailLevel == 0) return
        DistantPageHierarchy.children(key).forEach { child -> collectSourceDependencies(child, output) }
    }

    private fun desireWhenArtifactsExist(view: TerrainViewKey, pages: List<TerrainPageKey>) {
        val targets = pages.mapNotNull(::residencyTarget)
        selectionPublication.desireAvailable(view, pages, targets)
    }

    private fun invalidateSelectionData() {
        selectionDataRevision = Math.addExact(selectionDataRevision, 1L)
    }

    private fun coarsestAvailableRoots(available: Set<TerrainPageKey>): List<TerrainPageKey> {
        val cover = available.filterTo(linkedSetOf()) { it.detailLevel == 0 }
        for (detail in 1..index.maximumDetailLevel) {
            val parents = cover.asSequence()
                .filter { it.detailLevel == detail - 1 }
                .map(DistantPageHierarchy::parent)
                .distinct()
                .filter(available::contains)
                .filter { parent -> DistantPageHierarchy.children(parent).all(cover::contains) }
                .sortedWith(DistantPageHierarchy.order)
                .toList()
            for (parent in parents) {
                cover.removeAll(DistantPageHierarchy.children(parent).toSet())
                cover += parent
            }
        }
        return cover.sortedWith(DistantPageHierarchy.order)
    }

    private fun residencyTarget(key: TerrainPageKey): TerrainResidencyTarget? {
        val page = index[key] ?: return null
        if (!page.hasActiveArtifact) return null
        val artifact = cpuArtifacts[key] ?: return null
        if (artifact.publicationVersion != page.renderRevision) return null
        return TerrainResidencyTarget(key, page.renderRevision)
    }

    private fun promoteSelections() {
        selectionPublication.promote(MAIN_VIEW, gpuPublicationVersions)
        selectionPublication.promote(SHADOW_VIEW, gpuPublicationVersions)
        mainSelection = selectionPublication.active(MAIN_VIEW)
        shadowSelection = selectionPublication.active(SHADOW_VIEW)
        val coverage = nativeOwnership.lifecycle
        val mainCoverage = distantCoverageDrawSelection(mainSelection, coverage, COVERAGE_TRANSITION_POLICY)
        val shadowCoverage = distantCoverageDrawSelection(shadowSelection, coverage, COVERAGE_TRANSITION_POLICY)
        mainDrawSelection = mainCoverage.drawable
        shadowDrawSelection = shadowCoverage.drawable
        mainMaskedPages = mainCoverage.maskedPages
        shadowMaskedPages = shadowCoverage.maskedPages
        mainDrawPlan = drawPlan(mainDrawSelection, mainDrawPlan)
        shadowDrawPlan = drawPlan(shadowDrawSelection, shadowDrawPlan)
    }

    private fun drawPlan(selection: List<TerrainPageKey>, previous: DrawPlan): DrawPlan {
        if (previous.gpuRevision == gpuRevision && previous.selection == selection) return previous
        data class GroupKey(val region: Region, val materialGeneration: Long)
        val grouped = LinkedHashMap<GroupKey, ArrayList<TerrainPageKey>>()
        var hasSolid = false
        var hasWater = false
        for (key in selection) {
            val page = gpuPages[key] ?: continue
            hasSolid = hasSolid || page.hasSolid
            hasWater = hasWater || page.hasWater
            val region = page.region ?: continue
            grouped.getOrPut(GroupKey(region, page.materialGeneration), ::arrayListOf) += key
        }
        return DrawPlan(
            selection = selection,
            gpuRevision = gpuRevision,
            groups = grouped.map { (key, pages) ->
                DrawGroup(key.region, key.materialGeneration, java.util.List.copyOf(pages))
            },
            hasSolid = hasSolid,
            hasWater = hasWater,
        )
    }

    private fun updateEffectiveRenderDistance(camera: ChunkPosition, seamDistanceChunks: Float) {
        val seamBits = seamDistanceChunks.toRawBits()
        if (
            camera == coverageDistanceCamera && seamBits == coverageDistanceSeamBits &&
            mainSelection == coverageDistanceSelection
        ) return
        coverageDistanceCamera = camera
        coverageDistanceSeamBits = seamBits
        coverageDistanceSelection = mainSelection
        effectiveRenderDistanceChunks = distantCoverageRenderDistanceChunks(
            pages = mainSelection,
            camera = camera,
            seamDistanceChunks = seamDistanceChunks,
            configuredDistanceChunks = config.renderDistanceChunks,
        )
    }

    private fun evictUnselectedPages() {
        val retained = selectionPublication.retainedPages()
        val iterator = gpuPages.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val page = iterator.next().key
            if (page in retained) continue
            gpuPages[page]?.region?.storage?.remove(page)
            iterator.remove()
            gpuPublicationVersions.remove(page)
            gpuRevision = Math.addExact(gpuRevision, 1L)
            changed = true
        }
        if (changed) frameSubmission.clearCache()
    }

    private fun prepareStorageFrame() {
        frameSubmission.beginFrame()
        completion.collect()
        val iterator = regions.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val shards = iterator.next().value
            val shardIterator = shards.iterator()
            while (shardIterator.hasNext()) {
                val region = shardIterator.next()
                if (region.storage.collectRetired() > 0) capacityChanged()
                val metrics = region.storage.metrics()
                if (metrics.activePages != 0 || metrics.retiredPages != 0) continue
                region.device.close()
                shardIterator.remove()
                capacityChanged()
                changed = true
            }
            if (shards.isEmpty()) iterator.remove()
        }
        if (changed) frameSubmission.clearCache()
    }

    private fun createRegion(key: TerrainRegionKey, shard: Int): Region {
        val device = OpenGlTerrainRegionDevice(
            system = system,
            struct = DistantTerrainMeshStruct,
            deviceRuntime = deviceRuntime,
            layoutGeneration = layoutGeneration,
            vertexCapacityBytes = VERTEX_CAPACITY_BYTES,
            indexCapacityBytes = INDEX_CAPACITY_BYTES,
            staging = staging,
        )
        return Region(key, shard, device, TerrainRegionStorage(key, REGION_EXTENT, device, completion))
    }

    private fun capacityChanged() {
        storageCapacityRevision = Math.addExact(storageCapacityRevision, 1L)
    }

    private fun selectionMetadata(page: DistantVerticalPage): DistantPageSelectionMetadata {
        var minimumY = Int.MAX_VALUE
        var maximumY = Int.MIN_VALUE
        for (column in page.columns) {
            for (run in column.runs) {
                minimumY = minOf(minimumY, run.minimumY)
                maximumY = maxOf(maximumY, run.maximumYExclusive)
            }
        }
        if (minimumY == Int.MAX_VALUE) minimumY = page.originY
        if (maximumY == Int.MIN_VALUE) maximumY = Math.addExact(page.originY, 1)
        return DistantPageSelectionMetadata(
            geometricErrorBlocks = max(
                page.cellSizeBlocks.toDouble(),
                (maximumY.toLong() - minimumY).toDouble() / page.width,
            ),
            minimumY = minimumY,
            maximumYExclusive = maximumY,
        )
    }

    private fun visiblePages(
        pages: List<TerrainPageKey>,
        camera: ChunkPosition,
        maximumDistanceChunks: Int,
    ): List<TerrainPageKey> {
        val maximumSquared = maximumDistanceChunks.toDouble() * maximumDistanceChunks
        return pages.filter { key ->
            val span = 1L shl key.detailLevel
            val centerX = key.x * span + span * 0.5
            val centerZ = key.z * span + span * 0.5
            val dx = centerX - camera.x
            val dz = centerZ - camera.z
            val squared = dx * dx + dz * dz
            squared <= maximumSquared
        }
    }

    private fun resolveMaterials(tiles: List<DistantLodTile>): Map<ResourceLocation, DistantCompatibilityMaterial?> {
        val identifiers = linkedSetOf<ResourceLocation>()
        for (tile in tiles) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    tile[x, z].material?.let(identifiers::add)
                    tile[x, z].solidMaterial?.let(identifiers::add)
                }
            }
        }
        return identifiers.associateWith(::resolveMaterial)
    }

    private fun resolveMaterial(identifier: ResourceLocation): DistantCompatibilityMaterial? {
        if (identifier in AIR_IDENTIFIERS) return null
        val block = context.session.registries.block[identifier]
        val semantic = TerrainSemanticMaterialId(identifier.toString())
        if (block == null) return DistantCompatibilityMaterial(semantic, opaque = false)
        val state = block.states.default
        val fluid = (block as? FluidHolder)?.fluid?.let { fluid ->
            DistantFluidSample(
                material = TerrainSemanticMaterialId(fluid.identifier.toString()),
                level = 0,
                classification = fluid.identifier.toString(),
            )
        }
        return DistantCompatibilityMaterial(
            semanticMaterial = semantic,
            opaque = BlockStateFlags.FULL_OPAQUE in state.flags,
            fluid = fluid,
        )
    }

    private fun buildIdentity(page: TerrainPageKey, sequence: Long, sourceRevision: Long): TerrainBuildIdentity =
        TerrainBuildIdentity(
            page = page,
            requestRevision = sequence,
            capturedModelRevision = sourceRevision,
            providerGeneration = providerGeneration,
            layoutGeneration = layoutGeneration,
            materialGeneration = context.shaderPipeline.selection().generation,
            coverageGeneration = 0L,
            prioritySequence = sequence,
            sourceDataRevision = sourceRevision,
        )

    private fun pageKey(position: ChunkPosition) = TerrainPageKey(
        TerrainDomain.DISTANT,
        detailLevel = 0,
        x = position.x.toLong(),
        y = 0L,
        z = position.z.toLong(),
        worldEpoch = worldEpoch,
    )

    private fun resetWorld(epoch: Long) {
        pending.values.forEach { it.cancellation.cancel() }
        pending.clear()
        pendingSourceIngest.clear()
        needsSourceBuild.clear()
        needsArtifactBuild.clear()
        sourceTiles.clear()
        sourceVerticalPages.clear()
        sourceKinds.clear()
        tileSourceRevisions.clear()
        failures.clear()
        failurePurposes.clear()
        cpuPages.clear()
        cpuArtifacts.clear()
        cpuSelectionMetadata.clear()
        clearGpuResources()
        sourceRevision = Long.MIN_VALUE
        worldEpoch = epoch
        shaderGeneration = context.shaderPipeline.selection().generation
        index = createIndex(epoch)
        mainSelector = DistantPageSelector(epoch)
        shadowSelector = DistantPageSelector(epoch)
        selectionDataRevision = 0L
        lastSelectionInput = null
        lastSelectionFrame = 0L
        resetSelectionAfterIngest = false
    }

    private fun clearContent() {
        pending.values.forEach { it.cancellation.cancel() }
        pending.clear()
        pendingSourceIngest.clear()
        needsSourceBuild.clear()
        needsArtifactBuild.clear()
        sourceTiles.clear()
        sourceVerticalPages.clear()
        sourceKinds.clear()
        tileSourceRevisions.clear()
        failures.clear()
        failurePurposes.clear()
        cpuPages.clear()
        cpuArtifacts.clear()
        cpuSelectionMetadata.clear()
        clearGpuResources()
        sourceRevision = Long.MIN_VALUE
        index = createIndex(worldEpoch)
        mainSelector = DistantPageSelector(worldEpoch)
        shadowSelector = DistantPageSelector(worldEpoch)
        selectionDataRevision = 0L
        lastSelectionInput = null
        lastSelectionFrame = 0L
        resetSelectionAfterIngest = false
    }

    private fun publishDiagnostics() {
        val artifacts = gpuPages.keys.mapNotNull { cpuArtifacts[it]?.artifact }
        val hierarchyDiagnostics = diagnostics()
        source.publishDiagnostics(
            DistantLodRenderDiagnostics(
                revision = sourceRevision.coerceAtLeast(0L),
                nativeOwnershipRevision = nativeOwnership.revision,
                tileCount = sourceTiles.size,
                renderReadyNativeChunks = nativeOwnership.chunks.size,
                excludedTiles = 0,
                cellCount = artifacts.sumOf(DistantPageMeshArtifact::mergedFaceCount),
                terrainCells = artifacts.sumOf { it.mergedFaceCount - it.fluidFaceCount },
                waterCells = artifacts.sumOf(DistantPageMeshArtifact::fluidFaceCount),
                waterBedCells = 0,
                adaptiveCells = artifacts.size,
                skirtSegments = artifacts.sumOf(DistantPageMeshArtifact::fallbackFaceCount),
                maximumSkirtDrop = if (artifacts.any { it.fallbackFaceCount > 0 }) 32 else 0,
                cellSizes = artifacts.groupingBy { cpuArtifacts[it.page]?.source?.cellSizeBlocks ?: 1 }.eachCount(),
                sources = sourceKinds.values.groupingBy { it }.eachCount(),
                cells = emptyList(),
                hierarchy = hierarchyDiagnostics,
            ),
        )
    }

    internal fun diagnostics(): DistantHierarchyRenderDiagnostics {
        val draws = frameSubmission.metrics()
        val hierarchySnapshot = index.snapshot()
        val queuedBuildPages = Math.addExact(
            pendingSourceIngest.size,
            Math.addExact(needsSourceBuild.size, needsArtifactBuild.size),
        )
        val pendingUploadPages = selectionPublication.missing(gpuPublicationVersions).size
        val regionMetrics = regions.values.flatten().map { it to it.storage.metrics() }
        val storageMetrics = regionMetrics.map { it.second }
        return DistantHierarchyRenderDiagnostics(
            configuredRenderDistanceChunks = config.renderDistanceChunks,
            effectiveRenderDistanceChunks = effectiveRenderDistanceChunks,
            deviceCapacityBytes = Math.addExact(
                Math.multiplyExact(
                    MAX_REGIONS.toLong(),
                    Math.addExact(VERTEX_CAPACITY_BYTES, INDEX_CAPACITY_BYTES).toLong(),
                ),
                staging.capacityBytes.toLong(),
            ),
            storageHighWaterBytes = storageMetrics.sumOf {
                Math.addExact(it.vertexHighWaterBytes.toLong(), it.indexHighWaterBytes.toLong())
            },
            stagingCapacityBytes = staging.capacityBytes,
            storagePublicationGeneration = storageMetrics.fold(0L) { total, metrics ->
                Math.addExact(total, metrics.publicationGeneration)
            },
            selectionPublication = selectionPublication.snapshot(gpuPublicationVersions),
            indexRevision = hierarchySnapshot.revision,
            sourcePublicationRevision = hierarchySnapshot.sourcePublicationRevision,
            dirtyRevision = hierarchySnapshot.dirtyRevision,
            indexedPages = hierarchySnapshot.pages.size,
            dirtyPages = hierarchySnapshot.dirtyPages.size,
            cpuPages = cpuPages.size,
            gpuPages = gpuPages.size,
            pendingPages = pending.size,
            queuedPages = Math.addExact(queuedBuildPages, pendingUploadPages),
            queuedBuildPages = queuedBuildPages,
            pendingUploadPages = pendingUploadPages,
            mainSelectedPages = mainSelection.size,
            shadowSelectedPages = shadowSelection.size,
            mainMaskedPages = mainMaskedPages,
            shadowMaskedPages = shadowMaskedPages,
            coverageRevision = nativeOwnership.lifecycle?.revision ?: 0L,
            coverageLifecycleRevision = nativeOwnership.lifecycle?.lifecycleRevision ?: 0L,
            coverageTransitionFrames = COVERAGE_TRANSITION_POLICY.durationFrames,
            regions = regionCount(),
            residentBytes = storageMetrics.sumOf { it.residentBytes },
            retiredBytes = storageMetrics.sumOf { it.retiredBytes },
            allocationFailures = storageMetrics.sumOf { it.allocationFailures },
            uploadFailures = storageMetrics.sumOf { it.uploadFailures },
            cpuLeaseCount = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.cpuLeaseCount)
            },
            pendingSubmissionCount = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.pendingSubmissionCount)
            },
            failedSubmissionCount = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.failedSubmissionCount)
            },
            invalidatedSubmissionCount = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.invalidatedSubmissionCount)
            },
            retiredCpuLeasedPages = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredCpuLeasedPages)
            },
            retiredPendingSubmissionPages = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredPendingSubmissionPages)
            },
            retiredFailedSubmissionPages = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredFailedSubmissionPages)
            },
            retiredDeviceInvalidatedPages = storageMetrics.fold(0) { total, metrics ->
                Math.addExact(total, metrics.retiredDeviceInvalidatedPages)
            },
            deviceInvalidations = storageMetrics.sumOf { it.deviceInvalidations },
            drawBatches = draws.drawBatches,
            drawCommands = draws.drawCommands,
            drawVertices = draws.drawVertices,
            pendingSubmissionFences = completion.pendingFences,
            detailCounts = gpuPages.keys.groupingBy(TerrainPageKey::detailLevel).eachCount(),
            regionStates = regionMetrics.asSequence()
                .sortedWith(compareBy({ it.first.key.detailLevel }, { it.first.key.z }, { it.first.key.x }, { it.first.shard }))
                .map { (region, metrics) ->
                    val key = region.key
                    DistantHierarchyRegionDiagnostic(
                        detailLevel = key.detailLevel,
                        x = key.x,
                        z = key.z,
                        shard = region.shard,
                        activePages = metrics.activePages,
                        retiredPages = metrics.retiredPages,
                        residentBytes = metrics.residentBytes,
                        vertexAllocatedBytes = metrics.vertexAllocatedBytes,
                        indexAllocatedBytes = metrics.indexAllocatedBytes,
                        vertexHighWaterBytes = metrics.vertexHighWaterBytes,
                        indexHighWaterBytes = metrics.indexHighWaterBytes,
                        vertexFragmentation = metrics.vertexFragmentation,
                        indexFragmentation = metrics.indexFragmentation,
                        allocationFailures = metrics.allocationFailures,
                        uploadFailures = metrics.uploadFailures,
                    )
                }.toList(),
            pages = hierarchySnapshot.pages.values.asSequence()
                .sortedWith(compareBy({ it.key.detailLevel }, { it.key.z }, { it.key.x }))
                .take(MAX_DIAGNOSTIC_PAGES)
                .map { page ->
                    val artifact = cpuArtifacts[page.key]?.artifact
                    DistantHierarchyPageDiagnostic(
                        detailLevel = page.key.detailLevel,
                        x = page.key.x,
                        z = page.key.z,
                        sourceRevision = page.sourceRevision,
                        dirtyRevision = page.dirtyRevision,
                        renderRevision = page.renderRevision,
                        dirty = page.dirty,
                        derived = page.derived,
                        buildState = page.buildState,
                        mergedFaces = artifact?.mergedFaceCount ?: 0,
                        fallbackFaces = artifact?.fallbackFaceCount ?: 0,
                    )
                }.toList(),
        )
    }

    internal fun diagnosticPages(
        query: TerrainPageQuery,
        afterPage: TerrainPageKey?,
    ): List<TerrainPageDiagnosticSnapshot> {
        require(query.selector.domain == TerrainDomain.DISTANT) {
            "Distant hierarchy diagnostics require a distant page selector"
        }
        val limit = Math.addExact(query.maximumCount, 1)
        return index.snapshot().pages.values.asSequence()
            .filter { query.selector.matches(it.key) }
            .filter { afterPage == null || DIAGNOSTIC_PAGE_ORDER.compare(it.key, afterPage) > 0 }
            .take(limit)
            .map { hierarchyPage ->
                val key = hierarchyPage.key
                val cpu = cpuArtifacts[key]
                val gpu = gpuPages[key]
                val published = gpu?.region?.storage?.get(key)
                val source = if (hierarchyPage.derived) {
                    "derived"
                } else if (key.detailLevel == 0 && key.x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                    key.z in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                ) {
                    sourceKinds[ChunkPosition(key.x.toInt(), key.z.toInt())]?.wireName
                } else {
                    null
                }
                TerrainPageDiagnosticSnapshot(
                    page = key,
                    coverageState = when {
                        key in gpuPages -> TerrainCoverageState.READY
                        cpu != null || hierarchyPage.buildState == DistantPageBuildState.READY ->
                            TerrainCoverageState.UPLOAD_PENDING
                        key in pending || hierarchyPage.buildState == DistantPageBuildState.BUILDING ->
                            TerrainCoverageState.BUILDING
                        hierarchyPage.buildState == DistantPageBuildState.REQUESTED || hierarchyPage.dirty ->
                            TerrainCoverageState.REQUESTED
                        else -> TerrainCoverageState.ABSENT
                    },
                    buildIdentity = cpu?.identity ?: pending[key]?.identity,
                    providerId = DistantTerrainInterop.PROVIDER_ID,
                    failure = failures[key],
                    source = source,
                    revisions = TerrainPageRevisionDiagnosticSnapshot(
                        sourceRevision = hierarchyPage.sourceRevision,
                        dirtyRevision = hierarchyPage.dirtyRevision,
                        renderRevision = hierarchyPage.renderRevision,
                        publicationRevision = gpu?.publicationVersion,
                    ),
                    ranges = published?.streams.orEmpty().map { stream ->
                        TerrainPageRangeDiagnosticSnapshot(
                            partitionId = stream.partitionId,
                            vertexOffsetBytes = stream.vertexRange.offset,
                            vertexLengthBytes = stream.vertexRange.length,
                            indexOffsetBytes = stream.indexRange.offset,
                            indexLengthBytes = stream.indexRange.length,
                        )
                    },
                    visibility = listOf(
                        TerrainPageVisibilityDiagnosticSnapshot(
                            viewId = MAIN_VIEW.value,
                            selected = key in mainSelection,
                            masked = key in mainSelection && key !in mainDrawSelection,
                        ),
                        TerrainPageVisibilityDiagnosticSnapshot(
                            viewId = SHADOW_VIEW.value,
                            selected = key in shadowSelection,
                            masked = key in shadowSelection && key !in shadowDrawSelection,
                        ),
                    ),
                    artifactDigest = gpu?.digest ?: cpu?.artifact?.digest,
                )
            }
            .toList()
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { it.cancellation.cancel() }
        pending.clear()
        pendingSourceIngest.clear()
        needsSourceBuild.clear()
        needsArtifactBuild.clear()
        var failure: Throwable? = null
        try {
            lease.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            clearGpuResources()
        } catch (error: Throwable) {
            failure = combineCleanupFailure(failure, error)
        }
        try {
            staging.close()
        } catch (error: Throwable) {
            failure = combineCleanupFailure(failure, error)
        } finally {
            cpuPages.clear()
            cpuArtifacts.clear()
            cpuSelectionMetadata.clear()
        }
        if (failure != null) throw failure
    }

    private fun clearGpuResources() {
        var failure: Throwable? = null
        if (Thread.currentThread() === context.thread && Rendering.currentContext === context) {
            if (frameSubmission.hasSubmittedBatches) {
                try {
                    gl { glFinish() }
                } catch (error: Throwable) {
                    failure = error
                }
            }
            try {
                frameSubmission.closeSubmittedBatches()
            } catch (error: Throwable) {
                failure = combineCleanupFailure(failure, error)
            }
            for (region in regions.values.flatten()) {
                try {
                    region.device.close()
                } catch (error: Throwable) {
                    failure = combineCleanupFailure(failure, error)
                }
            }
            try {
                completion.close()
            } catch (error: Throwable) {
                failure = combineCleanupFailure(failure, error)
            }
        } else {
            // Context destruction owns device objects on shutdown. No GL call,
            // including lease retirement polling, is legal on this path.
            frameSubmission.abandon()
        }
        regions.clear()
        gpuPages.clear()
        gpuPublicationVersions.clear()
        selectionPublication.clear()
        mainSelection = emptyList()
        shadowSelection = emptyList()
        mainDrawSelection = emptyList()
        shadowDrawSelection = emptyList()
        mainDrawPlan = DrawPlan.EMPTY
        shadowDrawPlan = DrawPlan.EMPTY
        gpuRevision = 0L
        coverageDistanceSelection = emptyList()
        coverageDistanceCamera = null
        coverageDistanceSeamBits = 0
        effectiveRenderDistanceChunks = config.renderDistanceChunks
        frameSubmission.clearCache()
        frameSubmission.resetMetrics()
        diagnosticFrame = 0
        storageCapacityRevision = 0L
        if (failure != null) throw failure
    }

    private fun combineCleanupFailure(primary: Throwable?, next: Throwable): Throwable {
        if (primary == null) return next
        primary.addSuppressed(next)
        return primary
    }

    private fun TerrainCancellationToken.throwIfCancelled() {
        if (closed || isCancelled) throw java.util.concurrent.CancellationException("Distant page build cancelled")
    }

    private fun createIndex(epoch: Long): DistantPageHierarchyIndex {
        val diameter = Math.multiplyExact(config.renderDistanceChunks, 2).coerceAtLeast(1)
        val maximumDetail = ceil(log2(diameter.toDouble())).toInt()
            .coerceIn(0, DistantPageHierarchy.MAXIMUM_DETAIL_LEVEL)
        val capacity = Math.addExact(
            Math.multiplyExact(config.maximumTiles, Math.addExact(maximumDetail, 1)),
            64,
        )
        return DistantPageHierarchyIndex(epoch, maximumDetail, capacity)
    }

    private fun failureCapacity(): Int = Math.addExact(
        Math.multiplyExact(config.maximumTiles, DistantPageHierarchy.MAXIMUM_DETAIL_LEVEL + 1),
        64,
    )

    companion object {
        private const val MAX_SOURCE_SUBMISSIONS_PER_FRAME = 32
        private const val MAX_ARTIFACT_SUBMISSIONS_PER_FRAME = 6
        private const val MAX_SOURCE_INGEST_PER_FRAME = 128
        private const val MAX_PENDING_SOURCE_PAGES = 48
        private const val MAX_PENDING_ARTIFACT_PAGES = 6
        private const val MAX_PENDING_PAGES = MAX_PENDING_SOURCE_PAGES + MAX_PENDING_ARTIFACT_PAGES
        private val SOURCE_BUILD_ESTIMATE = TerrainBuildEstimate(
            cpuNanos = 25_000_000L,
            outputBytes = 512L * 1024L,
        )
        private val ARTIFACT_BUILD_ESTIMATE = TerrainBuildEstimate(
            cpuNanos = 50_000_000L,
            outputBytes = 2L * 1024L * 1024L,
        )
        private const val MAX_COMPLETIONS_PER_FRAME = 64
        private const val MAX_UPLOADS_PER_FRAME = 32
        private const val MAX_UPLOAD_CPU_NANOS_PER_FRAME = 2_000_000L
        private const val MAX_UPLOAD_BYTES_PER_FRAME = 8L * 1024L * 1024L
        private const val ESTIMATED_UPLOAD_NANOS_PER_QUAD = 100L
        private const val BYTES_PER_QUAD = 120L
        private const val MAX_REGIONS = 32
        private const val VERTEX_CAPACITY_BYTES = 8 * 1024 * 1024
        private const val INDEX_CAPACITY_BYTES = 2 * 1024 * 1024
        private const val MAX_FAILURES_PER_SOURCE = 3
        private const val FAILURE_RETRY_BACKOFF_NANOS = 250_000_000L
        private const val SHADOW_DISTANCE_CHUNKS = 128
        private val COVERAGE_TRANSITION_POLICY = TerrainCoverageTransitionPolicy(true, 8)
        private const val MAX_NODE_VISITS = 4_096
        private const val MAX_SELECTION_CHANGES = 1_024
        // Bound both retained replacement sets below the shared region-store
        // capacity. Best-first refinement stops at this ceiling; an unbounded
        // desired set cannot become active atomically.
        private const val MAX_MAIN_SELECTION_PAGES = 512
        private const val MAX_SHADOW_SELECTION_PAGES = 256
        private const val DATA_RESELECTION_INTERVAL_FRAMES = 4L
        private const val REFINE_ERROR_PIXELS = 48.0
        private const val COARSEN_ERROR_PIXELS = 36.0
        private const val SHADOW_QUALITY = 0.75
        private const val SEAM_URGENCY_BAND_CHUNKS = 4.0
        private const val MAX_DIAGNOSTIC_PAGES = 128
        private const val DIAGNOSTIC_FRAME_INTERVAL = 60
        private val DIAGNOSTIC_PAGE_ORDER: Comparator<TerrainPageKey> = compareBy(
            { it.domain.ordinal },
            { it.detailLevel },
            { it.z },
            { it.y },
            { it.x },
        )
        private val REGION_EXTENT = TerrainRegionExtent(32, 1, 32)
        private val MAIN_VIEW = TerrainViewKey("minosoft:main")
        private val SHADOW_VIEW = TerrainViewKey("minosoft:shadow")
        private val AIR_IDENTIFIERS = setOf(
            ResourceLocation.of("minecraft:air"),
            ResourceLocation.of("minecraft:cave_air"),
            ResourceLocation.of("minecraft:void_air"),
        )
    }
}
