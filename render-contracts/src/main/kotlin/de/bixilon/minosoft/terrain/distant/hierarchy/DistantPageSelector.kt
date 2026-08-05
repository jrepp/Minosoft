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
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoveragePageMask
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageRelationship
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageTransitionPolicy
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

data class DistantSelectionCamera(
    val x: Double,
    val y: Double,
    val z: Double,
    val verticalFieldOfViewDegrees: Double,
    val zoom: Double = 1.0,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Distant selection camera must be finite" }
        require(verticalFieldOfViewDegrees.isFinite() && verticalFieldOfViewDegrees in 1.0..179.0) {
            "Distant vertical field of view must be within 1..179 degrees"
        }
        require(zoom.isFinite() && zoom > 0.0) { "Distant selection zoom must be positive" }
    }
}

data class DistantPageSelectionMetadata(
    val geometricErrorBlocks: Double,
    val minimumY: Int,
    val maximumYExclusive: Int,
) {
    init {
        require(geometricErrorBlocks.isFinite() && geometricErrorBlocks >= 0.0) {
            "Distant geometric error must be finite and non-negative"
        }
        require(minimumY < maximumYExclusive) { "Distant selection bounds must not be empty" }
    }
}

class DistantPageSelectionRequest(
    val camera: DistantSelectionCamera,
    val viewportHeightPixels: Int,
    val basePageSizeBlocks: Int,
    roots: Collection<TerrainPageKey>,
    val refineErrorPixels: Double,
    val coarsenErrorPixels: Double,
    val qualityScale: Double = 1.0,
    val nearCoveragePressure: Double = 0.0,
    val seamPressure: Double = 0.0,
    val maximumNodeVisits: Int,
    val maximumSelectionChanges: Int,
    val requireCompleteChildren: Boolean = true,
    availablePages: Collection<TerrainPageKey>? = null,
) {
    val roots: List<TerrainPageKey> = Collections.unmodifiableList(
        roots.distinct().sortedWith(DistantPageHierarchy.order),
    )
    val availablePages: Set<TerrainPageKey>? = availablePages?.let { java.util.Set.copyOf(it) }

    init {
        require(viewportHeightPixels > 0) { "Distant selection viewport height must be positive" }
        require(basePageSizeBlocks > 0) { "Distant base page size must be positive" }
        require(this.roots.isNotEmpty()) { "Distant selection requires at least one root" }
        this.roots.forEach(DistantPageHierarchy::requireDistant)
        require(this.roots.map(TerrainPageKey::worldEpoch).distinct().size == 1) {
            "Distant selection roots must share a world epoch"
        }
        require(this.availablePages == null || this.availablePages.all { it.worldEpoch == this.roots.first().worldEpoch }) {
            "Available distant pages must share the root world epoch"
        }
        require(this.availablePages == null || this.roots.all(this.availablePages::contains)) {
            "Every distant root must be available"
        }
        require(refineErrorPixels.isFinite() && refineErrorPixels > 0.0) {
            "Distant refine threshold must be positive"
        }
        require(coarsenErrorPixels.isFinite() && coarsenErrorPixels in 0.0..<refineErrorPixels) {
            "Distant coarsen threshold must be non-negative and below the refine threshold"
        }
        require(qualityScale.isFinite() && qualityScale > 0.0) { "Distant quality scale must be positive" }
        require(nearCoveragePressure.isFinite() && nearCoveragePressure in 0.0..1.0) {
            "Distant near-coverage pressure must be within 0..1"
        }
        require(seamPressure.isFinite() && seamPressure in 0.0..1.0) {
            "Distant seam pressure must be within 0..1"
        }
        require(maximumNodeVisits > 0) { "Distant node-visit budget must be positive" }
        require(maximumSelectionChanges >= this.roots.size) {
            "Distant change budget must at least admit every root"
        }
        for (first in this.roots.indices) {
            for (second in first + 1 until this.roots.size) {
                require(!spatiallyOverlaps(this.roots[first], this.roots[second])) {
                    "Distant selection roots must not overlap"
                }
            }
        }
    }
}

