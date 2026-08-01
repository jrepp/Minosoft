/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.diagnostic

import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainPhysicalLayout
import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainRuntimeMode
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.identity.TerrainWorldIdentity
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.interop.TerrainLightingSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.model.interop.TerrainTintSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainUploadCapability
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainFailureCategory
import de.bixilon.minosoft.terrain.runtime.TerrainFailurePhase
import de.bixilon.minosoft.terrain.runtime.TerrainFailureSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainProcessScopeId
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCapSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCategory
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCategoryAccounting
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyScope
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.TerrainRetryEligibility
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildRuntimeSnapshot
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSchedulerTenantId
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSharedBuildServiceSnapshot
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSharedBuildTenantSnapshot

internal object TerrainDiagnosticFixtures {
    const val DIAGNOSTIC_GENERATION: Long = 101L
    const val PAGE_REGISTRY_GENERATION: Long = 111L
    const val WORLD_EPOCH: Long = 7L

    fun query(maximumCount: Int = 2): TerrainPageQuery = TerrainPageQuery(
        worldEpoch = WORLD_EPOCH,
        selector = TerrainPageAreaSelector(
            domain = TerrainDomain.NEAR,
            minimumDetailLevel = 0,
            maximumDetailLevel = 0,
            minimumX = -1L,
            maximumX = 1L,
            minimumY = 0L,
            maximumY = 0L,
            minimumZ = -2L,
            maximumZ = 2L,
        ),
        maximumCount = maximumCount,
    )

    fun page(x: Long, z: Long): TerrainPageKey =
        TerrainPageKey(TerrainDomain.NEAR, 0, x, 0L, z, WORLD_EPOCH)

    fun pageSnapshot(x: Long, z: Long): TerrainPageDiagnosticSnapshot {
        val page = page(x, z)
        return TerrainPageDiagnosticSnapshot(
            page = page,
            coverageState = TerrainCoverageState.READY,
            buildIdentity = TerrainBuildIdentity(
                page = page,
                requestRevision = 1L,
                capturedModelRevision = 2L,
                providerGeneration = 11L,
                layoutGeneration = 51L,
                materialGeneration = 31L,
                coverageGeneration = 71L,
                prioritySequence = 3L,
                sourceDataRevision = 4L,
            ),
            providerId = "builtin",
            source = "near-snapshot",
            revisions = TerrainPageRevisionDiagnosticSnapshot(
                sourceRevision = 4L,
                publicationRevision = 72L,
            ),
            ranges = listOf(
                TerrainPageRangeDiagnosticSnapshot(
                    partitionId = "opaque",
                    vertexOffsetBytes = 0,
                    vertexLengthBytes = 64,
                    indexOffsetBytes = 0,
                    indexLengthBytes = 12,
                ),
            ),
            visibility = listOf(
                TerrainPageVisibilityDiagnosticSnapshot("main", selected = true, masked = false),
                TerrainPageVisibilityDiagnosticSnapshot(
                    "shadow",
                    selected = x < 0L,
                    masked = x < 0L,
                ),
            ),
            artifactDigest = if (x < 0L) "sha256:left" else "sha256:right",
            failure = if (x < 0L) {
                TerrainFailureSnapshot(
                    category = TerrainFailureCategory.TRANSIENT,
                    phase = TerrainFailurePhase.BUILD,
                    generation = 5L,
                    retryEligibility = TerrainRetryEligibility.AfterBackoff(
                        attemptsUsed = 1,
                        maximumAttempts = 3,
                        retryAtNanos = 1_000L,
                    ),
                )
            } else {
                null
            },
        )
    }

