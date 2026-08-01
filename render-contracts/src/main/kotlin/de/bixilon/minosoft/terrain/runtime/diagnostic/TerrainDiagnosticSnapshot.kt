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

package de.bixilon.minosoft.terrain.runtime.diagnostic

import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainPhysicalLayout
import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainPipelineGeneration
import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainRuntimeMode
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.identity.TerrainWorldIdentity
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.material.TerrainMaterialTableMetadata
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainFailureSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCapSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyScope
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSharedBuildServiceSnapshot
import de.bixilon.minosoft.terrain.runtime.storage.TerrainSelectionPublicationSnapshot

data class TerrainViewVisibilityDiagnosticSnapshot(
    val viewId: String,
    val desiredPageCount: Int,
    val visiblePageCount: Int,
    val missingPageCount: Int,
    val maskedPageCount: Int,
) {
    val drawPageCount: Int = Math.subtractExact(visiblePageCount, maskedPageCount)

    init {
        require(viewId.isNotBlank()) { "Terrain diagnostic view ID must not be blank" }
        require(desiredPageCount >= 0) { "Terrain diagnostic desired-page count must not be negative" }
        require(visiblePageCount >= 0) { "Terrain diagnostic visible-page count must not be negative" }
        require(missingPageCount in 0..desiredPageCount) {
            "Terrain diagnostic missing-page count must be within the desired selection"
        }
        require(maskedPageCount in 0..visiblePageCount) {
            "Terrain diagnostic masked-page count must be within the visible selection"
        }
    }
}

class TerrainPublicationDiagnosticSnapshot(
    val domain: TerrainDomain,
    val providerId: String,
    val storageGeneration: Long,
    val selectionGeneration: Long,
    val residentPageCount: Int,
    val retainedPageCount: Int,
    views: Collection<TerrainViewVisibilityDiagnosticSnapshot>,
) {
    val views: List<TerrainViewVisibilityDiagnosticSnapshot> =
        java.util.List.copyOf(views.sortedBy { it.viewId })

    init {
        require(providerId.isNotBlank()) { "Terrain diagnostic publication provider must not be blank" }
        require(storageGeneration >= 0L) {
            "Terrain diagnostic storage generation must not be negative"
        }
        require(selectionGeneration >= 0L) {
            "Terrain diagnostic selection generation must not be negative"
        }
        require(residentPageCount >= 0) {
            "Terrain diagnostic resident-page count must not be negative"
        }
        require(retainedPageCount >= 0) {
            "Terrain diagnostic retained-page count must not be negative"
        }
        require(this.views.map { it.viewId }.distinct().size == this.views.size) {
            "Terrain diagnostic publication contains duplicate views"
        }
    }

    companion object {
        fun from(
            domain: TerrainDomain,
            providerId: String,
            storageGeneration: Long,
            residentPageCount: Int,
            selection: TerrainSelectionPublicationSnapshot,
            maskedPageCounts: Map<String, Int> = emptyMap(),
        ): TerrainPublicationDiagnosticSnapshot {
            require(maskedPageCounts.keys.all { masked ->
                selection.views.any { it.view.value == masked }
            }) { "Terrain diagnostic mask references an unpublished view" }
            return TerrainPublicationDiagnosticSnapshot(
                domain = domain,
                providerId = providerId,
                storageGeneration = storageGeneration,
                selectionGeneration = selection.generation,
                residentPageCount = residentPageCount,
                retainedPageCount = selection.retainedPageCount,
                views = selection.views.map { view ->
                    TerrainViewVisibilityDiagnosticSnapshot(
                        viewId = view.view.value,
                        desiredPageCount = view.desiredPageCount,
                        visiblePageCount = view.activePageCount,
                        missingPageCount = view.missingPageCount,
                        maskedPageCount = maskedPageCounts[view.view.value] ?: 0,
                    )
                },
            )
        }
    }
}

data class TerrainProviderDiagnosticSnapshot(
    val providerId: String,
    val generation: Long,
    val descriptor: TerrainInteropDescriptor,
) {
    init {
        require(providerId == descriptor.providerId) {
            "Terrain diagnostic provider ID must match its interoperability descriptor"
        }
        require(generation >= 0L) { "Terrain diagnostic provider generation must not be negative" }
    }
}

