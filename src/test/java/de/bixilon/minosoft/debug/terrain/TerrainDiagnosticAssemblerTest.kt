/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug.terrain

import de.bixilon.minosoft.gui.rendering.chunk.BuiltInChunkTerrainBackend
import de.bixilon.minosoft.gui.rendering.chunk.NearLoadedPageRegistrySnapshot
import de.bixilon.minosoft.gui.rendering.chunk.queue.loading.NearUploadPageRegistrySnapshot
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.NearMeshingPageRegistryEntry
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.NearMeshingPageRegistrySnapshot
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.near.NearTerrainPagePublicationDiagnostic
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageState
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.runtime.TerrainFailureCategory
import de.bixilon.minosoft.terrain.runtime.TerrainFailurePhase
import de.bixilon.minosoft.terrain.runtime.TerrainFailureSnapshot
import de.bixilon.minosoft.terrain.runtime.TerrainRetryEligibility
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCanonicalJson
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPagePrefixSelector
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageQuery
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageRangeDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageVisibilityDiagnosticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainDiagnosticAssemblerTest {
    @Test
    fun `legacy capture maps only frozen provider truth`() {
        val capture = TerrainDiagnosticProviderCapture.capture(
            diagnosticGeneration = 101L,
            providerGeneration = 7L,
            descriptor = descriptor(),
        )
        val response = TerrainDiagnosticAssembler.assemble(capture)
        val provider = response.snapshot.providers.single()

        assertEquals(TerrainDiagnosticAssembler.AVAILABLE_CAPABILITIES, response.capabilities.capabilities.toSet())
        assertEquals("minosoft:built-in-terrain", provider.providerId)
        assertEquals(7L, provider.generation)
        assertEquals(setOf(TerrainDomain.NEAR), provider.descriptor.domains)
        assertEquals(
            setOf(TerrainProviderCapability.AUXILIARY_VIEWS),
            provider.descriptor.capabilities,
        )
        assertEquals(
            BuiltInTerrainVertexLayout.VALUE.id.value,
            provider.descriptor.semanticVertexLayoutId,
        )
        assertEquals(
            TerrainDiagnosticCapability.entries
                .filterNot(TerrainDiagnosticAssembler.AVAILABLE_CAPABILITIES::contains)
                .map { it.name }
                .sorted(),
            response.unavailable.map { it.subject },
        )
        assertTrue(
            response.unavailable.all {
                it.code == TerrainDiagnosticRejectionCode.MISSING_CAPABILITY
            },
        )
    }

    @Test
    fun `provider-only response has deterministic schema without fabricated sections`() {
        val response = TerrainDiagnosticAssembler.assemble(
            TerrainDiagnosticProviderCapture.capture(10L, 2L, descriptor()),
        )
        val first = TerrainDiagnosticCanonicalJson.encode(response)
        val second = TerrainDiagnosticCanonicalJson.encode(response)
        val json = de.bixilon.minosoft.debug.DebugJson.MAPPER.readTree(first)

        assertEquals(first, second)
        assertEquals(10L, json.path("snapshot").path("diagnosticGeneration").longValue())
        assertEquals(1, json.path("snapshot").path("providers").size())
        assertFalse(json.path("snapshot").has("worldIdentity"))
        assertFalse(json.path("snapshot").has("pipeline"))
        assertFalse(json.path("snapshot").has("coverage"))
        assertFalse(json.path("snapshot").has("residency"))
        assertFalse(json.path("snapshot").has("submission"))
        assertNull(
            response.unavailable.firstOrNull {
                it.subject == TerrainDiagnosticCapability.PROVIDER_DESCRIPTOR.name
            },
        )
    }

    @Test
    fun `page cursor generation follows only the queried terrain domain`() {
        assertEquals(
            7L,
            TerrainProductionDiagnosticCapture.pageRegistryGeneration(TerrainDomain.NEAR, 7L, 11L),
        )
        assertEquals(
            11L,
            TerrainProductionDiagnosticCapture.pageRegistryGeneration(TerrainDomain.DISTANT, 7L, 11L),
        )
        assertEquals(
            18L,
            TerrainProductionDiagnosticCapture.pageRegistryGeneration(null, 7L, 11L),
        )
    }

    @Test
    fun `near page diagnostics project section failures without seam-column aliases`() {
        val installed = identity(y = 0L, requestRevision = 1L)
        val replacement = identity(y = 0L, requestRevision = 2L)
        val failed = identity(y = 5L, requestRevision = 3L)
        val failure = TerrainFailureSnapshot(
            category = TerrainFailureCategory.TRANSIENT,
            phase = TerrainFailurePhase.BUILD,
            generation = 4L,
            retryEligibility = TerrainRetryEligibility.AfterBackoff(1, 3, 100L),
        )
        val pages = TerrainProductionDiagnosticCapture.nearPageDiagnostics(
            loaded = NearLoadedPageRegistrySnapshot(1L, listOf(installed)),
            uploads = NearUploadPageRegistrySnapshot(0L, emptyList()),
            meshing = NearMeshingPageRegistrySnapshot(
                2L,
                listOf(
                    NearMeshingPageRegistryEntry(replacement, TerrainCoverageState.REQUESTED, failure),
                    NearMeshingPageRegistryEntry(failed, TerrainCoverageState.ABSENT, failure),
                ),
            ),
            providerId = "minosoft:test-near",
            query = TerrainPageQuery(
                worldEpoch = 7L,
                selector = TerrainPagePrefixSelector(TerrainDomain.NEAR, detailLevel = 0),
                maximumCount = 8,
            ),
            afterPage = null,
            publications = mapOf(
                installed.page to NearTerrainPagePublicationDiagnostic(
                    publicationRevision = 9L,
                    artifactDigest = "sha256:installed",
                    ranges = listOf(TerrainPageRangeDiagnosticSnapshot("opaque", 0, 64, 0, 12)),
                    visibility = listOf(
                        TerrainPageVisibilityDiagnosticSnapshot("minosoft:main", true, false),
                    ),
                ),
            ),
        )

        assertEquals(listOf(0L, 5L), pages.map { it.page.y })
        assertEquals(TerrainCoverageState.READY, pages[0].coverageState)
        assertEquals(replacement, pages[0].buildIdentity)
        assertEquals(failure, pages[0].failure)
        assertEquals("near-snapshot", pages[0].source)
        assertEquals(9L, pages[0].revisions.publicationRevision)
        assertEquals("sha256:installed", pages[0].artifactDigest)
        assertEquals("opaque", pages[0].ranges.single().partitionId)
        assertTrue(pages[0].visibility.single().selected)
        assertEquals(TerrainCoverageState.ABSENT, pages[1].coverageState)
        assertEquals(failed, pages[1].buildIdentity)
        assertEquals(failure, pages[1].failure)
    }

    private fun identity(y: Long, requestRevision: Long) = TerrainBuildIdentity(
        page = TerrainPageKey(TerrainDomain.NEAR, 0, 2L, y, 3L, 7L),
        requestRevision = requestRevision,
        capturedModelRevision = requestRevision,
        providerGeneration = 1L,
        layoutGeneration = 1L,
        materialGeneration = 1L,
        coverageGeneration = 0L,
        prioritySequence = requestRevision,
    )

    private fun descriptor() = TerrainBackendDescriptor(
        owner = BuiltInChunkTerrainBackend.OWNER,
        implementation = "minosoft-built-in-chunk-renderer",
        materials = TerrainMaterialClass.entries.toSet(),
        vertexLayout = BuiltInTerrainVertexLayout.VALUE,
        supportsAuxiliaryViews = true,
    )
}
