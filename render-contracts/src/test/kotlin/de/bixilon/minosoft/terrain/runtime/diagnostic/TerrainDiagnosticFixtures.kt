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
import de.bixilon.minosoft.terrain.runtime.TerrainProcessScopeId
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCapSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCategory
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCategoryAccounting
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyScope
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial

internal object TerrainDiagnosticFixtures {
    const val DIAGNOSTIC_GENERATION: Long = 101L
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
        )
    }

    fun snapshot(): TerrainDiagnosticSnapshot {
        val query = query()
        val pages = listOf(pageSnapshot(1L, 0L), pageSnapshot(-1L, -1L))
        val cursor = TerrainPageCursorCodec.issue(
            diagnosticGeneration = DIAGNOSTIC_GENERATION,
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
            pageRegistryGeneration = 111L,
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
