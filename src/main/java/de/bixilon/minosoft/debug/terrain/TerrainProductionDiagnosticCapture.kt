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

package de.bixilon.minosoft.debug.terrain

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.NearLoadedPageRegistrySnapshot
import de.bixilon.minosoft.gui.rendering.chunk.queue.loading.NearUploadPageRegistrySnapshot
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.NearMeshingPageRegistrySnapshot
import de.bixilon.minosoft.gui.rendering.terrain.distant.DistantTerrainRenderer
import de.bixilon.minosoft.gui.rendering.terrain.near.NearTerrainRegionMetrics
import de.bixilon.minosoft.gui.rendering.terrain.near.NearTerrainPagePublicationDiagnostic
import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainPhysicalLayout
import de.bixilon.minosoft.terrain.distant.DistantHierarchyRenderDiagnostics
import de.bixilon.minosoft.terrain.distant.DistantTerrainInterop
import de.bixilon.minosoft.terrain.distant.store.distantTerrainPersistenceIdentity
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainWorldIdentity
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCapSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCategory
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCategoryAccounting
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyScope
import de.bixilon.minosoft.terrain.runtime.TerrainProcessBuildService
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainCoverageDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticSchema
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageCursorCodec
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageCursorResolution
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainMaterialDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageRevisionDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageDiagnosticPaging
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageQuery
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPipelineDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPublicationDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainProviderDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainResidencyDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainSubmissionDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.storage.TerrainSelectionPublicationSnapshot

internal sealed interface TerrainProductionDiagnosticCaptureResult {
    data class Captured(val snapshot: TerrainDiagnosticSnapshot) : TerrainProductionDiagnosticCaptureResult
    data class Rejected(
        val rejection: de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejection,
    ) : TerrainProductionDiagnosticCaptureResult
    data object Unavailable : TerrainProductionDiagnosticCaptureResult
}