class DistantPageSelection(
    val worldEpoch: Long,
    val generation: Long,
    pages: Collection<TerrainPageKey>,
    val nodeVisits: Int,
    val selectionChanges: Int,
    val visitBudgetExhausted: Boolean,
    val changeBudgetExhausted: Boolean,
    unresolvedPartialCoveragePages: Collection<TerrainPageKey> = emptyList(),
) {
    val pages: List<TerrainPageKey> = Collections.unmodifiableList(
        pages.distinct().sortedWith(DistantPageHierarchy.order),
    )
    val maximumAdjacentDetailDelta: Int = maximumAdjacentDetailDelta(this.pages)
    val unresolvedPartialCoveragePages: List<TerrainPageKey> = Collections.unmodifiableList(
        unresolvedPartialCoveragePages.distinct().sortedWith(DistantPageHierarchy.order),
    )

    init {
        require(worldEpoch >= 0L) { "Distant selection world epoch must not be negative" }
        require(generation >= 0L) { "Distant selection generation must not be negative" }
        require(nodeVisits >= 0) { "Distant selection node visits must not be negative" }
        require(selectionChanges >= 0) { "Distant selection changes must not be negative" }
        require(this.pages.all { it.worldEpoch == worldEpoch }) {
            "Every selected distant page must share the selection world epoch"
        }
        require(this.unresolvedPartialCoveragePages.all(this.pages::contains)) {
            "Unresolved partial-coverage pages must remain in the conservative selection"
        }
        require(maximumAdjacentDetailDelta <= 1) { "Adjacent distant pages differ by more than one detail level" }
    }
}

/** Stateful screen-space-error selector with hysteresis and bounded progress. */
class DistantPageSelector(private val worldEpoch: Long) {
    init {
        require(worldEpoch >= 0L) { "Distant selector world epoch must not be negative" }
    }

    private var generation = 0L
    private var selected: Set<TerrainPageKey> = emptySet()

    @Synchronized
    fun current(): DistantPageSelection = DistantPageSelection(
        worldEpoch,
        generation,
        selected,
        nodeVisits = 0,
        selectionChanges = 0,
        visitBudgetExhausted = false,
        changeBudgetExhausted = false,
    )

    /**
     * Returns the smallest page budget that can retain every requested root while preserving the
     * adjacent-detail invariant. Mixed-detail roots can require refinement before screen-space
     * selection begins, so their raw count is not a sufficient admission bound.
     */
    fun minimumBalancedPageBudget(
        index: DistantPageIndexView,
        request: DistantPageSelectionRequest,
    ): Int {
        require(index.worldEpoch == worldEpoch) { "Distant hierarchy belongs to another selector world" }
        require(request.roots.all { it.worldEpoch == worldEpoch && it in index.pages }) {
            "Every distant selection root must be indexed in the selector world"
        }
        val balanced = balance(
            request.roots.toSet(),
            index,
            request.requireCompleteChildren,
            request.availablePages,
        )
        require(!balanced.exhausted) {
            "Distant selection roots cannot be balanced with the available hierarchy"
        }
        return balanced.pages.size
    }

    @Synchronized
    fun select(
        index: DistantPageIndexView,
        metadata: Map<TerrainPageKey, DistantPageSelectionMetadata>,
        request: DistantPageSelectionRequest,
        maximumPages: Int = Int.MAX_VALUE,
        nearCoverage: TerrainCoverageSnapshot? = null,
    ): DistantPageSelection {
        require(maximumPages >= request.roots.size) {
            "Distant page budget must at least admit every root"
        }
        val coverage = TerrainCoveragePageMask(
            nearCoverage,
            TerrainCoverageTransitionPolicy.CONSERVATIVE_OVERLAP,
        )
        if (maximumPages == Int.MAX_VALUE) return selectOnce(index, metadata, request, coverage)
        return selectBudgeted(index, metadata, request, maximumPages, coverage)
    }

    private data class RefinementCandidate(
        val page: TerrainPageKey,
        val error: Double,
        val coverageCritical: Boolean,
    )

