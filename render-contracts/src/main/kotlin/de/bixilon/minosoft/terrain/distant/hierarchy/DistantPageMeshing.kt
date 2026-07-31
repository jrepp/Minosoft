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

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import kotlin.math.max
import kotlin.math.min

class DistantVerticalPage(
    val key: TerrainPageKey,
    val width: Int,
    val originY: Int,
    val sourceRevision: Long,
    val completeness: DistantSourceCompleteness,
    columns: Collection<DistantVerticalColumn>,
) {
    val cellSizeBlocks: Int = validateGeometry(key, width)
    val pageSizeBlocks: Int = Math.multiplyExact(width, cellSizeBlocks)
    val originX: Long = Math.multiplyExact(key.x, pageSizeBlocks.toLong())
    val originZ: Long = Math.multiplyExact(key.z, pageSizeBlocks.toLong())
    val maximumXExclusive: Long = Math.addExact(originX, pageSizeBlocks.toLong())
    val maximumZExclusive: Long = Math.addExact(originZ, pageSizeBlocks.toLong())
    val columns: List<DistantVerticalColumn> = java.util.List.copyOf(columns)
    val semanticDigest: String = digest(this.columns)

    init {
        require(sourceRevision >= 0L) { "Distant page source revision must not be negative" }
        require(this.columns.size == Math.multiplyExact(width, width)) {
            "Distant page column count does not match its width"
        }
        for (column in this.columns) {
            for (run in column.runs) {
                require(run.minimumY >= originY) { "Distant run precedes the page vertical origin" }
                Math.subtractExact(run.maximumYExclusive, originY)
            }
        }
    }

    operator fun get(x: Int, z: Int): DistantVerticalColumn {
        require(x in 0 until width && z in 0 until width) { "Distant page column is out of bounds" }
        return columns[z * width + x]
    }

    fun contains(worldX: Long, worldZ: Long): Boolean =
        worldX >= originX && worldX < maximumXExclusive && worldZ >= originZ && worldZ < maximumZExclusive

    fun columnAt(worldX: Long, worldZ: Long): DistantVerticalColumn? {
        if (!contains(worldX, worldZ)) return null
        val x = Math.floorDiv(Math.subtractExact(worldX, originX), cellSizeBlocks.toLong()).toInt()
        val z = Math.floorDiv(Math.subtractExact(worldZ, originZ), cellSizeBlocks.toLong()).toInt()
        return get(x, z)
    }

    private companion object {
        const val MAXIMUM_WIDTH = 64
        const val DIGEST_SCHEMA = 1

        fun validateGeometry(key: TerrainPageKey, width: Int): Int {
            DistantPageHierarchy.requireDistant(key)
            require(width > 0 && width <= MAXIMUM_WIDTH && width.countOneBits() == 1) {
                "Distant page width must be a power of two within 1..$MAXIMUM_WIDTH"
            }
            return 1 shl key.detailLevel
        }

        fun digest(columns: List<DistantVerticalColumn>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DIGEST_SCHEMA.toByte())
            digest.putInt(columns.size)
            columns.forEach { digest.putText(it.digest) }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

/** Deterministic 2x2 reduction of four child pages into their parent page. */
object DistantVerticalPageReducer {
    fun reduce(
        parent: TerrainPageKey,
        children: Collection<DistantVerticalPage>,
        sourceRevision: Long,
    ): DistantVerticalPage {
        DistantPageHierarchy.requireDistant(parent)
        require(parent.detailLevel > 0) { "A distant base page cannot be reduced from children" }
        require(sourceRevision >= 0L) { "Distant reduced source revision must not be negative" }
        val expected = DistantPageHierarchy.children(parent)
        require(children.size == expected.size) { "Distant parent reduction requires exactly four children" }
        val byKey = children.associateBy(DistantVerticalPage::key)
        require(byKey.size == 4 && byKey.keys == expected.toSet()) {
            "Distant parent reduction requires its exact four children"
        }
        val ordered = expected.map(byKey::getValue)
        val width = ordered.first().width
        val originY = ordered.first().originY
        require(ordered.all { it.width == width && it.originY == originY }) {
            "Distant parent children must share width and vertical origin"
        }
        val columns = ArrayList<DistantVerticalColumn>(width * width)
        for (z in 0 until width) {
            for (x in 0 until width) {
                val samples = ArrayList<DistantVerticalColumn>(4)
                for (sampleZ in 0..1) {
                    for (sampleX in 0..1) {
                        val fineX = x * 2 + sampleX
                        val fineZ = z * 2 + sampleZ
                        val childIndex = (if (fineZ >= width) 2 else 0) + if (fineX >= width) 1 else 0
                        samples += ordered[childIndex][fineX % width, fineZ % width]
                    }
                }
                columns += reduceColumns(samples)
            }
        }
        val completeness = if (ordered.all { it.completeness == DistantSourceCompleteness.COMPLETE }) {
            DistantSourceCompleteness.COMPLETE
        } else {
            DistantSourceCompleteness.PARTIAL
        }
        return DistantVerticalPage(parent, width, originY, sourceRevision, completeness, columns)
    }

    private fun reduceColumns(columns: List<DistantVerticalColumn>): DistantVerticalColumn {
        val boundaryCapacity = columns.sumOf { Math.multiplyExact(it.runs.size, 2) }
        if (boundaryCapacity < 2) return DistantVerticalColumn(emptyList())
        val boundaries = IntArray(boundaryCapacity)
        var boundaryCount = 0
        for (column in columns) {
            for (run in column.runs) {
                boundaries[boundaryCount++] = run.minimumY
                boundaries[boundaryCount++] = run.maximumYExclusive
            }
        }
        java.util.Arrays.sort(boundaries, 0, boundaryCount)
        val reduced = ArrayList<DistantColumnRun>()
        val candidates = arrayOfNulls<DistantColumnRun>(columns.size)
        var boundaryIndex = boundaryCount - 1
        var upper = boundaries[boundaryIndex]
        while (boundaryIndex >= 0) {
            do {
                boundaryIndex--
            } while (boundaryIndex >= 0 && boundaries[boundaryIndex] == upper)
            if (boundaryIndex < 0) break
            val lower = boundaries[boundaryIndex]
            var candidateCount = 0
            for (column in columns) {
                val candidate = column.runs.firstOrNull {
                    it.minimumY <= lower && it.maximumYExclusive >= upper
                } ?: continue
                candidates[candidateCount++] = candidate
            }
            if (candidateCount == 0) {
                upper = lower
                continue
            }
            val representative = representative(candidates, candidateCount)
            val interval = copyInterval(representative, lower, upper)
            val previous = reduced.lastOrNull()
            if (previous != null && previous.minimumY == interval.maximumYExclusive &&
                sameSemantic(previous, interval)
            ) {
                reduced[reduced.lastIndex] = copyInterval(interval, interval.minimumY, previous.maximumYExclusive)
            } else {
                reduced += interval
            }
            upper = lower
        }
        val column = DistantVerticalColumn(reduced)
        return if (column.runs.size > DistantVerticalColumn.MAXIMUM_RUNS) {
            DistantColumnReducer.reduce(column, DistantVerticalColumn.MAXIMUM_RUNS)
        } else {
            column
        }
    }

    private fun representative(candidates: Array<DistantColumnRun?>, count: Int): DistantColumnRun {
        var best = checkNotNull(candidates[0])
        var bestPriority = semanticPriority(best, semanticCount(candidates, count, best))
        var bestOrder: String? = null
        for (index in 1 until count) {
            val candidate = checkNotNull(candidates[index])
            if ((0 until index).any { sameSemantic(checkNotNull(candidates[it]), candidate) }) continue
            val priority = semanticPriority(candidate, semanticCount(candidates, count, candidate))
            if (priority < bestPriority) continue
            if (priority == bestPriority) {
                val candidateOrder = semanticOrder(candidate)
                val currentOrder = bestOrder ?: semanticOrder(best).also { bestOrder = it }
                if (candidateOrder >= currentOrder) continue
                bestOrder = candidateOrder
            } else {
                bestOrder = null
            }
            best = candidate
            bestPriority = priority
        }
        return best
    }

    private fun semanticCount(
        candidates: Array<DistantColumnRun?>,
        count: Int,
        target: DistantColumnRun,
    ): Int = (0 until count).count { sameSemantic(checkNotNull(candidates[it]), target) }

    private fun sameSemantic(first: DistantColumnRun, second: DistantColumnRun): Boolean =
        first.material == second.material && first.fluid == second.fluid &&
            first.blockLight == second.blockLight && first.skyLight == second.skyLight &&
            first.tint == second.tint && first.flags == second.flags && first.confidence == second.confidence

    private fun semanticPriority(run: DistantColumnRun, count: Int): Long {
        var priority = count.toLong() * 1_000_000_000L + run.confidence
        if (run.fluid != null) priority += 4_000_000_000_000L
        if (DistantRunFlag.EMISSIVE in run.flags) priority += 2_000_000_000_000L
        if (DistantRunFlag.OPAQUE in run.flags) priority += 1_000_000_000_000L
        priority += (run.blockLight + run.skyLight) * 1_000_000L
        return priority
    }

    private fun copyInterval(source: DistantColumnRun, minimumY: Int, maximumYExclusive: Int) =
        DistantColumnRun(
            minimumY,
            Math.subtractExact(maximumYExclusive, minimumY),
            source.material,
            source.fluid,
            source.blockLight,
            source.skyLight,
            source.tint,
            source.flags,
            source.confidence,
        )

    private fun semanticOrder(run: DistantColumnRun): String = buildString {
        append(run.material?.value.orEmpty()).append('|')
        append(run.fluid?.material?.value.orEmpty()).append('|').append(run.fluid?.level ?: -1).append('|')
        append(run.fluid?.classification.orEmpty()).append('|').append(run.blockLight).append('|')
        append(run.skyLight).append('|').append(run.tint?.resolvedRgb ?: -1).append('|')
        append(run.tint?.biomeInput.orEmpty()).append('|').append(run.tint?.generation ?: -1L).append('|')
        append(run.flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) }).append('|').append(run.confidence)
    }
}