/** Captures all currently consolidated production owners on the render thread. */
object TerrainProductionDiagnosticCapture {
    internal fun capture(
        context: RenderContext,
        pageQuery: TerrainPageQuery? = null,
    ): TerrainProductionDiagnosticCaptureResult {
        val chunks = context.renderer[ChunkRenderer] ?: return TerrainProductionDiagnosticCaptureResult.Unavailable
        val world = context.session.world
        val graphGeneration = context.renderer.pipeline.generation.number
        val pipelineSelection = context.renderer.pipeline.terrainSelection()
        val pipelineIdentity = pipelineSelection.identity
        val distant = context.renderer.filterIsInstance<DistantTerrainRenderer>().singleOrNull()
        val distantDiagnostics = distant?.hierarchyDiagnostics()

        return chunks.terrain.acquire().use { nearLease ->
            val nearCapture = TerrainDiagnosticProviderCapture.capture(
                diagnosticGeneration = context.frameNumber,
                providerGeneration = nearLease.generation,
                descriptor = nearLease.descriptor,
            )
            val nearProvider = TerrainProviderDiagnosticSnapshot(
                pipelineIdentity.nearProviderId,
                pipelineIdentity.nearProviderGeneration,
                pipelineSelection.nearDescriptor,
            )
            val distantProvider = pipelineSelection.distantDescriptor?.let {
                TerrainProviderDiagnosticSnapshot(
                    checkNotNull(pipelineIdentity.distantProviderId),
                    checkNotNull(pipelineIdentity.distantProviderGeneration),
                    it,
                )
            }
            val providers = listOfNotNull(nearProvider, distantProvider)
            val coverage = chunks.loaded.ownershipSnapshot().lifecycle
            val loadedPages = chunks.loaded.pageRegistrySnapshot()
            val uploadPages = chunks.loadingQueue.pageRegistrySnapshot()
            val meshingPages = chunks.meshingQueue.pageRegistrySnapshot()
            val nearPageRegistryGeneration = checkedLongSum(
                loadedPages.generation,
                uploadPages.generation,
                meshingPages.generation,
            )
            val materialIds = providers.asSequence()
                .flatMap { it.descriptor.materials.asSequence() }
                .map { TerrainSemanticMaterialId("minosoft:terrain/${it.name.lowercase().replace('_', '-')}") }
                .distinct()
                .sorted()
                .toList()
            val views = providers
                .map { it.descriptor.supportedViews }
                .reduce(Set<String>::intersect)
            val materialPasses = providers.asSequence()
                .flatMap { it.descriptor.materials.asSequence() }
                .map { "minosoft:terrain/${it.name.lowercase().replace('_', '-')}" }
                .toSortedSet()
            val contentGeneration = context.session.contentFidelity.generationId ?: 0L
            val nearMetrics = chunks.regionTerrain?.metrics()
            val nearPublication = chunks.regionTerrain?.publicationDiagnostics()
            val nearPagePublications = chunks.regionTerrain?.pagePublicationDiagnostics().orEmpty()
            val residency = deviceResidency(graphGeneration, nearMetrics, distantDiagnostics)
            val pendingSubmissions = checkedIntSum(
                nearMetrics?.pendingSubmissionCount ?: 0,
                distantDiagnostics?.pendingSubmissionCount ?: 0,
            )
            val failedSubmissions = checkedLongSum(
                nearMetrics?.failedSubmissionCount?.toLong() ?: 0L,
                distantDiagnostics?.failedSubmissionCount?.toLong() ?: 0L,
            )
            val completionGeneration = checkedLongSum(
                nearMetrics?.deviceInvalidations ?: 0L,
                distantDiagnostics?.deviceInvalidations ?: 0L,
            )
            val coverageDiagnostic = coverage?.let(TerrainCoverageDiagnosticSnapshot::from)
                ?: TerrainCoverageDiagnosticSnapshot(
                    generation = 0L,
                    worldEpoch = world.terrainEpoch,
                    providerGeneration = nearLease.generation,
                    stateCounts = TerrainCoverageState.entries.associateWith { 0 },
                )
            val pageRegistryGeneration = pageRegistryGeneration(
                pageQuery?.selector?.domain,
                nearPageRegistryGeneration,
                distantDiagnostics?.indexRevision ?: 0L,
            )
            val pipelineGeneration = pipelineSelection.generation
            val publication = buildList {
                val nearSelection = nearPublication?.selection
                    ?: TerrainSelectionPublicationSnapshot(0L, 0, emptyList())
                add(TerrainPublicationDiagnosticSnapshot.from(
                    domain = TerrainDomain.NEAR,
                    providerId = nearProvider.providerId,
                    storageGeneration = nearPublication?.storageGeneration ?: 0L,
                    residentPageCount = nearPublication?.residentPageCount ?: 0,
                    selection = nearSelection,
                ))
                if (distantProvider != null) {
                    val distantSelection = distantDiagnostics?.selectionPublication
                        ?: TerrainSelectionPublicationSnapshot(0L, 0, emptyList())
                    val viewIds = distantSelection.views.mapTo(hashSetOf()) { it.view.value }
                    val masks = buildMap {
                        if ("minosoft:main" in viewIds) {
                            put("minosoft:main", distantDiagnostics?.mainMaskedPages ?: 0)
                        }
                        if ("minosoft:shadow" in viewIds) {
                            put("minosoft:shadow", distantDiagnostics?.shadowMaskedPages ?: 0)
                        }
                    }
                    add(TerrainPublicationDiagnosticSnapshot.from(
                        domain = TerrainDomain.DISTANT,
                        providerId = distantProvider.providerId,
                        storageGeneration = distantDiagnostics?.storagePublicationGeneration ?: 0L,
                        residentPageCount = distantDiagnostics?.gpuPages ?: 0,
                        selection = distantSelection,
                        maskedPageCounts = masks,
                    ))
                }
            }
            val pageWindow = pageQuery?.let { query ->
                val afterPage = query.cursor?.let { cursor ->
                    when (val resolution = TerrainPageCursorCodec.resolve(
                        cursor,
                        pageRegistryGeneration,
                        query,
                    )) {
                        is TerrainPageCursorResolution.Accepted -> resolution.lastPage
                        is TerrainPageCursorResolution.Rejected -> return@use TerrainProductionDiagnosticCaptureResult.Rejected(
                            resolution.rejection.copy(diagnosticGeneration = context.frameNumber),
                        )
                    }
                }
                val candidates = when (query.selector.domain) {
                    TerrainDomain.NEAR -> {
                        nearPageDiagnostics(
                            loadedPages,
                            uploadPages,
                            meshingPages,
                            nearProvider.providerId,
                            query,
                            afterPage,
                            nearPagePublications,
                        )
                    }

                    TerrainDomain.DISTANT -> distant?.hierarchyPageDiagnostics(query, afterPage).orEmpty()
                }
                TerrainPageDiagnosticPaging.window(pageRegistryGeneration, query, candidates)
            }

            TerrainProductionDiagnosticCaptureResult.Captured(TerrainDiagnosticSnapshot(
                schemaVersion = TerrainDiagnosticSchema.VERSION,
                diagnosticGeneration = context.frameNumber,
                worldIdentity = TerrainWorldIdentity(
                    sessionGeneration = context.session.terrainSessionGeneration,
                    normalizedWorldKey = world.name?.toString() ?: "minosoft:unknown",
                    worldEpoch = world.terrainEpoch,
                    minimumHeight = world.dimension.minY,
                    maximumHeightExclusive = Math.addExact(world.dimension.maxY, 1),
                    contentGeneration = contentGeneration,
                    persistenceIdentity = distantTerrainPersistenceIdentity(
                        context.session.version.name,
                        context.session.connection.identifier,
                        world.name?.toString() ?: "minosoft:unknown",
                        world.terrainPersistenceFingerprint,
                    ),
                ),
                providers = providers,
                pipeline = TerrainPipelineDiagnosticSnapshot(
                    generation = pipelineGeneration,
                    mode = pipelineIdentity.mode,
                    nearProviderId = nearProvider.providerId,
                    nearProviderGeneration = nearProvider.generation,
                    distantProviderId = distantProvider?.providerId,
                    distantProviderGeneration = distantProvider?.generation,
                    materialGeneration = pipelineIdentity.materialGeneration,
                    shaderPipelineGeneration = pipelineIdentity.shaderPipelineGeneration,
                    nearLayout = TerrainPhysicalLayout(
                        id = nearCapture.physicalLayoutId,
                        generation = pipelineIdentity.nearLayoutGeneration,
                        domain = TerrainDomain.NEAR,
                    ),
                    distantLayout = distantProvider?.let {
                        TerrainPhysicalLayout(
                            id = DistantTerrainInterop.PHYSICAL_LAYOUT_ID,
                            generation = checkNotNull(pipelineIdentity.distantLayoutGeneration),
                            domain = TerrainDomain.DISTANT,
                        )
                    },
                    declaredViews = views,
                    declaredMaterialPasses = materialPasses,
                    renderGraphGeneration = graphGeneration,
                ),
                material = TerrainMaterialDiagnosticSnapshot(
                    generation = pipelineIdentity.materialGeneration,
                    semanticMaterialIds = materialIds,
                    modelGeneration = contentGeneration,
                    atlasGeneration = contentGeneration,
                    tintGeneration = contentGeneration,
                    animationGeneration = contentGeneration,
                ),
                coverage = coverageDiagnostic,
                pageRegistryGeneration = pageRegistryGeneration,
                scheduling = TerrainProcessBuildService.shared.snapshot(),
                residency = listOf(residency),
                submission = TerrainSubmissionDiagnosticSnapshot(
                    generation = context.terrainSubmissions.latest()?.value ?: 0L,
                    deviceRuntime = context.terrainSubmissions.deviceRuntime,
                    latestSubmittedSerial = context.terrainSubmissions.latest(),
                    pendingSubmissionCount = pendingSubmissions,
                    failedSubmissionCount = failedSubmissions,
                    completionGeneration = completionGeneration,
                ),
                publication = publication,
                pageWindow = pageWindow,
            ))
        }
    }