    /** Best-first refinement visits the hierarchy once and stops at the page budget. */
    private fun selectBudgeted(
        index: DistantPageIndexView,
        metadata: Map<TerrainPageKey, DistantPageSelectionMetadata>,
        request: DistantPageSelectionRequest,
        maximumPages: Int,
        coverage: TerrainCoveragePageMask,
    ): DistantPageSelection {
        require(index.worldEpoch == worldEpoch) { "Distant hierarchy belongs to another selector world" }
        require(request.roots.all { it.worldEpoch == worldEpoch && it in index.pages }) {
            "Every distant selection root must be indexed in the selector world"
        }
        require(metadata.keys.all(index.pages::containsKey)) { "Distant selection metadata contains an unknown page" }

        val previousSelection = selected.filterTo(linkedSetOf()) { page ->
            page in index.pages &&
                (request.availablePages == null || page in request.availablePages) &&
                request.roots.any { root -> isDescendantOrSame(page, root) }
        }
        val maximumIndexedDetail = index.pages.keys.maxOfOrNull(TerrainPageKey::detailLevel) ?: 0
        val previouslyRefinedPages = hashSetOf<TerrainPageKey>()
        for (selectedPage in previousSelection) {
            var ancestor = selectedPage
            while (ancestor.detailLevel < maximumIndexedDetail) {
                ancestor = DistantPageHierarchy.parent(ancestor)
                previouslyRefinedPages += ancestor
            }
        }
        val queue = PriorityQueue(
            compareByDescending<RefinementCandidate>(RefinementCandidate::coverageCritical)
                .thenByDescending(RefinementCandidate::error)
                .thenBy { it.page.detailLevel }
                .thenBy { it.page.z }
                .thenBy { it.page.x },
        )
        val initialBalance = balance(
            request.roots.toSet(),
            index,
            request.requireCompleteChildren,
            request.availablePages,
        )
        require(!initialBalance.exhausted) {
            "Distant selection roots cannot be balanced with the available hierarchy"
        }
        require(initialBalance.pages.size <= maximumPages) {
            "Distant page budget $maximumPages cannot admit the balanced root selection of " +
                "${initialBalance.pages.size} pages"
        }
        val proposal = initialBalance.pages.toMutableSet()
        fun candidate(page: TerrainPageKey) = RefinementCandidate(
            page,
            projectedError(page, metadata[page], request),
            coverage.relationship(page) == TerrainCoverageRelationship.PARTIAL,
        )
        proposal.forEach { queue += candidate(it) }
        var visits = 0
        var balanceExhausted = initialBalance.exhausted

        while (queue.isNotEmpty() && visits < request.maximumNodeVisits) {
            val candidate = queue.remove()
            val page = candidate.page
            if (page !in proposal) continue
            visits++
            val children = if (page.detailLevel > 0) DistantPageHierarchy.children(page) else emptyList()
            if (children.isEmpty() || !children.all { child ->
                    val state = index.pages[child] ?: return@all false
                    (request.availablePages == null || child in request.availablePages) &&
                        (!request.requireCompleteChildren || state.completeness == DistantSourceCompleteness.COMPLETE)
                }
            ) continue

            val refineForCoverage = candidate.coverageCritical
            val refine = refineForCoverage || if (page in previouslyRefinedPages) {
                candidate.error >= request.coarsenErrorPixels
            } else {
                candidate.error > request.refineErrorPixels
            }
            if (!refine) continue

            val balanced = refineBalanced(
                proposal,
                page,
                children,
                index,
                request.requireCompleteChildren,
                request.availablePages,
                maximumPages,
            )
            if (!balanced.accepted) {
                if (!balanced.exhausted) continue
                balanceExhausted = true
                continue
            }
            for (refined in balanced.addedPages) {
                queue += candidate(refined)
            }
        }

        val visitBudgetExhausted = queue.isNotEmpty() && visits >= request.maximumNodeVisits || balanceExhausted
        val changes = symmetricDifferenceSize(previousSelection, proposal)
        val changeBudgetExhausted = changes > request.maximumSelectionChanges
        val reusablePrevious = previousSelection.takeIf {
            it.isNotEmpty() &&
                it.size <= maximumPages &&
                maximumAdjacentDetailDelta(it.toList()) <= 1 &&
                selectionCoversRoots(it, request.roots)
        }
        val accepted = if (changeBudgetExhausted && reusablePrevious != null) {
            reusablePrevious
        } else {
            proposal
        }
        val acceptedChanges = symmetricDifferenceSize(selected, accepted)
        if (accepted != selected) generation = Math.addExact(generation, 1L)
        selected = accepted
        val unresolvedPartialCoveragePages = accepted.filter {
            coverage.relationship(it) == TerrainCoverageRelationship.PARTIAL
        }
        return DistantPageSelection(
            worldEpoch,
            generation,
            accepted,
            visits,
            acceptedChanges,
            visitBudgetExhausted,
            changeBudgetExhausted,
            unresolvedPartialCoveragePages,
        )
    }