data class DistantNeighbourSample(
    val page: DistantVerticalPage,
    val column: DistantVerticalColumn,
)

class DistantPageNeighbourhood(
    val subject: DistantVerticalPage,
    neighbours: Collection<DistantVerticalPage>,
) {
    val neighbours: List<DistantVerticalPage> = Collections.unmodifiableList(
        neighbours.distinctBy(DistantVerticalPage::key).sortedWith { first, second ->
            DistantPageHierarchy.order.compare(first.key, second.key)
        },
    )

    init {
        require(this.neighbours.none { it.key == subject.key }) { "Distant neighbourhood must not contain its subject" }
        require(this.neighbours.all { it.key.worldEpoch == subject.key.worldEpoch }) {
            "Distant neighbours must share the subject world epoch"
        }
        require(this.neighbours.all { kotlin.math.abs(it.key.detailLevel - subject.key.detailLevel) <= 1 }) {
            "Distant neighbour detail differs by more than one level"
        }
        for (first in this.neighbours.indices) {
            for (second in first + 1 until this.neighbours.size) {
                require(!overlaps(this.neighbours[first], this.neighbours[second])) {
                    "Distant neighbour pages must not overlap"
                }
            }
        }
    }

    fun sample(worldX: Long, worldZ: Long): DistantNeighbourSample? {
        for (page in neighbours) {
            val column = page.columnAt(worldX, worldZ) ?: continue
            return DistantNeighbourSample(page, column)
        }
        return null
    }
}

