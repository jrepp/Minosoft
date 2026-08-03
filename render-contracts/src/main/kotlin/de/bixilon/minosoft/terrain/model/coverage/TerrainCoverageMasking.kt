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

package de.bixilon.minosoft.terrain.model.coverage

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey

data class TerrainCoverageTransitionPolicy(
    val enabled: Boolean,
    val durationFrames: Int,
) {
    init {
        require(durationFrames >= 0) { "Terrain coverage transition duration must not be negative" }
        require(enabled || durationFrames == 0) {
            "Disabled terrain coverage transition must not retain a duration"
        }
        require(!enabled || durationFrames > 0) {
            "Enabled terrain coverage transition must have a positive duration"
        }
    }

    companion object {
        val CONSERVATIVE_OVERLAP = TerrainCoverageTransitionPolicy(false, 0)
    }
}

data class TerrainCoverageMaskDecision(
    val drawNear: Boolean,
    val drawDistant: Boolean,
    val nearWeight: Double,
    val distantWeight: Double,
) {
    init {
        require(nearWeight in 0.0..1.0 && distantWeight in 0.0..1.0)
        require(drawNear || nearWeight == 0.0)
        require(drawDistant || distantWeight == 0.0)
    }
}

object TerrainCoverageMasking {
    fun decide(
        cell: TerrainCoverageCell?,
        policy: TerrainCoverageTransitionPolicy,
    ): TerrainCoverageMaskDecision {
        if (cell == null || !cell.contributesCoverage) {
            return TerrainCoverageMaskDecision(false, true, 0.0, 1.0)
        }
        if (!policy.enabled) {
            // Rollback route: overlap behind ready near geometry and never
            // exclude the distant page prematurely.
            return TerrainCoverageMaskDecision(true, true, 1.0, 1.0)
        }
        if (cell.state == TerrainCoverageState.RETIRING) {
            val distant = progress(cell.transitionAgeFrames, policy.durationFrames)
            return TerrainCoverageMaskDecision(
                drawNear = distant < 1.0,
                drawDistant = true,
                nearWeight = 1.0 - distant,
                distantWeight = distant,
            )
        }
        val near = progress(cell.coverageAgeFrames, policy.durationFrames)
        return TerrainCoverageMaskDecision(
            drawNear = true,
            drawDistant = near < 1.0,
            nearWeight = near,
            distantWeight = 1.0 - near,
        )
    }

    private fun progress(ageFrames: Int, durationFrames: Int): Double =
        (ageFrames.toDouble() / durationFrames.toDouble()).coerceIn(0.0, 1.0)
}

enum class TerrainCoverageRelationship {
    NONE,
    PARTIAL,
    FULL,
}

/**
 * Frame-pinned, page-granular distant mask derived from near coverage cells.
 *
 * A distant page is hidden only when every base chunk it spans has retained
 * surface coverage. Different pages use a stable threshold within the policy
 * duration, producing a deterministic spatial transition without requiring a
 * mutable per-draw clock. Missing cells, wrong-world snapshots, and unsupported
 * page spans all fail open to distant geometry.
 */