    /**
     * Refines one page in an already-balanced selection and repairs only the
     * newly exposed boundary. A global balance pass here makes best-first
     * selection quadratic in the number of admitted pages, while an undo log
     * keeps rejected budget or availability refinements allocation-bounded.
     */
    private data class RefinementBalanceResult(
        val accepted: Boolean,
        val exhausted: Boolean,
        val addedPages: Set<TerrainPageKey> = emptySet(),
    )

    private data class SelectionModification(val page: TerrainPageKey, val added: Boolean)

    private fun refineBalanced(
        pages: MutableSet<TerrainPageKey>,
        page: TerrainPageKey,
        children: List<TerrainPageKey>,
        index: DistantPageIndexView,
        requireCompleteChildren: Boolean,
        availablePages: Set<TerrainPageKey>?,
        maximumPages: Int,
    ): RefinementBalanceResult {
        val modifications = ArrayDeque<SelectionModification>()
        val addedPages = linkedSetOf<TerrainPageKey>()

        fun remove(candidate: TerrainPageKey) {
            if (!pages.remove(candidate)) return
            modifications.addLast(SelectionModification(candidate, added = false))
            addedPages.remove(candidate)
        }

        fun add(candidate: TerrainPageKey) {
            if (!pages.add(candidate)) return
            modifications.addLast(SelectionModification(candidate, added = true))
            addedPages += candidate
        }

        fun rollback(exhausted: Boolean): RefinementBalanceResult {
            while (modifications.isNotEmpty()) {
                val modification = modifications.removeLast()
                if (modification.added) pages.remove(modification.page) else pages.add(modification.page)
            }
            return RefinementBalanceResult(accepted = false, exhausted = exhausted)
        }

        remove(page)
        children.forEach(::add)
        if (pages.size > maximumPages) return rollback(exhausted = false)

        val maximumDetail = index.pages.keys.maxOfOrNull(TerrainPageKey::detailLevel) ?: 0
        val boundary = ArrayDeque(children)
        while (boundary.isNotEmpty()) {
            val fine = boundary.removeFirst()
            for (neighbour in DistantPageHierarchy.cardinalNeighbours(fine)) {
                var coarse = neighbour
                while (coarse.detailLevel <= maximumDetail && coarse !in pages) {
                    if (coarse.detailLevel == maximumDetail) break
                    coarse = DistantPageHierarchy.parent(coarse)
                }
                if (coarse !in pages || coarse.detailLevel - fine.detailLevel <= 1) continue

                val coarseChildren = DistantPageHierarchy.children(coarse)
                val canRefine = coarseChildren.all { child ->
                    val state = index.pages[child] ?: return@all false
                    (availablePages == null || child in availablePages) &&
                        (!requireCompleteChildren || state.completeness == DistantSourceCompleteness.COMPLETE)
                }
                if (!canRefine) return rollback(exhausted = true)
                remove(coarse)
                coarseChildren.forEach(::add)
                if (pages.size > maximumPages) return rollback(exhausted = false)
                boundary.addAll(coarseChildren)
            }
        }
        return RefinementBalanceResult(accepted = true, exhausted = false, addedPages = addedPages)
    }

