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

import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactDifference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainDualBuildFixtureTest {
    @Test
    fun `near and distant dual builds are semantically identical`() {
        listOf(
            TerrainDualBuildFixture.NEAR_SOLID_QUAD,
            TerrainDualBuildFixture.DISTANT_VERTICAL_FACE,
        ).forEach { fixture ->
            val comparison = TerrainDualBuildFixtures.compare(fixture)
            assertTrue(comparison.equivalent)
            assertEquals(comparison.referenceDigest, comparison.candidateDigest)
            assertTrue(comparison.differences.isEmpty())
        }
    }

    @Test
    fun `dual build comparison reports an intentional content difference`() {
        val comparison = TerrainDualBuildFixtures.compare(TerrainDualBuildFixture.DETECTION_CONTENT_MISMATCH)

        assertFalse(comparison.equivalent)
        assertEquals(setOf(TerrainArtifactDifference.CONTENT), comparison.differences)
        assertFalse(comparison.referenceDigest == comparison.candidateDigest)
    }
}
