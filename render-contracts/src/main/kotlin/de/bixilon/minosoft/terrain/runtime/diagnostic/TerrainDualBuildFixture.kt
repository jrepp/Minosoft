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

import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactBounds
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactComparison
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactCoordinate
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactDifference
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactStream
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.model.mesh.compareSemantic

enum class TerrainDualBuildFixture(val wireName: String) {
    NEAR_SOLID_QUAD("near-solid-quad"),
    DISTANT_VERTICAL_FACE("distant-vertical-face"),
    DETECTION_CONTENT_MISMATCH("detection-content-mismatch"),
    ;

    companion object {
        fun fromWireName(value: String): TerrainDualBuildFixture? = entries.firstOrNull { it.wireName == value }
    }
}

data class TerrainDualBuildComparison(
    val fixture: TerrainDualBuildFixture,
    val equivalent: Boolean,
    val referenceDigest: String,
    val candidateDigest: String,
    val differences: Set<TerrainArtifactDifference>,
)

/** Headless dual-build fixtures hash both candidates and publish neither. */
object TerrainDualBuildFixtures {
    fun compare(fixture: TerrainDualBuildFixture): TerrainDualBuildComparison {
        val reference = build(fixture, reference = true)
        val candidate = try {
            build(fixture, reference = false)
        } catch (failure: Throwable) {
            try {
                reference.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return try {
            fixture.result(reference.compareSemantic(candidate))
        } finally {
            var failure: Throwable? = null
            try {
                candidate.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                reference.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            failure?.let { throw it }
        }
    }

    private fun build(fixture: TerrainDualBuildFixture, reference: Boolean): TerrainMeshArtifact {
        val domain = if (fixture == TerrainDualBuildFixture.DISTANT_VERTICAL_FACE) {
            TerrainDomain.DISTANT
        } else {
            TerrainDomain.NEAR
        }
        val page = TerrainPageKey(domain, if (domain == TerrainDomain.DISTANT) 2 else 0, 3, 0, -2, 1)
        val identity = TerrainBuildIdentity(
            page = page,
            requestRevision = 1,
            capturedModelRevision = 2,
            providerGeneration = 3,
            layoutGeneration = 4,
            materialGeneration = 5,
            coverageGeneration = 6,
            prioritySequence = 7,
            sourceDataRevision = 8,
        )
        val source = if (domain == TerrainDomain.DISTANT) {
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        } else {
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        }
        val vertexBytes = if (reference) {
            source.copyOf()
        } else {
            source.indices.map { source[it] }.toByteArray().also {
                if (fixture == TerrainDualBuildFixture.DETECTION_CONTENT_MISMATCH) it[it.lastIndex] = 99
            }
        }
        val stream = TerrainArtifactStream(
            partitionId = if (domain == TerrainDomain.DISTANT) "distant-solid" else "near-solid",
            material = TerrainSemanticMaterialId("minosoft:terrain/opaque"),
            vertexStrideBytes = if (domain == TerrainDomain.DISTANT) 8 else 4,
            vertexBytes = vertexBytes,
            indexElementBytes = 1,
            indexBytes = byteArrayOf(0, 1, 2, 2, 3, 0),
        )
        return TerrainMeshArtifact(
            identity = identity,
            streams = listOf(stream),
            bounds = TerrainArtifactBounds(
                TerrainArtifactCoordinate(0, 0, 0),
                TerrainArtifactCoordinate(1, if (domain == TerrainDomain.DISTANT) 4 else 1, 1),
            ),
            connectivityBits = 0x15,
            coverageContribution = listOf(page),
        )
    }

    private fun TerrainDualBuildFixture.result(comparison: TerrainArtifactComparison) =
        TerrainDualBuildComparison(
            fixture = this,
            equivalent = comparison.equivalent,
            referenceDigest = comparison.expectedDigest.encodedValue,
            candidateDigest = comparison.actualDigest.encodedValue,
            differences = comparison.differences,
        )
}