    private fun selectOnce(
        index: DistantPageIndexView,
        metadata: Map<TerrainPageKey, DistantPageSelectionMetadata>,
        request: DistantPageSelectionRequest,
        coverage: TerrainCoveragePageMask,
    ): DistantPageSelection {
        require(index.worldEpoch == worldEpoch) { "Distant hierarchy belongs to another selector world" }
        require(request.roots.all { it.worldEpoch == worldEpoch }) { "Distant roots belong to another selector world" }
        require(request.roots.all(index.pages::containsKey)) { "Every distant selection root must be indexed" }
        require(metadata.keys.all(index.pages::containsKey)) { "Distant selection metadata contains an unknown page" }

        var visits = 0
        var visitBudgetExhausted = false
        val proposal = linkedSetOf<TerrainPageKey>()
        val maximumIndexedDetail = index.pages.keys.maxOfOrNull(TerrainPageKey::detailLevel) ?: 0
        val previousSelection = selected.filterTo(linkedSetOf()) { page ->
            page in index.pages &&
                (request.availablePages == null || page in request.availablePages) &&
                request.roots.any { root -> isDescendantOrSame(page, root) }
        }
        val previousByAncestor = HashMap<TerrainPageKey, MutableList<TerrainPageKey>>()
        for (page in previousSelection.sortedWith(DistantPageHierarchy.order)) {
            var ancestor = page
            while (ancestor.detailLevel <= maximumIndexedDetail) {
                previousByAncestor.getOrPut(ancestor, ::ArrayList) += page
                if (ancestor.detailLevel == maximumIndexedDetail) break
                ancestor = DistantPageHierarchy.parent(ancestor)
            }
        }

        fun previousWithin(page: TerrainPageKey): List<TerrainPageKey> = previousByAncestor[page].orEmpty()

        fun retain(page: TerrainPageKey): List<TerrainPageKey> = previousWithin(page).ifEmpty { listOf(page) }

        fun visit(page: TerrainPageKey): List<TerrainPageKey> {
            if (visits >= request.maximumNodeVisits) {
                visitBudgetExhausted = true
                return retain(page)
            }
            visits++
            val children = if (page.detailLevel > 0) DistantPageHierarchy.children(page) else emptyList()
            val childrenAvailable = children.isNotEmpty() && children.all { child ->
                val indexed = index.pages[child] ?: return@all false
                (request.availablePages == null || child in request.availablePages) &&
                    (!request.requireCompleteChildren || indexed.completeness == DistantSourceCompleteness.COMPLETE)
            }
            if (!childrenAvailable) return listOf(page)

            val currentlyRefined = previousWithin(page).any { it != page }
            val error = projectedError(page, metadata[page], request)
            val refineForCoverage = coverage.relationship(page) == TerrainCoverageRelationship.PARTIAL
            val refine = refineForCoverage || if (currentlyRefined) {
                error >= request.coarsenErrorPixels
            } else {
                error > request.refineErrorPixels
            }
            if (!refine) return listOf(page)
            val exhaustedBeforeChildren = visitBudgetExhausted
            val refined = children.flatMap(::visit)
            return if (!exhaustedBeforeChildren && visitBudgetExhausted) retain(page) else refined
        }

        request.roots.forEach { proposal += visit(it) }
        val balanced = balance(proposal, index, request.requireCompleteChildren, request.availablePages)
        val candidate = balanced.pages
        visitBudgetExhausted = visitBudgetExhausted || balanced.exhausted
        val changes = symmetricDifferenceSize(previousSelection, candidate)
        val changeBudgetExhausted = changes > request.maximumSelectionChanges
        val accepted = if (changeBudgetExhausted) {
            if (previousSelection.isEmpty()) request.roots.toSet() else previousSelection
        } else {
            candidate
        }
        val acceptedChanges = symmetricDifferenceSize(selected, accepted)
        if (accepted != selected) generation = Math.addExact(generation, 1L)
        selected = accepted
        val unresolvedPartialCoveragePages = accepted.filter {
            coverage.relationship(it) == TerrainCoverageRelationship.PARTIAL
        }
        return DistantPageSelection(
            worldEpoch = worldEpoch,
            generation = generation,
            pages = accepted,
            nodeVisits = visits,
            selectionChanges = acceptedChanges,
            visitBudgetExhausted = visitBudgetExhausted,
            changeBudgetExhausted = changeBudgetExhausted,
            unresolvedPartialCoveragePages = unresolvedPartialCoveragePages,
        )
    }