class TerrainCoveragePageMask(
    private val snapshot: TerrainCoverageSnapshot?,
    private val policy: TerrainCoverageTransitionPolicy,
) {
    private data class CellCoordinate(val x: Long, val z: Long)

    private val nearCells = snapshot?.cells?.filter {
        it.page.domain == TerrainDomain.NEAR && it.page.detailLevel == 0
    }.orEmpty()
    private val nearCellsByCoordinate = nearCells.groupByTo(
        LinkedHashMap(),
    ) { CellCoordinate(it.page.x, it.page.z) }
    private val coverageCountsByDetail = HashMap<Int, Map<CellCoordinate, Long>>()
    private val thresholdPolicies = if (policy.enabled) {
        Array(policy.durationFrames) { threshold ->
            TerrainCoverageTransitionPolicy(true, threshold + 1)
        }
    } else {
        emptyArray()
    }

    fun drawDistant(page: TerrainPageKey): Boolean {
        require(page.domain == TerrainDomain.DISTANT) { "Coverage page masking requires a distant page" }
        if (!policy.enabled) return true
        if (relationship(page) != TerrainCoverageRelationship.FULL) return true
        val bounds = bounds(page) ?: return true
        val thresholdPolicy = thresholdPolicies[stableThreshold(page, policy.durationFrames) - 1]
        for (z in bounds.minimumZ..<bounds.maximumZ) {
            for (x in bounds.minimumX..<bounds.maximumX) {
                val cell = nearCellsByCoordinate[CellCoordinate(x, z)]
                    ?.firstOrNull(TerrainCoverageCell::contributesCoverage)
                    ?: return true
                if (TerrainCoverageMasking.decide(cell, thresholdPolicy).drawDistant) return true
            }
        }
        return false
    }

    /**
     * Spatial near ownership for one distant page, independent of transition
     * age. Invalid worlds and coordinate spans fail open as [TerrainCoverageRelationship.NONE].
     */
    fun relationship(page: TerrainPageKey): TerrainCoverageRelationship {
        require(page.domain == TerrainDomain.DISTANT) { "Coverage relationship requires a distant page" }
        val snapshot = snapshot ?: return TerrainCoverageRelationship.NONE
        if (snapshot.worldEpoch != page.worldEpoch) return TerrainCoverageRelationship.NONE
        val bounds = bounds(page) ?: return TerrainCoverageRelationship.NONE
        val covered = coverageCounts(page.detailLevel)[CellCoordinate(page.x, page.z)] ?: 0L
        return when (covered) {
            0L -> TerrainCoverageRelationship.NONE
            bounds.expectedCells -> TerrainCoverageRelationship.FULL
            else -> TerrainCoverageRelationship.PARTIAL
        }
    }

    private fun coverageCounts(detailLevel: Int): Map<CellCoordinate, Long> =
        coverageCountsByDetail.getOrPut(detailLevel) {
            val span = 1L shl detailLevel
            val counts = HashMap<CellCoordinate, Long>()
            for ((coordinate, cells) in nearCellsByCoordinate) {
                if (cells.none(TerrainCoverageCell::contributesCoverage)) continue
                val parent = CellCoordinate(
                    Math.floorDiv(coordinate.x, span),
                    Math.floorDiv(coordinate.z, span),
                )
                counts[parent] = Math.addExact(counts[parent] ?: 0L, 1L)
            }
            counts
        }

    private data class Bounds(
        val minimumX: Long,
        val maximumX: Long,
        val minimumZ: Long,
        val maximumZ: Long,
        val expectedCells: Long,
    )

    private fun bounds(page: TerrainPageKey): Bounds? {
        if (page.detailLevel > MAXIMUM_CHECKED_DETAIL_LEVEL) return null
        val span = 1L shl page.detailLevel
        val expectedCells = exactOrNull { Math.multiplyExact(span, span) } ?: return null
        val minimumX = exactOrNull { Math.multiplyExact(page.x, span) } ?: return null
        val minimumZ = exactOrNull { Math.multiplyExact(page.z, span) } ?: return null
        val maximumX = exactOrNull { Math.addExact(minimumX, span) } ?: return null
        val maximumZ = exactOrNull { Math.addExact(minimumZ, span) } ?: return null
        return Bounds(minimumX, maximumX, minimumZ, maximumZ, expectedCells)
    }

    private fun stableThreshold(page: TerrainPageKey, durationFrames: Int): Int {
        var mixed = page.x * -7046029254386353131L
        mixed = mixed xor (page.z * -4417276706812531889L)
        mixed = mixed xor (page.detailLevel.toLong() * 1609587929392839161L)
        mixed = mixed xor (mixed ushr 33)
        return Math.floorMod(mixed, durationFrames.toLong()).toInt() + 1
    }

    private inline fun exactOrNull(operation: () -> Long): Long? = try {
        operation()
    } catch (_: ArithmeticException) {
        null
    }

    private companion object {
        const val MAXIMUM_CHECKED_DETAIL_LEVEL = 30
    }
}