enum class DistantFaceDirection {
    UP,
    DOWN,
    NORTH,
    SOUTH,
    WEST,
    EAST,
}

class DistantMeshQuad(
    val direction: DistantFaceDirection,
    val plane: Int,
    val minimumU: Int,
    val maximumUExclusive: Int,
    val minimumV: Int,
    val maximumVExclusive: Int,
    val material: TerrainSemanticMaterialId,
    val fluid: DistantFluidSample?,
    val blockLight: Int,
    val skyLight: Int,
    val tint: DistantTintSample?,
    flags: Collection<DistantRunFlag>,
    val confidence: Int,
    val fallback: Boolean,
) {
    val flags: Set<DistantRunFlag> = java.util.Set.copyOf(flags)

    init {
        require(minimumU < maximumUExclusive && minimumV < maximumVExclusive) {
            "Distant mesh quad must have positive area"
        }
        require(blockLight in 0..15 && skyLight in 0..15) { "Distant mesh light is invalid" }
        require(confidence in 0..100) { "Distant mesh confidence is invalid" }
    }

    internal fun copy(
        maximumUExclusive: Int = this.maximumUExclusive,
        maximumVExclusive: Int = this.maximumVExclusive,
    ) = DistantMeshQuad(
        direction, plane, minimumU, maximumUExclusive, minimumV, maximumVExclusive,
        material, fluid, blockLight, skyLight, tint, flags, confidence, fallback,
    )
}