class TerrainMaterialDiagnosticSnapshot(
    val generation: Long,
    semanticMaterialIds: Collection<TerrainSemanticMaterialId>,
    val modelGeneration: Long,
    val atlasGeneration: Long,
    val tintGeneration: Long,
    val animationGeneration: Long,
) {
    val semanticMaterialIds: List<TerrainSemanticMaterialId> =
        java.util.List.copyOf(semanticMaterialIds.distinct().sorted())

    init {
        require(generation >= 0L) { "Terrain diagnostic material generation must not be negative" }
        require(modelGeneration >= 0L) { "Terrain diagnostic model generation must not be negative" }
        require(atlasGeneration >= 0L) { "Terrain diagnostic atlas generation must not be negative" }
        require(tintGeneration >= 0L) { "Terrain diagnostic tint generation must not be negative" }
        require(animationGeneration >= 0L) {
            "Terrain diagnostic animation generation must not be negative"
        }
        require(this.semanticMaterialIds.isNotEmpty()) {
            "Terrain diagnostic material IDs must not be empty"
        }
        require(this.semanticMaterialIds.size == semanticMaterialIds.size) {
            "Terrain diagnostic material IDs must not contain duplicates"
        }
    }

    companion object {
        fun from(metadata: TerrainMaterialTableMetadata): TerrainMaterialDiagnosticSnapshot =
            TerrainMaterialDiagnosticSnapshot(
                generation = metadata.generation,
                semanticMaterialIds = metadata.semanticMaterialIds,
                modelGeneration = metadata.modelGeneration,
                atlasGeneration = metadata.atlasGeneration,
                tintGeneration = metadata.tintGeneration,
                animationGeneration = metadata.animationGeneration,
            )
    }
}

class TerrainPipelineDiagnosticSnapshot(
    val generation: Long,
    val mode: TerrainRuntimeMode,
    val nearProviderId: String,
    val nearProviderGeneration: Long,
    val distantProviderId: String?,
    val distantProviderGeneration: Long?,
    val materialGeneration: Long,
    val shaderPipelineGeneration: Long,
    val nearLayout: TerrainPhysicalLayout,
    val distantLayout: TerrainPhysicalLayout?,
    declaredViews: Collection<String>,
    declaredMaterialPasses: Collection<String>,
    val renderGraphGeneration: Long,
) {
    val declaredViews: List<String> = java.util.List.copyOf(declaredViews.distinct().sorted())
    val declaredMaterialPasses: List<String> =
        java.util.List.copyOf(declaredMaterialPasses.distinct().sorted())

    init {
        require(generation >= 0L) { "Terrain diagnostic pipeline generation must not be negative" }
        require(nearProviderId.isNotBlank()) {
            "Terrain diagnostic near-provider ID must not be blank"
        }
        require(nearProviderGeneration >= 0L) {
            "Terrain diagnostic near-provider generation must not be negative"
        }
        require((distantProviderId == null) == (distantProviderGeneration == null)) {
            "Terrain diagnostic distant-provider ID and generation must be present together"
        }
        require(distantProviderId == null || distantProviderId.isNotBlank()) {
            "Terrain diagnostic distant-provider ID must not be blank"
        }
        require(distantProviderGeneration == null || distantProviderGeneration >= 0L) {
            "Terrain diagnostic distant-provider generation must not be negative"
        }
        require(materialGeneration >= 0L) {
            "Terrain diagnostic material generation must not be negative"
        }
        require(shaderPipelineGeneration >= 0L) {
            "Terrain diagnostic shader-pipeline generation must not be negative"
        }
        require((distantProviderId == null) == (distantLayout == null)) {
            "Terrain diagnostic distant provider and layout must be present together"
        }
        require(this.declaredViews.isNotEmpty() && this.declaredViews.size == declaredViews.size) {
            "Terrain diagnostic views must be non-empty and unique"
        }
        require(
            this.declaredMaterialPasses.isNotEmpty() &&
                this.declaredMaterialPasses.size == declaredMaterialPasses.size,
        ) {
            "Terrain diagnostic material passes must be non-empty and unique"
        }
        require(renderGraphGeneration >= 0L) {
            "Terrain diagnostic render-graph generation must not be negative"
        }
    }

    companion object {
        fun from(generation: TerrainPipelineGeneration): TerrainPipelineDiagnosticSnapshot =
            TerrainPipelineDiagnosticSnapshot(
                generation = generation.generation,
                mode = generation.mode,
                nearProviderId = generation.nearProvider.descriptor.providerId,
                nearProviderGeneration = generation.nearProvider.generation,
                distantProviderId = generation.distantProvider?.descriptor?.providerId,
                distantProviderGeneration = generation.distantProvider?.generation,
                materialGeneration = generation.materialTable.generation,
                shaderPipelineGeneration = generation.shaderPipeline.generation,
                nearLayout = generation.nearLayout,
                distantLayout = generation.distantLayout,
                declaredViews = generation.declaredViews,
                declaredMaterialPasses = generation.declaredMaterialPasses,
                renderGraphGeneration = generation.renderGraphGeneration,
            )
    }
}

