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
                residency = snapshot.residency,
                submission = snapshot.submission,
                pageWindow = snapshot.pageWindow,
            )
        }
    }

    @Test
    fun `canonical snapshot matches checked schema fixture`() {
        assertEquals(
            golden("terrain-diagnostic-snapshot-v1.json"),
            TerrainDiagnosticCanonicalJson.encode(TerrainDiagnosticFixtures.snapshot()),
        )
    }

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/de/bixilon/minosoft/terrain/runtime/diagnostic/$name"))
            .readText()
            .trimEnd()
}