class DistantPageMeshArtifact(
    val page: TerrainPageKey,
    val sourceRevision: Long,
    val sourceDigest: String,
    val primitiveFaceCount: Int,
    quads: Collection<DistantMeshQuad>,
) {
    val quads: List<DistantMeshQuad> = Collections.unmodifiableList(quads.sortedWith(QUAD_ORDER))
    val mergedFaceCount: Int = this.quads.size
    val fallbackFaceCount: Int = this.quads.count(DistantMeshQuad::fallback)
    val fluidFaceCount: Int = this.quads.count { it.fluid != null }
    val vertexCount: Int = Math.multiplyExact(mergedFaceCount, 4)
    val indexCount: Int = Math.multiplyExact(mergedFaceCount, 6)
    val digest: String = digest()

    init {
        DistantPageHierarchy.requireDistant(page)
        require(sourceRevision >= 0L) { "Distant mesh source revision must not be negative" }
        require(sourceDigest.isNotBlank()) { "Distant mesh source digest must not be blank" }
        require(primitiveFaceCount >= mergedFaceCount) { "Distant greedy meshing increased face count" }
    }

    private fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DIGEST_SCHEMA.toByte())
        digest.putInt(page.detailLevel)
        digest.putLong(page.x)
        digest.putLong(page.z)
        digest.putLong(page.worldEpoch)
        digest.putLong(sourceRevision)
        digest.putText(sourceDigest)
        digest.putInt(primitiveFaceCount)
        digest.putInt(quads.size)
        for (quad in quads) {
            digest.update(quad.direction.ordinal.toByte())
            digest.putInt(quad.plane)
            digest.putInt(quad.minimumU)
            digest.putInt(quad.maximumUExclusive)
            digest.putInt(quad.minimumV)
            digest.putInt(quad.maximumVExclusive)
            digest.putText(quad.material.value)
            digest.putText(quad.fluid?.material?.value.orEmpty())
            digest.putInt(quad.fluid?.level ?: -1)
            digest.putText(quad.fluid?.classification.orEmpty())
            digest.putInt(quad.blockLight)
            digest.putInt(quad.skyLight)
            digest.putInt(quad.tint?.resolvedRgb ?: -1)
            digest.putText(quad.tint?.biomeInput.orEmpty())
            digest.putLong(quad.tint?.generation ?: -1L)
            digest.putInt(quad.flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) })
            digest.putInt(quad.confidence)
            digest.update(if (quad.fallback) 1 else 0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val DIGEST_SCHEMA = 1
        val QUAD_ORDER = compareBy<DistantMeshQuad>(
            { it.direction.ordinal },
            DistantMeshQuad::plane,
            DistantMeshQuad::minimumV,
            DistantMeshQuad::minimumU,
            { it.material.value },
            { it.fluid?.material?.value.orEmpty() },
            DistantMeshQuad::fallback,
        )
    }
}

object DistantPageMesher {
    fun mesh(
        page: DistantVerticalPage,
        neighbourhood: DistantPageNeighbourhood = DistantPageNeighbourhood(page, emptyList()),
        maximumFallbackDepth: Int = DEFAULT_FALLBACK_DEPTH,
    ): DistantPageMeshArtifact {
        require(neighbourhood.subject === page || neighbourhood.subject.key == page.key) {
            "Distant neighbourhood belongs to another page"
        }
        require(maximumFallbackDepth > 0) { "Distant fallback depth must be positive" }
        val faces = ArrayList<DistantMeshQuad>()
        for (z in 0 until page.width) {
            for (x in 0 until page.width) {
                val column = page[x, z]
                emitHorizontalFaces(page, x, z, column, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.NORTH, maximumFallbackDepth, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.SOUTH, maximumFallbackDepth, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.WEST, maximumFallbackDepth, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.EAST, maximumFallbackDepth, faces)
            }
        }
        val merged = greedyMerge(faces)
        return DistantPageMeshArtifact(page.key, page.sourceRevision, page.semanticDigest, faces.size, merged)
    }