class TerrainCoverageDiagnosticSnapshot(
    val generation: Long,
    val worldEpoch: Long,
    val providerGeneration: Long,
    stateCounts: Map<TerrainCoverageState, Int>,
) {
    val stateCounts: Map<TerrainCoverageState, Int> =
        java.util.Collections.unmodifiableMap(
            TerrainCoverageState.entries.associateWith { stateCounts[it] ?: 0 },
        )
    val pageCount: Int = checkedCount(this.stateCounts.values)

    init {
        require(generation >= 0L) { "Terrain diagnostic coverage generation must not be negative" }
        require(worldEpoch >= 0L) { "Terrain diagnostic coverage world epoch must not be negative" }
        require(providerGeneration >= 0L) {
            "Terrain diagnostic coverage provider generation must not be negative"
        }
        require(stateCounts.keys.all { it in TerrainCoverageState.entries }) {
            "Terrain diagnostic coverage contains an unsupported state"
        }
        require(this.stateCounts.values.all { it >= 0 }) {
            "Terrain diagnostic coverage counts must not be negative"
        }
    }

    companion object {
        fun from(snapshot: TerrainCoverageSnapshot): TerrainCoverageDiagnosticSnapshot =
            TerrainCoverageDiagnosticSnapshot(
                generation = snapshot.lifecycleRevision,
                worldEpoch = snapshot.worldEpoch,
                providerGeneration = snapshot.providerGeneration,
                stateCounts = snapshot.cells.groupingBy { it.state }.eachCount(),
            )

        private fun checkedCount(counts: Collection<Int>): Int {
            var total = 0
            for (count in counts) total = Math.addExact(total, count)
            return total
        }
    }
}

data class TerrainResidencyDiagnosticSnapshot(
    val generation: Long,
    val accounting: TerrainResidencyCapSnapshot,
) {
    val scope: TerrainResidencyScope
        get() = accounting.scope

    init {
        require(generation >= 0L) { "Terrain diagnostic residency generation must not be negative" }
    }
}

data class TerrainSubmissionDiagnosticSnapshot(
    val generation: Long,
    val deviceRuntime: TerrainDeviceRuntimeId,
    val latestSubmittedSerial: TerrainSubmissionSerial?,
    val pendingSubmissionCount: Int,
    val failedSubmissionCount: Long,
    val completionGeneration: Long,
) {
    init {
        require(generation >= 0L) { "Terrain diagnostic submission generation must not be negative" }
        require(pendingSubmissionCount >= 0) {
            "Terrain diagnostic pending-submission count must not be negative"
        }
        require(failedSubmissionCount >= 0L) {
            "Terrain diagnostic failed-submission count must not be negative"
        }
        require(completionGeneration >= 0L) {
            "Terrain diagnostic completion generation must not be negative"
        }
    }
}

data class TerrainPageRevisionDiagnosticSnapshot(
    val sourceRevision: Long? = null,
    val dirtyRevision: Long? = null,
    val renderRevision: Long? = null,
    val publicationRevision: Long? = null,
) {
    init {
        require(listOfNotNull(sourceRevision, dirtyRevision, renderRevision, publicationRevision).all { it >= 0L }) {
            "Terrain diagnostic page revisions must not be negative"
        }
    }
}

data class TerrainPageRangeDiagnosticSnapshot(
    val partitionId: String,
    val vertexOffsetBytes: Int,
    val vertexLengthBytes: Int,
    val indexOffsetBytes: Int,
    val indexLengthBytes: Int,
) {
    init {
        require(partitionId.isNotBlank()) { "Terrain diagnostic range partition must not be blank" }
        require(vertexOffsetBytes >= 0 && vertexLengthBytes >= 0) {
            "Terrain diagnostic vertex range must not be negative"
        }
        require(indexOffsetBytes >= 0 && indexLengthBytes >= 0) {
            "Terrain diagnostic index range must not be negative"
        }
    }
}

data class TerrainPageVisibilityDiagnosticSnapshot(
    val viewId: String,
    val selected: Boolean,
    val masked: Boolean,
) {
    init {
        require(viewId.isNotBlank()) { "Terrain diagnostic page view must not be blank" }
        require(!masked || selected) { "A masked terrain diagnostic page must be selected" }
    }
}