    private fun deviceResidency(
        generation: Long,
        near: NearTerrainRegionMetrics?,
        distant: DistantHierarchyRenderDiagnostics?,
    ): TerrainResidencyDiagnosticSnapshot {
        val stagingBytes = checkedLongSum(
            near?.stagingCapacityBytes?.toLong() ?: 0L,
            distant?.stagingCapacityBytes?.toLong() ?: 0L,
        )
        val nearBytes = near?.residentBytes ?: 0L
        val distantBytes = distant?.residentBytes ?: 0L
        val retiredBytes = checkedLongSum(near?.retiredBytes ?: 0L, distant?.retiredBytes ?: 0L)
        val capBytes = checkedLongSum(
            near?.deviceCapacityBytes ?: 0L,
            distant?.deviceCapacityBytes ?: 0L,
        )
        val highWaterBytes = checkedLongSum(
            stagingBytes,
            near?.vertexHighWaterBytes ?: 0L,
            near?.indexHighWaterBytes ?: 0L,
            distant?.storageHighWaterBytes ?: 0L,
        )
        val accounting = buildList {
            if (stagingBytes != 0L) add(
                TerrainResidencyCategoryAccounting(TerrainResidencyCategory.STAGING_BUFFER, stagingBytes),
            )
            if (nearBytes != 0L) add(
                TerrainResidencyCategoryAccounting(TerrainResidencyCategory.RESIDENT_NEAR_GPU_RANGE, nearBytes),
            )
            if (distantBytes != 0L) add(
                TerrainResidencyCategoryAccounting(TerrainResidencyCategory.RESIDENT_DISTANT_GPU_RANGE, distantBytes),
            )
            if (retiredBytes != 0L) add(
                TerrainResidencyCategoryAccounting(
                    category = TerrainResidencyCategory.RETIRED_GPU_RANGE,
                    residentBytes = 0L,
                    retiredBytes = retiredBytes,
                    pinnedBytes = retiredBytes,
                ),
            )
        }
        return TerrainResidencyDiagnosticSnapshot(
            generation = generation,
            accounting = TerrainResidencyCapSnapshot(
                scope = TerrainResidencyScope.DEVICE,
                capBytes = capBytes,
                accounting = accounting,
                highWaterMarkBytes = maxOf(highWaterBytes, checkedLongSum(stagingBytes, nearBytes, distantBytes, retiredBytes)),
                hardFailureCount = checkedLongSum(
                    near?.allocationFailures ?: 0L,
                    near?.uploadFailures ?: 0L,
                    distant?.allocationFailures ?: 0L,
                    distant?.uploadFailures ?: 0L,
                ),
            ),
        )
    }