    private fun emitHorizontalFaces(
        page: DistantVerticalPage,
        x: Int,
        z: Int,
        column: DistantVerticalColumn,
        destination: MutableList<DistantMeshQuad>,
    ) {
        val minimumX = x * page.cellSizeBlocks
        val minimumZ = z * page.cellSizeBlocks
        for (run in column.runs) {
            if (!isRenderable(run)) continue
            val above = column.runs.firstOrNull { it.minimumY == run.maximumYExclusive && it.material != null }
            if (above == null || !occludes(run, above)) {
                destination += quad(
                    DistantFaceDirection.UP,
                    run.maximumYExclusive - page.originY,
                    minimumX,
                    minimumX + page.cellSizeBlocks,
                    minimumZ,
                    minimumZ + page.cellSizeBlocks,
                    run,
                )
            }
            val below = column.runs.firstOrNull { it.maximumYExclusive == run.minimumY && it.material != null }
            if (below == null || !occludes(run, below)) {
                destination += quad(
                    DistantFaceDirection.DOWN,
                    run.minimumY - page.originY,
                    minimumX,
                    minimumX + page.cellSizeBlocks,
                    minimumZ,
                    minimumZ + page.cellSizeBlocks,
                    run,
                )
            }
        }
    }

    private fun emitDirection(
        page: DistantVerticalPage,
        neighbourhood: DistantPageNeighbourhood,
        x: Int,
        z: Int,
        column: DistantVerticalColumn,
        direction: DistantFaceDirection,
        maximumFallbackDepth: Int,
        destination: MutableList<DistantMeshQuad>,
    ) {
        val localNeighbourX = x + when (direction) {
            DistantFaceDirection.WEST -> -1
            DistantFaceDirection.EAST -> 1
            else -> 0
        }
        val localNeighbourZ = z + when (direction) {
            DistantFaceDirection.NORTH -> -1
            DistantFaceDirection.SOUTH -> 1
            else -> 0
        }
        if (localNeighbourX in 0 until page.width && localNeighbourZ in 0 until page.width) {
            emitComparedSegment(
                page,
                x,
                z,
                column,
                page[localNeighbourX, localNeighbourZ],
                direction,
                segmentOffset = 0,
                segmentLength = page.cellSizeBlocks,
                destination,
            )
            return
        }

        var offset = 0
        while (offset < page.cellSizeBlocks) {
            val worldX = when (direction) {
                DistantFaceDirection.WEST -> page.originX + x.toLong() * page.cellSizeBlocks - 1L
                DistantFaceDirection.EAST -> page.originX + (x + 1L) * page.cellSizeBlocks
                else -> page.originX + x.toLong() * page.cellSizeBlocks + offset
            }
            val worldZ = when (direction) {
                DistantFaceDirection.NORTH -> page.originZ + z.toLong() * page.cellSizeBlocks - 1L
                DistantFaceDirection.SOUTH -> page.originZ + (z + 1L) * page.cellSizeBlocks
                else -> page.originZ + z.toLong() * page.cellSizeBlocks + offset
            }
            val sample = neighbourhood.sample(worldX, worldZ)
            val neighbourSegmentLength = sample?.let {
                val along = when (direction) {
                    DistantFaceDirection.NORTH, DistantFaceDirection.SOUTH ->
                        Math.subtractExact(worldX, it.page.originX)
                    DistantFaceDirection.WEST, DistantFaceDirection.EAST ->
                        Math.subtractExact(worldZ, it.page.originZ)
                    else -> error("Horizontal faces do not have neighbour segments")
                }
                it.page.cellSizeBlocks - Math.floorMod(along, it.page.cellSizeBlocks.toLong()).toInt()
            }
            val segmentLength = min(
                page.cellSizeBlocks - offset,
                neighbourSegmentLength ?: page.cellSizeBlocks,
            )
            if (sample == null || sample.page.completeness != DistantSourceCompleteness.COMPLETE) {
                emitFallbackSegment(
                    page,
                    x,
                    z,
                    column,
                    direction,
                    offset,
                    segmentLength,
                    maximumFallbackDepth,
                    destination,
                )
            } else {
                emitComparedSegment(
                    page,
                    x,
                    z,
                    column,
                    sample.column,
                    direction,
                    offset,
                    segmentLength,
                    destination,
                )
            }
            offset += segmentLength
        }
    }

