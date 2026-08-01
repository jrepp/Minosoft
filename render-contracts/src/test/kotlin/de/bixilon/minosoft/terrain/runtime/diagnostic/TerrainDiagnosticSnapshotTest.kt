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

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerrainDiagnosticSnapshotTest {
    @Test
    fun `atomic snapshot aligns world pipeline material and provider generations`() {
        val snapshot = TerrainDiagnosticFixtures.snapshot()

        assertEquals(snapshot.worldIdentity.worldEpoch, snapshot.coverage.worldEpoch)
        assertEquals(snapshot.pipeline.materialGeneration, snapshot.material.generation)
        assertEquals(
            listOf("builtin"),
            snapshot.providers.map(TerrainProviderDiagnosticSnapshot::providerId),
        )
        assertEquals(
            listOf("WORLD", "DEVICE"),
            snapshot.residency.map { it.scope.name },
        )

        assertThrows<IllegalArgumentException> {
            TerrainDiagnosticSnapshot(
                schemaVersion = snapshot.schemaVersion,
                diagnosticGeneration = snapshot.diagnosticGeneration,
                worldIdentity = snapshot.worldIdentity,
                providers = snapshot.providers,
                pipeline = snapshot.pipeline,
                material = TerrainMaterialDiagnosticSnapshot(
                    generation = snapshot.material.generation + 1L,
                    semanticMaterialIds = snapshot.material.semanticMaterialIds,
                    modelGeneration = snapshot.material.modelGeneration,
                    atlasGeneration = snapshot.material.atlasGeneration,
                    tintGeneration = snapshot.material.tintGeneration,
                    animationGeneration = snapshot.material.animationGeneration,
                ),
                coverage = snapshot.coverage,
                pageRegistryGeneration = snapshot.pageRegistryGeneration,
                scheduling = snapshot.scheduling,
                residency = snapshot.residency,
                submission = snapshot.submission,
                publication = snapshot.publication,
                pageWindow = snapshot.pageWindow,
            )
        }
    }

    @Test
    fun `page cursor remains valid across frames while the page registry is unchanged`() {
        val source = TerrainDiagnosticFixtures.snapshot()
        val laterFrame = TerrainDiagnosticSnapshot(
            schemaVersion = source.schemaVersion,
            diagnosticGeneration = Math.addExact(source.diagnosticGeneration, 1L),
            worldIdentity = source.worldIdentity,
            providers = source.providers,
            pipeline = source.pipeline,
            material = source.material,
            coverage = source.coverage,
            pageRegistryGeneration = source.pageRegistryGeneration,
            scheduling = source.scheduling,
            residency = source.residency,
            submission = source.submission,
            publication = source.publication,
            pageWindow = source.pageWindow,
        )

        assertEquals(source.pageWindow?.nextCursor, laterFrame.pageWindow?.nextCursor)
    }

    @Test
    fun `canonical snapshot matches checked schema fixture`() {
        assertEquals(
            golden("terrain-diagnostic-snapshot-v1.json"),
            TerrainDiagnosticCanonicalJson.encode(TerrainDiagnosticFixtures.snapshot()),
        )
    }

    @Test
    fun `page detail bounds ranges views provenance and digest`() {
        val page = TerrainDiagnosticFixtures.pageSnapshot(-1L, -1L)

        assertEquals("near-snapshot", page.source)
        assertEquals(4L, page.revisions.sourceRevision)
        assertEquals(72L, page.revisions.publicationRevision)
        assertEquals("opaque", page.ranges.single().partitionId)
        assertEquals(listOf("main", "shadow"), page.visibility.map { it.viewId })
        assertEquals("sha256:left", page.artifactDigest)

        assertFailsWith<IllegalArgumentException> {
            TerrainPageDiagnosticSnapshot(
                page = page.page,
                coverageState = page.coverageState,
                buildIdentity = page.buildIdentity,
                providerId = page.providerId,
                visibility = listOf(
                    TerrainPageVisibilityDiagnosticSnapshot("main", selected = false, masked = true),
                ),
            )
        }
    }

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/de/bixilon/minosoft/terrain/runtime/diagnostic/$name"))
            .readText()
            .trimEnd()
}