    private fun checkedIntSum(vararg values: Int): Int = values.fold(0, Math::addExact)

    private fun checkedLongSum(vararg values: Long): Long = values.fold(0L, Math::addExact)

    internal fun nearPageDiagnostics(
        loaded: NearLoadedPageRegistrySnapshot,
        uploads: NearUploadPageRegistrySnapshot,
        meshing: NearMeshingPageRegistrySnapshot,
        providerId: String,
        query: TerrainPageQuery,
        afterPage: de.bixilon.minosoft.terrain.model.identity.TerrainPageKey?,
        publications: Map<de.bixilon.minosoft.terrain.model.identity.TerrainPageKey, NearTerrainPagePublicationDiagnostic> = emptyMap(),
    ): List<TerrainPageDiagnosticSnapshot> {
        require(query.selector.domain == TerrainDomain.NEAR) {
            "Near page diagnostics require a near page selector"
        }
        val pages = HashMap<de.bixilon.minosoft.terrain.model.identity.TerrainPageKey, TerrainPageDiagnosticSnapshot>()
        for (identity in loaded.identities) {
            val publication = publications[identity.page]
            pages[identity.page] = TerrainPageDiagnosticSnapshot(
                page = identity.page,
                coverageState = TerrainCoverageState.READY,
                buildIdentity = identity,
                providerId = providerId,
                source = "near-snapshot",
                revisions = TerrainPageRevisionDiagnosticSnapshot(
                    sourceRevision = identity.sourceDataRevision,
                    publicationRevision = publication?.publicationRevision,
                ),
                ranges = publication?.ranges.orEmpty(),
                visibility = publication?.visibility.orEmpty(),
                artifactDigest = publication?.artifactDigest ?: loaded.artifactDigests[identity.page],
            )
        }
        for (identity in uploads.identities) {
            val previous = pages[identity.page]
            pages[identity.page] = TerrainPageDiagnosticSnapshot(
                page = identity.page,
                coverageState = previous?.coverageState ?: TerrainCoverageState.UPLOAD_PENDING,
                buildIdentity = identity,
                providerId = providerId,
                failure = previous?.failure,
                source = previous?.source ?: "near-snapshot",
                revisions = TerrainPageRevisionDiagnosticSnapshot(
                    sourceRevision = identity.sourceDataRevision,
                    publicationRevision = previous?.revisions?.publicationRevision,
                ),
                ranges = previous?.ranges.orEmpty(),
                visibility = previous?.visibility.orEmpty(),
                artifactDigest = previous?.artifactDigest,
            )
        }
        for (entry in meshing.entries) {
            val previous = pages[entry.identity.page]
            pages[entry.identity.page] = TerrainPageDiagnosticSnapshot(
                page = entry.identity.page,
                coverageState = previous?.coverageState ?: entry.state,
                buildIdentity = entry.identity,
                providerId = providerId,
                failure = entry.failure,
                source = previous?.source ?: "near-snapshot",
                revisions = TerrainPageRevisionDiagnosticSnapshot(
                    sourceRevision = entry.identity.sourceDataRevision,
                    publicationRevision = previous?.revisions?.publicationRevision,
                ),
                ranges = previous?.ranges.orEmpty(),
                visibility = previous?.visibility.orEmpty(),
                artifactDigest = previous?.artifactDigest,
            )
        }
        return pages.values.asSequence()
            .filter { query.selector.matches(it.page) }
            .filter { afterPage == null || PAGE_ORDER.compare(it.page, afterPage) > 0 }
            .sortedWith(TerrainPageDiagnosticSnapshot.ORDER)
            .take(Math.addExact(query.maximumCount, 1))
            .toList()
    }

    internal fun pageRegistryGeneration(
        domain: TerrainDomain?,
        nearGeneration: Long,
        distantGeneration: Long,
    ): Long {
        require(nearGeneration >= 0L && distantGeneration >= 0L) {
            "Terrain page-registry generations must not be negative"
        }
        return when (domain) {
            TerrainDomain.NEAR -> nearGeneration
            TerrainDomain.DISTANT -> distantGeneration
            null -> checkedLongSum(nearGeneration, distantGeneration)
        }
    }

    private val PAGE_ORDER: Comparator<de.bixilon.minosoft.terrain.model.identity.TerrainPageKey> = compareBy(
        { it.domain.ordinal },
        { it.detailLevel },
        { it.z },
        { it.y },
        { it.x },
    )
}