    private fun emitComparedSegment(
        page: DistantVerticalPage,
        x: Int,
        z: Int,
        column: DistantVerticalColumn,
        neighbour: DistantVerticalColumn,
        direction: DistantFaceDirection,
        segmentOffset: Int,
        segmentLength: Int,
        destination: MutableList<DistantMeshQuad>,
    ) {
        val (minimumU, maximumU, plane) = sideCoordinates(page, x, z, direction, segmentOffset, segmentLength)
        val neighbourRuns = neighbour.runs.filter(::isRenderable)
        for (run in column.runs) {
            if (!isRenderable(run)) continue
            val occluders = neighbourRuns.filter { occludes(run, it) }
            for (interval in subtract(run.minimumY, run.maximumYExclusive, occluders)) {
                destination += quad(
                    direction,
                    plane,
                    minimumU,
                    maximumU,
                    interval.first - page.originY,
                    interval.second - page.originY,
                    run,
                )
            }
        }
    }

    private fun emitFallbackSegment(
        page: DistantVerticalPage,
        x: Int,
        z: Int,
        column: DistantVerticalColumn,
        direction: DistantFaceDirection,
        segmentOffset: Int,
        segmentLength: Int,
        maximumFallbackDepth: Int,
        destination: MutableList<DistantMeshQuad>,
    ) {
        val top = column.runs.firstOrNull(::isRenderable) ?: return
        val (minimumU, maximumU, plane) = sideCoordinates(page, x, z, direction, segmentOffset, segmentLength)
        val minimumY = max(
            page.originY.toLong(),
            top.maximumYExclusive.toLong() - maximumFallbackDepth,
        ).toInt()
        destination += quad(
            direction,
            plane,
            minimumU,
            maximumU,
            minimumY - page.originY,
            top.maximumYExclusive - page.originY,
            top,
            fallback = true,
        )
    }

    private fun sideCoordinates(
        page: DistantVerticalPage,
        x: Int,
        z: Int,
        direction: DistantFaceDirection,
        segmentOffset: Int,
        segmentLength: Int,
    ): Triple<Int, Int, Int> = when (direction) {
        DistantFaceDirection.NORTH -> Triple(
            x * page.cellSizeBlocks + segmentOffset,
            x * page.cellSizeBlocks + segmentOffset + segmentLength,
            z * page.cellSizeBlocks,
        )

        DistantFaceDirection.SOUTH -> Triple(
            x * page.cellSizeBlocks + segmentOffset,
            x * page.cellSizeBlocks + segmentOffset + segmentLength,
            (z + 1) * page.cellSizeBlocks,
        )

        DistantFaceDirection.WEST -> Triple(
            z * page.cellSizeBlocks + segmentOffset,
            z * page.cellSizeBlocks + segmentOffset + segmentLength,
            x * page.cellSizeBlocks,
        )

        DistantFaceDirection.EAST -> Triple(
            z * page.cellSizeBlocks + segmentOffset,
            z * page.cellSizeBlocks + segmentOffset + segmentLength,
            (x + 1) * page.cellSizeBlocks,
        )

        else -> error("Horizontal faces do not have side coordinates")
    }

    private fun isRenderable(run: DistantColumnRun): Boolean =
        run.material != null && DistantRunFlag.VOID !in run.flags

    private fun occludes(current: DistantColumnRun, neighbour: DistantColumnRun): Boolean {
        if (neighbour.material == null || DistantRunFlag.VOID in neighbour.flags) return false
        if (DistantRunFlag.OPAQUE in neighbour.flags) return true
        if (current.fluid != null) return current.fluid == neighbour.fluid
        return current.material == neighbour.material && current.fluid == neighbour.fluid
    }

    private fun subtract(
        minimum: Int,
        maximum: Int,
        occluders: List<DistantColumnRun>,
    ): List<Pair<Int, Int>> {
        var visible = listOf(minimum to maximum)
        for (occluder in occluders) {
            visible = visible.flatMap { interval ->
                val clippedMinimum = max(interval.first, occluder.minimumY)
                val clippedMaximum = min(interval.second, occluder.maximumYExclusive)
                if (clippedMinimum >= clippedMaximum) {
                    listOf(interval)
                } else {
                    buildList(2) {
                        if (interval.first < clippedMinimum) add(interval.first to clippedMinimum)
                        if (clippedMaximum < interval.second) add(clippedMaximum to interval.second)
                    }
                }
            }
            if (visible.isEmpty()) break
        }
        return visible
    }