class TerrainPageDiagnosticSnapshot(
    val page: TerrainPageKey,
    val coverageState: TerrainCoverageState,
    val buildIdentity: TerrainBuildIdentity?,
    val providerId: String?,
    val failure: TerrainFailureSnapshot? = null,
    val source: String? = null,
    val revisions: TerrainPageRevisionDiagnosticSnapshot = TerrainPageRevisionDiagnosticSnapshot(),
    ranges: Collection<TerrainPageRangeDiagnosticSnapshot> = emptyList(),
    visibility: Collection<TerrainPageVisibilityDiagnosticSnapshot> = emptyList(),
    val artifactDigest: String? = null,
) {
    val ranges: List<TerrainPageRangeDiagnosticSnapshot> = java.util.List.copyOf(
        ranges.sortedBy { it.partitionId },
    )
    val visibility: List<TerrainPageVisibilityDiagnosticSnapshot> = java.util.List.copyOf(
        visibility.sortedBy { it.viewId },
    )

    init {
        require(buildIdentity == null || buildIdentity.page == page) {
            "Terrain diagnostic build identity must belong to its page"
        }
        require(providerId == null || providerId.isNotBlank()) {
            "Terrain diagnostic page provider ID must not be blank"
        }
        require(source == null || source.isNotBlank() && source.length <= TerrainDiagnosticSchema.MAXIMUM_PAGE_SOURCE_LENGTH) {
            "Terrain diagnostic page source is invalid"
        }
        require(this.ranges.size <= TerrainDiagnosticSchema.MAXIMUM_PAGE_RANGES) {
            "Terrain diagnostic page range count exceeds the schema bound"
        }
        require(this.ranges.map { it.partitionId }.distinct().size == this.ranges.size) {
            "Terrain diagnostic page ranges contain duplicate partitions"
        }
        require(this.visibility.size <= TerrainDiagnosticSchema.MAXIMUM_PAGE_VIEWS) {
            "Terrain diagnostic page visibility count exceeds the schema bound"
        }
        require(this.visibility.map { it.viewId }.distinct().size == this.visibility.size) {
            "Terrain diagnostic page visibility contains duplicate views"
        }
        require(
            artifactDigest == null ||
                artifactDigest.isNotBlank() && artifactDigest.length <= TerrainDiagnosticSchema.MAXIMUM_ARTIFACT_DIGEST_LENGTH,
        ) { "Terrain diagnostic artifact digest is invalid" }
    }

    companion object {
        val ORDER: Comparator<TerrainPageDiagnosticSnapshot> = compareBy(
            { it.page.domain.ordinal },
            { it.page.detailLevel },
            { it.page.z },
            { it.page.y },
            { it.page.x },
        )
    }
}

class TerrainPageDiagnosticWindow(
    val query: TerrainPageQuery,
    pages: Collection<TerrainPageDiagnosticSnapshot>,
    val nextCursor: TerrainPageCursor?,
) {
    val pages: List<TerrainPageDiagnosticSnapshot> =
        java.util.List.copyOf(pages.sortedWith(TerrainPageDiagnosticSnapshot.ORDER))

    init {
        require(this.pages.size <= query.maximumCount) {
            "Terrain diagnostic page window exceeds the requested maximum count"
        }
        require(this.pages.map { it.page }.toSet().size == this.pages.size) {
            "Terrain diagnostic page window contains duplicate pages"
        }
        require(this.pages.all { it.page.worldEpoch == query.worldEpoch }) {
            "Terrain diagnostic page window contains another world epoch"
        }
        require(this.pages.all { query.selector.matches(it.page) }) {
            "Terrain diagnostic page window contains a page outside its selector"
        }
        require(nextCursor == null || this.pages.isNotEmpty()) {
            "An empty terrain diagnostic page window cannot have a continuation cursor"
        }
    }
}

object TerrainPageDiagnosticPaging {
    fun window(
        pageRegistryGeneration: Long,
        query: TerrainPageQuery,
        candidates: Collection<TerrainPageDiagnosticSnapshot>,
    ): TerrainPageDiagnosticWindow {
        require(pageRegistryGeneration >= 0L) {
            "Terrain page-registry generation must not be negative"
        }
        require(candidates.size <= Math.addExact(query.maximumCount, 1)) {
            "Terrain page owner exceeded the bounded diagnostic lookahead"
        }
        require(candidates.all { query.selector.matches(it.page) && it.page.worldEpoch == query.worldEpoch }) {
            "Terrain page owner returned a candidate outside the diagnostic query"
        }
        val ordered = candidates.sortedWith(TerrainPageDiagnosticSnapshot.ORDER)
        val pages = ordered.take(query.maximumCount)
        val nextCursor = if (ordered.size > query.maximumCount) {
            TerrainPageCursorCodec.issue(pageRegistryGeneration, query, pages.last().page)
        } else {
            null
        }
        return TerrainPageDiagnosticWindow(query, pages, nextCursor)
    }
}