    private fun projectedError(
        page: TerrainPageKey,
        metadata: DistantPageSelectionMetadata?,
        request: DistantPageSelectionRequest,
    ): Double {
        val spanPages = 1L shl page.detailLevel
        val width = Math.multiplyExact(spanPages, request.basePageSizeBlocks.toLong())
        val minimumX = Math.multiplyExact(page.x, width).toDouble()
        val minimumZ = Math.multiplyExact(page.z, width).toDouble()
        val maximumX = minimumX + width
        val maximumZ = minimumZ + width
        val minimumY = metadata?.minimumY?.toDouble() ?: -30_000_000.0
        val maximumY = metadata?.maximumYExclusive?.toDouble() ?: 30_000_001.0
        val dx = axisDistance(request.camera.x, minimumX, maximumX)
        val dy = axisDistance(request.camera.y, minimumY, maximumY)
        val dz = axisDistance(request.camera.z, minimumZ, maximumZ)
        val distance = max(1.0, sqrt(dx * dx + dy * dy + dz * dz))
        val geometricError = metadata?.geometricErrorBlocks ?: max(1.0, width * DEFAULT_ERROR_RATIO)
        val focalLength = request.viewportHeightPixels /
            (2.0 * tan(Math.toRadians(request.camera.verticalFieldOfViewDegrees) / 2.0))
        val pressure = (1.0 + request.seamPressure) * (1.0 - request.nearCoveragePressure * 0.5)
        return geometricError * focalLength * request.camera.zoom * request.qualityScale * pressure / distance
    }

    private data class BalanceResult(val pages: Set<TerrainPageKey>, val exhausted: Boolean)

    private fun balance(
        input: Set<TerrainPageKey>,
        index: DistantPageIndexView,
        requireCompleteChildren: Boolean,
        availablePages: Set<TerrainPageKey>?,
    ): BalanceResult {
        val pages = input.toMutableSet()
        repeat(MAXIMUM_BALANCE_PASSES) {
            val violations = detailViolations(pages)
            if (violations.isEmpty()) return BalanceResult(pages, false)
            var changed = false
            for (violation in violations) {
                if (violation.first !in pages || violation.second !in pages) continue
                val coarse = if (violation.first.detailLevel > violation.second.detailLevel) {
                    violation.first
                } else {
                    violation.second
                }
                val fine = if (coarse == violation.first) violation.second else violation.first
                val children = DistantPageHierarchy.children(coarse)
                val canRefine = children.all { child ->
                    val state = index.pages[child] ?: return@all false
                    (availablePages == null || child in availablePages) &&
                        (!requireCompleteChildren || state.completeness == DistantSourceCompleteness.COMPLETE)
                }
                if (canRefine) {
                    pages.remove(coarse)
                    pages.addAll(children)
                } else {
                    val target = ancestorAt(fine, coarse.detailLevel - 1)
                    val targetState = index.pages[target]
                    if (targetState == null || availablePages != null && target !in availablePages ||
                        requireCompleteChildren && targetState.completeness != DistantSourceCompleteness.COMPLETE
                    ) return BalanceResult(input, true)
                    pages.removeIf { isDescendantOrSame(it, target) }
                    pages += target
                }
                changed = true
            }
            if (!changed) return BalanceResult(input, true)
        }
        return BalanceResult(input, true)
    }

    private companion object {
        const val DEFAULT_ERROR_RATIO = 0.25
        const val MAXIMUM_BALANCE_PASSES = 64
    }
}

private fun axisDistance(value: Double, minimum: Double, maximum: Double): Double = when {
    value < minimum -> minimum - value
    value > maximum -> value - maximum
    else -> 0.0
}

private fun ancestorAt(page: TerrainPageKey, detailLevel: Int): TerrainPageKey {
    require(detailLevel >= page.detailLevel) { "Cannot derive a finer distant ancestor" }
    var result = page
    while (result.detailLevel < detailLevel) result = DistantPageHierarchy.parent(result)
    return result
}

private fun isDescendantOrSame(page: TerrainPageKey, ancestor: TerrainPageKey): Boolean =
    page.worldEpoch == ancestor.worldEpoch &&
        page.detailLevel <= ancestor.detailLevel &&
        ancestorAt(page, ancestor.detailLevel) == ancestor