    fun snapshot(): TerrainDiagnosticSnapshot {
        val query = query()
        val pages = listOf(pageSnapshot(1L, 0L), pageSnapshot(-1L, -1L))
        val cursor = TerrainPageCursorCodec.issue(
            diagnosticGeneration = PAGE_REGISTRY_GENERATION,
            query = query,
            lastPage = pages.first().page,
        )
        return TerrainDiagnosticSnapshot(
            schemaVersion = TerrainDiagnosticSchema.VERSION,
            diagnosticGeneration = DIAGNOSTIC_GENERATION,
            worldIdentity = TerrainWorldIdentity(
                sessionGeneration = 1L,
                normalizedWorldKey = "minecraft:overworld",
                worldEpoch = WORLD_EPOCH,
                minimumHeight = -64,
                maximumHeightExclusive = 320,
                contentGeneration = 9L,
                persistenceIdentity = "server/world",
            ),
            providers = listOf(provider()),
            pipeline = pipeline(),
            material = TerrainMaterialDiagnosticSnapshot(
                generation = 31L,
                semanticMaterialIds = listOf(
                    TerrainSemanticMaterialId("minecraft:stone"),
                    TerrainSemanticMaterialId("minecraft:dirt"),
                ),
                modelGeneration = 32L,
                atlasGeneration = 33L,
                tintGeneration = 34L,
                animationGeneration = 35L,
            ),
            coverage = TerrainCoverageDiagnosticSnapshot(
                generation = 71L,
                worldEpoch = WORLD_EPOCH,
                providerGeneration = 11L,
                stateCounts = mapOf(
                    TerrainCoverageState.READY to 2,
                    TerrainCoverageState.REQUESTED to 1,
                ),
            ),
            pageRegistryGeneration = PAGE_REGISTRY_GENERATION,
            scheduling = TerrainSharedBuildServiceSnapshot(
                runtime = TerrainBuildRuntimeSnapshot(
                    queueDepth = 2,
                    completionDepth = 3,
                    routedCompletionDepth = 2,
                    outstanding = 6,
                    outstandingEstimatedCpuNanos = 600L,
                    outstandingEstimatedOutputBytes = 6_000L,
                    active = 4,
                    queueHighWater = 8,
                    completionHighWater = 7,
                    outstandingHighWater = 12,
                    estimatedCpuHighWaterNanos = 1_200L,
                    estimatedOutputHighWaterBytes = 12_000L,
                    estimatedCpuBudgetNanos = 10_000L,
                    estimatedOutputBudgetBytes = 100_000L,
                    requested = 20L,
                    admitted = 18L,
                    rejected = 2L,
                    estimatedCpuRejected = 1L,
                    estimatedOutputRejected = 1L,
                    started = 16L,
                    completed = 13L,
                    cancelled = 1L,
                    failed = 1L,
                ),
                tenants = listOf(
                    TerrainSharedBuildTenantSnapshot(
                        ownerId = "minosoft:distant-owner",
                        tenant = TerrainSchedulerTenantId("distant:world-1"),
                        accepting = true,
                        activeBuilds = 2,
                        outstanding = 3,
                        completionDepth = 1,
                        workerContextCount = 2,
                    ),
                    TerrainSharedBuildTenantSnapshot(
                        ownerId = "minosoft:near-owner",
                        tenant = TerrainSchedulerTenantId("near:world-1"),
                        accepting = true,
                        activeBuilds = 2,
                        outstanding = 3,
                        completionDepth = 2,
                        workerContextCount = 3,
                    ),
                ),
            ),
            residency = listOf(
                TerrainResidencyDiagnosticSnapshot(
                    generation = 82L,
                    accounting = TerrainResidencyCapSnapshot(
                        scope = TerrainResidencyScope.DEVICE,
                        capBytes = 2_000L,
                        accounting = listOf(
                            TerrainResidencyCategoryAccounting(
                                category = TerrainResidencyCategory.STAGING_BUFFER,
                                residentBytes = 200L,
                                pinnedBytes = 100L,
                            ),
                        ),
                        highWaterMarkBytes = 300L,
                    ),
                ),
                TerrainResidencyDiagnosticSnapshot(
                    generation = 81L,
                    accounting = TerrainResidencyCapSnapshot(
                        scope = TerrainResidencyScope.WORLD,
                        capBytes = 1_000L,
                        accounting = listOf(
                            TerrainResidencyCategoryAccounting(
                                category = TerrainResidencyCategory.DETACHED_BUILD_SNAPSHOT,
                                residentBytes = 100L,
                            ),
                        ),
                        highWaterMarkBytes = 200L,
                    ),
                ),
            ),
            submission = TerrainSubmissionDiagnosticSnapshot(
                generation = 91L,
                deviceRuntime = TerrainDeviceRuntimeId(TerrainProcessScopeId(1L), 2L),
                latestSubmittedSerial = TerrainSubmissionSerial(3L),
                pendingSubmissionCount = 4,
                failedSubmissionCount = 5L,
                completionGeneration = 6L,
            ),
            publication = listOf(
                TerrainPublicationDiagnosticSnapshot(
                    domain = TerrainDomain.NEAR,
                    providerId = "builtin",
                    storageGeneration = 72L,
                    selectionGeneration = 73L,
                    residentPageCount = 3,
                    retainedPageCount = 3,
                    views = listOf(
                        TerrainViewVisibilityDiagnosticSnapshot(
                            viewId = "main",
                            desiredPageCount = 3,
                            visiblePageCount = 2,
                            missingPageCount = 1,
                            maskedPageCount = 0,
                        ),
                        TerrainViewVisibilityDiagnosticSnapshot(
                            viewId = "shadow",
                            desiredPageCount = 2,
                            visiblePageCount = 2,
                            missingPageCount = 0,
                            maskedPageCount = 1,
                        ),
                    ),
                ),
            ),
            pageWindow = TerrainPageDiagnosticWindow(query, pages, cursor),
        )
    }

    private fun provider(): TerrainProviderDiagnosticSnapshot {
        val descriptor = TerrainInteropDescriptor(
            providerId = "builtin",
            domains = setOf(TerrainDomain.NEAR),
            capabilities = setOf(TerrainProviderCapability.REGION_BATCHING),
            materials = setOf(TerrainMaterialClass.CUTOUT, TerrainMaterialClass.OPAQUE),
            semanticVertexLayoutId = "terrain-v1",
            physicalLayoutIds = setOf("near-v1"),
            supportedViews = setOf("shadow", "main"),
            uploadCapabilities = setOf(TerrainUploadCapability.BUFFER_UPDATE),
            shaderInputs = setOf("position"),
            lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
            tintSemantics = TerrainTintSemantics.BIOME_INPUT,
        )
        return TerrainProviderDiagnosticSnapshot("builtin", 11L, descriptor)
    }

    private fun pipeline(): TerrainPipelineDiagnosticSnapshot = TerrainPipelineDiagnosticSnapshot(
        generation = 21L,
        mode = TerrainRuntimeMode.UNIFIED,
        nearProviderId = "builtin",
        nearProviderGeneration = 11L,
        distantProviderId = null,
        distantProviderGeneration = null,
        materialGeneration = 31L,
        shaderPipelineGeneration = 41L,
        nearLayout = TerrainPhysicalLayout("near-v1", 51L, TerrainDomain.NEAR),
        distantLayout = null,
        declaredViews = setOf("shadow", "main"),
        declaredMaterialPasses = setOf("cutout", "opaque"),
        renderGraphGeneration = 61L,
    )
}
