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
    val columns: List<DistantVerticalColumn> = canonicalize(columns)
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
            columns.forEach { digest.putText(DistantVerticalColumn.semanticDigest(it.runs)) }
            return HexFormat.of().formatHex(digest.digest())
        }

        /**
         * Full-height pages contain many repeated immutable values. Canonicalize them within the
         * page so explored terrain residency scales with distinct semantics instead of sampled
         * voxel transitions. The page digest remains the authority; identity is not observable.
         */
        fun canonicalize(columns: Collection<DistantVerticalColumn>): List<DistantVerticalColumn> {
            val strings = HashMap<String, String>()
            val materials = HashMap<String, TerrainSemanticMaterialId>()
            val fluids = HashMap<FluidKey, DistantFluidSample>()
            val tints = HashMap<TintKey, DistantTintSample>()
            val runs = HashMap<RunKey, DistantColumnRun>()
            val canonicalColumns = HashMap<List<DistantColumnRun>, DistantVerticalColumn>()

            fun canonicalString(value: String): String = strings.getOrPut(value) { value }
            fun canonicalMaterial(value: TerrainSemanticMaterialId?): TerrainSemanticMaterialId? {
                value ?: return null
                return materials.getOrPut(value.value) {
                    TerrainSemanticMaterialId(canonicalString(value.value))
                }
            }
            fun canonicalFluid(value: DistantFluidSample?): DistantFluidSample? {
                value ?: return null
                val material = checkNotNull(canonicalMaterial(value.material))
                val classification = canonicalString(value.classification)
                val key = FluidKey(material, value.level, classification)
                return fluids.getOrPut(key) {
                    if (value.material.value === material.value && value.classification === classification) value
                    else DistantFluidSample(material, value.level, classification)
                }
            }
            fun canonicalTint(value: DistantTintSample?): DistantTintSample? {
                value ?: return null
                val biomeInput = value.biomeInput?.let(::canonicalString)
                val key = TintKey(value.resolvedRgb, biomeInput, value.generation)
                return tints.getOrPut(key) {
                    if (value.biomeInput === biomeInput) value
                    else DistantTintSample(value.resolvedRgb, biomeInput, value.generation)
                }
            }

            val result = ArrayList<DistantVerticalColumn>(columns.size)
            for (column in columns) {
                val canonicalRuns = ArrayList<DistantColumnRun>(column.runs.size)
                var unchanged = true
                for (run in column.runs) {
                    val material = canonicalMaterial(run.material)
                    val fluid = canonicalFluid(run.fluid)
                    val tint = canonicalTint(run.tint)
                    val key = RunKey(
                        run.minimumY,
                        run.height,
                        material,
                        fluid,
                        run.blockLight,
                        run.skyLight,
                        tint,
                        run.flagBits,
                        run.confidence,
                    )
                    val pageCanonical = runs.getOrPut(key) {
                        if (run.material?.value === material?.value && run.fluid === fluid && run.tint === tint) run
                        else DistantColumnRun(
                            run.minimumY,
                            run.height,
                            material,
                            fluid,
                            run.blockLight,
                            run.skyLight,
                            tint,
                            run.flags,
                            run.confidence,
                        )
                    }
                    val canonical = SHARED_RUNS.canonicalize(pageCanonical)
                    canonicalRuns += canonical
                    unchanged = unchanged && canonical === run
                }
                val key = java.util.List.copyOf(canonicalRuns)
                val pageCanonical = canonicalColumns.getOrPut(key) {
                    if (unchanged) column else DistantVerticalColumn(key)
                }
                result += pageCanonical
            }
            return java.util.List.copyOf(result)
        }

        private val SHARED_RUNS = DistantWeakCanonicalizer<DistantColumnRun>()

        private data class FluidKey(
            val material: TerrainSemanticMaterialId,
            val level: Int,
            val classification: String,
        )

        private data class TintKey(
            val resolvedRgb: Int?,
            val biomeInput: String?,
            val generation: Long,
        )

        private data class RunKey(
            val minimumY: Int,
            val height: Int,
            val material: TerrainSemanticMaterialId?,
            val fluid: DistantFluidSample?,
            val blockLight: Int,
            val skyLight: Int,
            val tint: DistantTintSample?,
            val flagBits: Int,
            val confidence: Int,
        )
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
            // A parent column covers the complete 2x2 footprint. Promoting an
            // interval present in only one or two children invents a full-cell
            // slab (most visibly isolated peaks and water at coastlines).
            if (candidateCount * 2 <= columns.size) {
                upper = lower
                continue
            }
            val representative = representative(candidates, candidateCount, columns.size)
            if (representative == null) {
                upper = lower
                continue
            }
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
        return if (reduced.size > DistantVerticalColumn.MAXIMUM_RUNS) {
            DistantColumnReducer.reduce(reduced, DistantVerticalColumn.MAXIMUM_RUNS)
        } else {
            DistantVerticalColumn(reduced)
        }
    }

    private fun representative(
        candidates: Array<DistantColumnRun?>,
        count: Int,
        sampleCount: Int,
    ): DistantColumnRun? {
        require(sampleCount > 0 && count in 1..sampleCount) { "Distant reduction sample count is invalid" }
        val fluidCount = (0 until count).count { checkNotNull(candidates[it]).fluid != null }
        if (fluidCount * 2 <= sampleCount) {
            var retained = 0
            for (index in 0 until count) {
                val candidate = checkNotNull(candidates[index])
                if (candidate.fluid == null) candidates[retained++] = candidate
            }
            if (retained == 0) return null
            return representative(candidates, retained)
        }
        return representative(candidates, count)
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
    ): DistantPageMeshArtifact {
        require(neighbourhood.subject === page || neighbourhood.subject.key == page.key) {
            "Distant neighbourhood belongs to another page"
        }
        val faces = ArrayList<DistantMeshQuad>()
        for (z in 0 until page.width) {
            for (x in 0 until page.width) {
                val column = page[x, z]
                emitHorizontalFaces(page, x, z, column, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.NORTH, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.SOUTH, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.WEST, faces)
                emitDirection(page, neighbourhood, x, z, column, DistantFaceDirection.EAST, faces)
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
            val above = column.runs.firstOrNull { it.minimumY == run.maximumYExclusive }
            if (above == null || !occludes(run, above)) {
                destination += quad(
                    DistantFaceDirection.UP,
                    run.maximumYExclusive - page.originY,
                    minimumX,
                    minimumX + page.cellSizeBlocks,
                    minimumZ,
                    minimumZ + page.cellSizeBlocks,
                    run,
                    adjacentLight(run, above),
                )
            }
            val below = column.runs.firstOrNull { it.maximumYExclusive == run.minimumY }
            val knownInteriorBelow = below != null || column.runs.any {
                it.maximumYExclusive < run.minimumY
            }
            if (page.completeness == DistantSourceCompleteness.COMPLETE &&
                knownInteriorBelow &&
                (below == null || !occludes(run, below))
            ) {
                destination += quad(
                    DistantFaceDirection.DOWN,
                    run.minimumY - page.originY,
                    minimumX,
                    minimumX + page.cellSizeBlocks,
                    minimumZ,
                    minimumZ + page.cellSizeBlocks,
                    run,
                    adjacentLight(run, below),
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
            // Missing coverage is unknown, not an observed cliff. Emitting a
            // bounded skirt here turns page and LOD frontiers into large dark
            // walls. A partial neighbour still contributes its known runs;
            // the comparison therefore closes only geometry backed by data.
            if (sample != null) {
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
        val currentSurfaceOnly = isSurfaceOnlyCompatibilityColumn(column)
        val neighbourSurfaceOnly = isSurfaceOnlyCompatibilityColumn(neighbour)
        if (currentSurfaceOnly || neighbourSurfaceOnly) {
            val currentSurface = column.runs.firstOrNull(::isRenderable) ?: return
            val neighbourSurface = neighbour.runs.firstOrNull(::isRenderable) ?: return
            if (currentSurface.maximumYExclusive > neighbourSurface.maximumYExclusive) {
                destination += quad(
                    direction,
                    plane,
                    minimumU,
                    maximumU,
                    neighbourSurface.maximumYExclusive - page.originY,
                    currentSurface.maximumYExclusive - page.originY,
                    currentSurface,
                    adjacentLight(currentSurface, neighbourSurface),
                )
            }
            return
        }
        val neighbourRuns = neighbour.runs
        for (run in column.runs) {
            if (!isRenderable(run)) continue
            val occluders = neighbourRuns.filter { isRenderable(it) && occludes(run, it) }
            for (interval in subtract(run.minimumY, run.maximumYExclusive, occluders)) {
                destination += quad(
                    direction,
                    plane,
                    minimumU,
                    maximumU,
                    interval.first - page.originY,
                    interval.second - page.originY,
                    run,
                    adjacentLight(run, neighbourRuns, interval.first, interval.second),
                )
            }
        }
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

    private fun isSurfaceOnlyCompatibilityColumn(column: DistantVerticalColumn): Boolean {
        val occupied = column.runs.filter(::isRenderable)
        if (occupied.isEmpty()) return false
        return occupied.all {
            DistantRunFlag.SURFACE_ONLY in it.flags ||
                (DistantRunFlag.GENERATED in it.flags && it.confidence <= LEGACY_SURFACE_ONLY_CONFIDENCE)
        }
    }

    private fun occludes(current: DistantColumnRun, neighbour: DistantColumnRun): Boolean {
        // Enclosed air is not part of the distant exterior envelope. Treat it as closed here so
        // cave floors, ceilings, and walls cannot leak through translucent water or a clipped LOD
        // frontier; exterior VOID remains non-occluding and preserves real surface relief.
        if (DistantRunFlag.CAVE in neighbour.flags) return true
        if (neighbour.material == null || DistantRunFlag.VOID in neighbour.flags) return false
        if (DistantRunFlag.OPAQUE in neighbour.flags) return true
        if (current.fluid != null) return current.fluid == neighbour.fluid
        // The compact distant material palette renders non-fluid materials as closed, opaque
        // volumes. Treat their shared boundary the same way here. Otherwise an opaque trunk beside
        // simplified non-opaque foliage emits its dark internal face while the foliage correctly
        // suppresses the opposite face, producing black branch silhouettes at the LOD seam.
        return neighbour.fluid == null
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
        light: FaceLight = FaceLight(run.blockLight, run.skyLight),
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
        light.block,
        light.sky,
        run.tint,
        run.flags,
        run.confidence,
        fallback,
    )

    /**
     * Voxel light belongs to the volume containing the sample, while Minecraft face light belongs
     * to the non-occluding volume immediately outside the face. Retaining the brighter component
     * from that adjacent medium prevents opaque shore and cave-boundary faces from inheriting the
     * zero light inside their own block. The source value remains the fallback when an exterior
     * interval was intentionally omitted from a partial page.
     */
    private fun adjacentLight(run: DistantColumnRun, adjacent: DistantColumnRun?): FaceLight {
        if (adjacent == null || occludes(run, adjacent)) return FaceLight(run.blockLight, run.skyLight)
        return FaceLight(
            max(run.blockLight, adjacent.blockLight),
            max(run.skyLight, adjacent.skyLight),
        )
    }

    private fun adjacentLight(
        run: DistantColumnRun,
        adjacent: List<DistantColumnRun>,
        minimumY: Int,
        maximumYExclusive: Int,
    ): FaceLight {
        var block = run.blockLight
        var sky = run.skyLight
        for (candidate in adjacent) {
            if (candidate.maximumYExclusive <= minimumY || candidate.minimumY >= maximumYExclusive) continue
            if (occludes(run, candidate)) continue
            block = max(block, candidate.blockLight)
            sky = max(sky, candidate.skyLight)
        }
        return FaceLight(block, sky)
    }

    private data class FaceLight(val block: Int, val sky: Int)

    private const val LEGACY_SURFACE_ONLY_CONFIDENCE = 25

    private fun greedyMerge(input: List<DistantMeshQuad>): List<DistantMeshQuad> {
        if (input.size < 2) return input
        val ordered = input.sortedWith(MERGE_QUAD_ORDER)
        val result = ArrayList<DistantMeshQuad>(input.size)
        var start = 0
        while (start < ordered.size) {
            var end = start + 1
            while (end < ordered.size && sameMergeKey(ordered[start], ordered[end])) end++
            var current: List<DistantMeshQuad> = ordered.subList(start, end)
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
            start = end
        }
        return result
    }

    private fun mergeAlongU(quads: List<DistantMeshQuad>): List<DistantMeshQuad> {
        if (quads.size < 2) return quads
        val sorted = quads.sortedWith(QUAD_U_ORDER)
        val merged = ArrayList<DistantMeshQuad>(quads.size)
        for (quad in sorted) {
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                previous.minimumV == quad.minimumV &&
                previous.maximumVExclusive == quad.maximumVExclusive &&
                previous.maximumUExclusive == quad.minimumU
            ) {
                merged[merged.lastIndex] = previous.copy(maximumUExclusive = quad.maximumUExclusive)
            } else {
                merged += quad
            }
        }
        return merged
    }

    private fun mergeAlongV(quads: List<DistantMeshQuad>): List<DistantMeshQuad> {
        if (quads.size < 2) return quads
        val sorted = quads.sortedWith(QUAD_V_ORDER)
        val merged = ArrayList<DistantMeshQuad>(quads.size)
        for (quad in sorted) {
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                previous.minimumU == quad.minimumU &&
                previous.maximumUExclusive == quad.maximumUExclusive &&
                previous.maximumVExclusive == quad.minimumV
            ) {
                merged[merged.lastIndex] = previous.copy(maximumVExclusive = quad.maximumVExclusive)
            } else {
                merged += quad
            }
        }
        return merged
    }

    private fun sameMergeKey(first: DistantMeshQuad, second: DistantMeshQuad): Boolean =
        first.direction == second.direction &&
            first.plane == second.plane &&
            first.material == second.material &&
            first.fluid == second.fluid &&
            first.blockLight == second.blockLight &&
            first.skyLight == second.skyLight &&
            first.tint == second.tint &&
            first.flags == second.flags &&
            first.confidence == second.confidence &&
            first.fallback == second.fallback

    private val MERGE_QUAD_ORDER = compareBy<DistantMeshQuad>(
        { it.direction.ordinal },
        DistantMeshQuad::plane,
        { it.material.value },
        { it.fluid?.material?.value.orEmpty() },
        { it.fluid?.level ?: -1 },
        { it.fluid?.classification.orEmpty() },
        DistantMeshQuad::blockLight,
        DistantMeshQuad::skyLight,
        { it.tint?.resolvedRgb ?: -1 },
        { it.tint?.biomeInput.orEmpty() },
        { it.tint?.generation ?: -1L },
        { it.flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) } },
        DistantMeshQuad::confidence,
        DistantMeshQuad::fallback,
    )

    private val QUAD_U_ORDER = compareBy<DistantMeshQuad>(
        DistantMeshQuad::minimumV,
        DistantMeshQuad::maximumVExclusive,
        DistantMeshQuad::minimumU,
    )

    private val QUAD_V_ORDER = compareBy<DistantMeshQuad>(
        DistantMeshQuad::minimumU,
        DistantMeshQuad::maximumUExclusive,
        DistantMeshQuad::minimumV,
    )

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