private fun symmetricDifferenceSize(first: Set<TerrainPageKey>, second: Set<TerrainPageKey>): Int =
    first.count { it !in second } + second.count { it !in first }

private fun selectionCoversRoots(
    selection: Set<TerrainPageKey>,
    roots: List<TerrainPageKey>,
): Boolean {
    val selected = selection.toHashSet()
    val maximumRootDetail = roots.maxOf(TerrainPageKey::detailLevel)
    val selectedAncestors = HashSet<TerrainPageKey>()
    for (page in selected) {
        var ancestor = page
        while (ancestor.detailLevel < maximumRootDetail) {
            ancestor = DistantPageHierarchy.parent(ancestor)
            selectedAncestors += ancestor
        }
    }

    fun covered(page: TerrainPageKey): Boolean {
        if (page in selected) return true
        if (page.detailLevel == 0 || page !in selectedAncestors) return false
        return DistantPageHierarchy.children(page).all(::covered)
    }
    return roots.all(::covered)
}

private fun detailViolations(pages: Set<TerrainPageKey>): List<Pair<TerrainPageKey, TerrainPageKey>> {
    val ordered = pages.sortedWith(DistantPageHierarchy.order)
    val maximumDetail = ordered.maxOfOrNull(TerrainPageKey::detailLevel) ?: return emptyList()
    val violations = linkedSetOf<Pair<TerrainPageKey, TerrainPageKey>>()
    for (page in ordered) {
        for (neighbour in DistantPageHierarchy.cardinalNeighbours(page)) {
            var candidate = neighbour
            while (candidate.detailLevel < maximumDetail) {
                candidate = DistantPageHierarchy.parent(candidate)
                if (candidate !in pages) continue
                if (candidate.detailLevel - page.detailLevel > 1 && cardinallyAdjacent(page, candidate)) {
                    violations += page to candidate
                }
                break
            }
        }
    }
    return violations.toList()
}

private fun maximumAdjacentDetailDelta(pages: List<TerrainPageKey>): Int {
    var maximum = 0
    val selected = pages.toHashSet()
    val maximumDetail = pages.maxOfOrNull(TerrainPageKey::detailLevel) ?: return maximum
    for (page in pages) {
        for (neighbour in DistantPageHierarchy.cardinalNeighbours(page)) {
            var candidate = neighbour
            while (candidate.detailLevel < maximumDetail) {
                candidate = DistantPageHierarchy.parent(candidate)
                if (candidate !in selected) continue
                maximum = max(maximum, candidate.detailLevel - page.detailLevel)
                break
            }
        }
    }
    return maximum
}

private data class PageRectangle(val minimumX: Long, val maximumX: Long, val minimumZ: Long, val maximumZ: Long)

private fun rectangle(page: TerrainPageKey): PageRectangle {
    val span = 1L shl page.detailLevel
    val minimumX = Math.multiplyExact(page.x, span)
    val minimumZ = Math.multiplyExact(page.z, span)
    return PageRectangle(
        minimumX,
        Math.addExact(minimumX, span),
        minimumZ,
        Math.addExact(minimumZ, span),
    )
}

private fun spatiallyOverlaps(first: TerrainPageKey, second: TerrainPageKey): Boolean {
    val a = rectangle(first)
    val b = rectangle(second)
    return a.minimumX < b.maximumX && b.minimumX < a.maximumX &&
        a.minimumZ < b.maximumZ && b.minimumZ < a.maximumZ
}

private fun cardinallyAdjacent(first: TerrainPageKey, second: TerrainPageKey): Boolean {
    val a = rectangle(first)
    val b = rectangle(second)
    val xEdge = a.maximumX == b.minimumX || b.maximumX == a.minimumX
    val zOverlap = a.minimumZ < b.maximumZ && b.minimumZ < a.maximumZ
    val zEdge = a.maximumZ == b.minimumZ || b.maximumZ == a.minimumZ
    val xOverlap = a.minimumX < b.maximumX && b.minimumX < a.maximumX
    return xEdge && zOverlap || zEdge && xOverlap
}