class TerrainDiagnosticSnapshot(
    override val schemaVersion: Int,
    override val diagnosticGeneration: Long,
    val worldIdentity: TerrainWorldIdentity,
    providers: Collection<TerrainProviderDiagnosticSnapshot>,
    val pipeline: TerrainPipelineDiagnosticSnapshot,
    val material: TerrainMaterialDiagnosticSnapshot,
    val coverage: TerrainCoverageDiagnosticSnapshot,
    val pageRegistryGeneration: Long,
    val scheduling: TerrainSharedBuildServiceSnapshot,
    residency: Collection<TerrainResidencyDiagnosticSnapshot>,
    val submission: TerrainSubmissionDiagnosticSnapshot,
    publication: Collection<TerrainPublicationDiagnosticSnapshot>,
    val pageWindow: TerrainPageDiagnosticWindow? = null,
) : TerrainDiagnosticSnapshotView {
    override val providers: List<TerrainProviderDiagnosticSnapshot> =
        java.util.List.copyOf(providers.sortedWith(compareBy({ it.providerId }, { it.generation })))
    val residency: List<TerrainResidencyDiagnosticSnapshot> =
        java.util.List.copyOf(residency.sortedBy { it.scope.ordinal })
    val publication: List<TerrainPublicationDiagnosticSnapshot> =
        java.util.List.copyOf(publication.sortedBy { it.domain.ordinal })

    init {
        require(schemaVersion == TerrainDiagnosticSchema.VERSION) {
            "Unsupported terrain diagnostic snapshot schema version: $schemaVersion"
        }
        require(diagnosticGeneration >= 0L) {
            "Terrain diagnostic snapshot generation must not be negative"
        }
        require(this.providers.isNotEmpty()) {
            "Terrain diagnostic snapshot must contain at least one provider"
        }
        require(this.providers.map { it.providerId to it.generation }.toSet().size == this.providers.size) {
            "Terrain diagnostic snapshot contains duplicate provider generations"
        }
        require(
            this.providers.any {
                it.providerId == pipeline.nearProviderId &&
                    it.generation == pipeline.nearProviderGeneration
            },
        ) {
            "Terrain diagnostic snapshot is missing the selected near provider"
        }
        require(
            pipeline.distantProviderId == null ||
                this.providers.any {
                    it.providerId == pipeline.distantProviderId &&
                        it.generation == pipeline.distantProviderGeneration
                },
        ) {
            "Terrain diagnostic snapshot is missing the selected distant provider"
        }
        require(material.generation == pipeline.materialGeneration) {
            "Terrain diagnostic material and pipeline generations must match"
        }
        require(coverage.worldEpoch == worldIdentity.worldEpoch) {
            "Terrain diagnostic coverage must belong to the snapshot world epoch"
        }
        require(pageRegistryGeneration >= 0L) {
            "Terrain diagnostic page-registry generation must not be negative"
        }
        require(this.residency.map { it.scope }.toSet().size == this.residency.size) {
            "Terrain diagnostic snapshot contains duplicate residency scopes"
        }
        require(this.publication.map { it.domain }.distinct().size == this.publication.size) {
            "Terrain diagnostic snapshot contains duplicate publication domains"
        }
        require(this.publication.all { state ->
            this.providers.any { provider ->
                provider.providerId == state.providerId && state.domain in provider.descriptor.domains
            }
        }) {
            "Terrain diagnostic publication must belong to a declared provider domain"
        }
        require(this.publication.any {
            it.domain == TerrainDomain.NEAR && it.providerId == pipeline.nearProviderId
        }) {
            "Terrain diagnostic snapshot is missing near publication state"
        }
        require(
            pipeline.distantProviderId == null || this.publication.any {
                it.domain == TerrainDomain.DISTANT && it.providerId == pipeline.distantProviderId
            },
        ) {
            "Terrain diagnostic snapshot is missing distant publication state"
        }
        require(pageWindow == null || pageWindow.query.worldEpoch == worldIdentity.worldEpoch) {
            "Terrain diagnostic page window must belong to the snapshot world epoch"
        }
        if (pageWindow?.nextCursor != null) {
            val resolved = TerrainPageCursorCodec.resolve(
                cursor = pageWindow.nextCursor,
                diagnosticGeneration = pageRegistryGeneration,
                query = pageWindow.query,
            )
            require(
                resolved is TerrainPageCursorResolution.Accepted &&
                    resolved.lastPage == pageWindow.pages.last().page,
            ) {
                "Terrain diagnostic continuation cursor must identify the final page and generation"
            }
        }
    }
}