    private fun quad(
        direction: DistantFaceDirection,
        plane: Int,
        minimumU: Int,
        maximumU: Int,
        minimumV: Int,
        maximumV: Int,
        run: DistantColumnRun,
        fallback: Boolean = false,
    ) = DistantMeshQuad(
        direction,
        plane,
        minimumU,
        maximumU,
        minimumV,
        maximumV,
        requireNotNull(run.material),
        run.fluid,
        run.blockLight,
        run.skyLight,
        run.tint,
        run.flags,
        run.confidence,
        fallback,
    )

    private fun greedyMerge(input: List<DistantMeshQuad>): List<DistantMeshQuad> {
        val groups = input.groupBy(::mergeKey)
        val result = ArrayList<DistantMeshQuad>()
        for (key in groups.keys.sortedWith(MERGE_KEY_ORDER)) {
            var current = groups.getValue(key)
            while (true) {
                val alongU = mergeAlongU(current)
                val alongV = mergeAlongV(alongU)
                if (alongV.size == current.size) {
                    current = alongV
                    break
                }
                current = alongV
            }
            result += current
        }
        return result
    }

    private fun mergeAlongU(quads: List<DistantMeshQuad>): List<DistantMeshQuad> = quads
        .groupBy { it.minimumV to it.maximumVExclusive }
        .toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
        .values
        .flatMap { row ->
            val sorted = row.sortedBy(DistantMeshQuad::minimumU)
            val merged = ArrayList<DistantMeshQuad>()
            for (quad in sorted) {
                val previous = merged.lastOrNull()
                if (previous != null && previous.maximumUExclusive == quad.minimumU) {
                    merged[merged.lastIndex] = previous.copy(maximumUExclusive = quad.maximumUExclusive)
                } else {
                    merged += quad
                }
            }
            merged
        }

    private fun mergeAlongV(quads: List<DistantMeshQuad>): List<DistantMeshQuad> = quads
        .groupBy { it.minimumU to it.maximumUExclusive }
        .toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
        .values
        .flatMap { column ->
            val sorted = column.sortedBy(DistantMeshQuad::minimumV)
            val merged = ArrayList<DistantMeshQuad>()
            for (quad in sorted) {
                val previous = merged.lastOrNull()
                if (previous != null && previous.maximumVExclusive == quad.minimumV) {
                    merged[merged.lastIndex] = previous.copy(maximumVExclusive = quad.maximumVExclusive)
                } else {
                    merged += quad
                }
            }
            merged
        }

    private fun mergeKey(quad: DistantMeshQuad) = MergeKey(
        quad.direction,
        quad.plane,
        quad.material,
        quad.fluid,
        quad.blockLight,
        quad.skyLight,
        quad.tint,
        quad.flags,
        quad.confidence,
        quad.fallback,
    )

    private data class MergeKey(
        val direction: DistantFaceDirection,
        val plane: Int,
        val material: TerrainSemanticMaterialId,
        val fluid: DistantFluidSample?,
        val blockLight: Int,
        val skyLight: Int,
        val tint: DistantTintSample?,
        val flags: Set<DistantRunFlag>,
        val confidence: Int,
        val fallback: Boolean,
    )

    private val MERGE_KEY_ORDER = compareBy<MergeKey>(
        { it.direction.ordinal },
        MergeKey::plane,
        { it.material.value },
        { it.fluid?.material?.value.orEmpty() },
        { it.fluid?.level ?: -1 },
        { it.fluid?.classification.orEmpty() },
        MergeKey::blockLight,
        MergeKey::skyLight,
        { it.tint?.resolvedRgb ?: -1 },
        { it.tint?.biomeInput.orEmpty() },
        { it.tint?.generation ?: -1L },
        { it.flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) } },
        MergeKey::confidence,
        MergeKey::fallback,
    )

    private const val DEFAULT_FALLBACK_DEPTH = 32
}

private fun overlaps(first: DistantVerticalPage, second: DistantVerticalPage): Boolean =
    first.originX < second.maximumXExclusive && second.originX < first.maximumXExclusive &&
        first.originZ < second.maximumZExclusive && second.originZ < first.maximumZExclusive

private fun MessageDigest.putText(value: String) {
    val bytes = value.encodeToByteArray()
    putInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.putInt(value: Int) {
    update((value ushr 24).toByte())
    update((value ushr 16).toByte())
    update((value ushr 8).toByte())
    update(value.toByte())
}

private fun MessageDigest.putLong(value: Long) {
    putInt((value ushr 32).toInt())
    putInt(value.toInt())
}
