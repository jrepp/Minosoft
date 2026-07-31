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

import de.bixilon.minosoft.terrain.distant.DistantSemanticDigest
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.SortedMap
import java.util.TreeMap

/** Horizontal quadtree operations for full-height distant columns. */
object DistantPageHierarchy {
    const val MAXIMUM_DETAIL_LEVEL = 30

    val order: Comparator<TerrainPageKey> =
        compareBy<TerrainPageKey>({ it.detailLevel }, { it.z }, { it.x }, { it.worldEpoch })

    fun parent(page: TerrainPageKey): TerrainPageKey {
        requireDistant(page)
        require(page.detailLevel < MAXIMUM_DETAIL_LEVEL) { "Distant page has no representable parent" }
        return page.copy(
            detailLevel = Math.addExact(page.detailLevel, 1),
            x = Math.floorDiv(page.x, 2L),
            z = Math.floorDiv(page.z, 2L),
        )
    }

    fun children(page: TerrainPageKey): List<TerrainPageKey> {
        requireDistant(page)
        require(page.detailLevel > 0) { "Base distant pages do not have indexed children" }
        val childX = Math.multiplyExact(page.x, 2L)
        val childZ = Math.multiplyExact(page.z, 2L)
        return listOf(
            page.copy(detailLevel = page.detailLevel - 1, x = childX, z = childZ),
            page.copy(detailLevel = page.detailLevel - 1, x = Math.addExact(childX, 1L), z = childZ),
            page.copy(detailLevel = page.detailLevel - 1, x = childX, z = Math.addExact(childZ, 1L)),
            page.copy(
                detailLevel = page.detailLevel - 1,
                x = Math.addExact(childX, 1L),
                z = Math.addExact(childZ, 1L),
            ),
        )
    }

    fun cardinalNeighbours(page: TerrainPageKey): List<TerrainPageKey> {
        requireDistant(page)
        return listOf(
            page.copy(x = Math.subtractExact(page.x, 1L)),
            page.copy(x = Math.addExact(page.x, 1L)),
            page.copy(z = Math.subtractExact(page.z, 1L)),
            page.copy(z = Math.addExact(page.z, 1L)),
        )
    }

    fun lineage(page: TerrainPageKey, maximumDetailLevel: Int): List<TerrainPageKey> {
        requireDistant(page)
        require(maximumDetailLevel in page.detailLevel..MAXIMUM_DETAIL_LEVEL) {
            "Maximum detail level is outside the distant hierarchy"
        }
        val result = ArrayList<TerrainPageKey>(maximumDetailLevel - page.detailLevel + 1)
        var current = page
        result += current
        while (current.detailLevel < maximumDetailLevel) {
            current = parent(current)
            result += current
        }
        return result
    }

    fun requireDistant(page: TerrainPageKey) {
        require(page.domain == TerrainDomain.DISTANT) { "Distant hierarchy requires a distant page" }
        require(page.y == 0L) { "Distant hierarchy pages cover full-height columns and require y=0" }
        require(page.detailLevel <= MAXIMUM_DETAIL_LEVEL) { "Distant page detail level is too large" }
    }
}

enum class DistantPageBuildState {
    DIRTY,
    REQUESTED,
    BUILDING,
    READY,
}

/**
 * One immutable hierarchy entry. [renderRevision] counts accepted artifacts;
 * [renderedDirtyRevision] identifies which invalidation the active artifact
 * consumed. A dirty replacement therefore retains its last-known-good render
 * revision without claiming that it represents current source data.
 */
data class DistantHierarchyPage(
    val key: TerrainPageKey,
    val sourceRevision: Long,
    val dirtyRevision: Long,
    val renderRevision: Long,
    val renderedDirtyRevision: Long?,
    val completeness: DistantSourceCompleteness,
    val presentChildMask: Int,
    val completeChildMask: Int,
    val semanticDigest: DistantSemanticDigest,
    val derived: Boolean,
    val buildState: DistantPageBuildState,
) {
    val dirty: Boolean get() = renderedDirtyRevision != dirtyRevision
    val hasActiveArtifact: Boolean get() = renderRevision > 0L

    init {
        DistantPageHierarchy.requireDistant(key)
        require(sourceRevision >= 0L) { "Distant hierarchy source revision must not be negative" }
        require(dirtyRevision > 0L) { "Distant hierarchy dirty revision must be positive" }
        require(renderRevision >= 0L) { "Distant hierarchy render revision must not be negative" }
        require(renderedDirtyRevision == null || renderedDirtyRevision > 0L) {
            "Distant hierarchy rendered-dirty revision must be positive"
        }
        require(presentChildMask and CHILD_MASK.inv() == 0) { "Distant present-child mask is invalid" }
        require(completeChildMask and CHILD_MASK.inv() == 0) { "Distant complete-child mask is invalid" }
        require(completeChildMask and presentChildMask == completeChildMask) {
            "Complete distant children must also be present"
        }
        require(key.detailLevel > 0 || presentChildMask == 0) { "Base distant pages cannot have child state" }
        require(key.detailLevel > 0 || completeChildMask == 0) { "Base distant pages cannot have child state" }
        require(!derived || key.detailLevel > 0) { "A base distant page cannot be derived" }
        require(!dirty || buildState != DistantPageBuildState.READY) {
            "A dirty distant page cannot claim READY"
        }
        require(dirty || buildState == DistantPageBuildState.READY) {
            "A current distant page must claim READY"
        }
    }

    companion object {
        const val CHILD_MASK = 0b1111
    }
}

interface DistantPageIndexView {
    val worldEpoch: Long
    val revision: Long
    val sourcePublicationRevision: Long
    val dirtyRevision: Long
    val pages: Map<TerrainPageKey, DistantHierarchyPage>
}

class DistantPageIndexSnapshot(
    override val worldEpoch: Long,
    override val revision: Long,
    override val sourcePublicationRevision: Long,
    override val dirtyRevision: Long,
    pages: Map<TerrainPageKey, DistantHierarchyPage>,
) : DistantPageIndexView {
    override val pages: SortedMap<TerrainPageKey, DistantHierarchyPage> =
        Collections.unmodifiableSortedMap(
            TreeMap<TerrainPageKey, DistantHierarchyPage>(DistantPageHierarchy.order).apply { putAll(pages) },
        )
    val dirtyPages: List<TerrainPageKey> = Collections.unmodifiableList(
        this.pages.values.asSequence().filter(DistantHierarchyPage::dirty).map(DistantHierarchyPage::key).toList(),
    )

    init {
        require(worldEpoch >= 0L) { "Distant hierarchy world epoch must not be negative" }
        require(revision >= 0L) { "Distant hierarchy revision must not be negative" }
        require(sourcePublicationRevision >= 0L) { "Distant source publication revision must not be negative" }
        require(dirtyRevision >= 0L) { "Distant dirty revision must not be negative" }
        require(this.pages.keys.all { it.worldEpoch == worldEpoch }) {
            "Every distant hierarchy page must belong to world epoch $worldEpoch"
        }
    }
}

sealed interface DistantSourcePublication {
    class Published(
        val snapshot: DistantPageIndexSnapshot,
        sourceChangedPages: Collection<TerrainPageKey>,
        dirtiedPages: Collection<TerrainPageKey>,
    ) : DistantSourcePublication {
        val sourceChangedPages: List<TerrainPageKey> = java.util.List.copyOf(sourceChangedPages)
        val dirtiedPages: List<TerrainPageKey> = java.util.List.copyOf(dirtiedPages)
    }

    data class Unchanged(val active: DistantHierarchyPage) : DistantSourcePublication
    data class Stale(val active: DistantHierarchyPage) : DistantSourcePublication
    data class Conflict(val active: DistantHierarchyPage) : DistantSourcePublication
    data class CapacityRejected(val requiredPages: Int, val maximumPages: Int) : DistantSourcePublication
}

enum class DistantSourceMutationStatus {
    PUBLISHED,
    UNCHANGED,
    STALE,
    CONFLICT,
    CAPACITY_REJECTED,
}

/**
 * Source-publication result without an eager immutable hierarchy snapshot.
 * Render-thread owners that already publish a bounded frame snapshot use this
 * form to avoid copying and sorting the complete index for every drained page.
 */
class DistantSourceMutation(
    val status: DistantSourceMutationStatus,
    val active: DistantHierarchyPage? = null,
    val requiredPages: Int? = null,
    val maximumPages: Int? = null,
    sourceChangedPages: Collection<TerrainPageKey> = emptyList(),
    dirtiedPages: Collection<TerrainPageKey> = emptyList(),
) {
    val sourceChangedPages: List<TerrainPageKey> = java.util.List.copyOf(sourceChangedPages)
    val dirtiedPages: List<TerrainPageKey> = java.util.List.copyOf(dirtiedPages)

    init {
        require((status == DistantSourceMutationStatus.CAPACITY_REJECTED) == (requiredPages != null))
        require((requiredPages == null) == (maximumPages == null))
        require(requiredPages == null || requiredPages > maximumPages!!)
        require(status == DistantSourceMutationStatus.PUBLISHED ||
            this.sourceChangedPages.isEmpty() && this.dirtiedPages.isEmpty()
        )
    }
}

data class DistantSourcePageUpdate(
    val page: TerrainPageKey,
    val sourceRevision: Long,
    val completeness: DistantSourceCompleteness,
    val semanticDigest: DistantSemanticDigest,
)

sealed interface DistantSourceBatchMutation {
    class Published(
        publishedPages: Collection<TerrainPageKey>,
        sourceChangedPages: Collection<TerrainPageKey>,
        dirtiedPages: Collection<TerrainPageKey>,
    ) : DistantSourceBatchMutation {
        val publishedPages: List<TerrainPageKey> = java.util.List.copyOf(publishedPages)
        val sourceChangedPages: List<TerrainPageKey> = java.util.List.copyOf(sourceChangedPages)
        val dirtiedPages: List<TerrainPageKey> = java.util.List.copyOf(dirtiedPages)
    }

    data object Unchanged : DistantSourceBatchMutation

    data class Rejected(
        val page: TerrainPageKey,
        val status: DistantSourceMutationStatus,
        val active: DistantHierarchyPage? = null,
        val requiredPages: Int? = null,
        val maximumPages: Int? = null,
    ) : DistantSourceBatchMutation {
        init {
            require(status != DistantSourceMutationStatus.PUBLISHED && status != DistantSourceMutationStatus.UNCHANGED)
            require((status == DistantSourceMutationStatus.CAPACITY_REJECTED) == (requiredPages != null))
            require((requiredPages == null) == (maximumPages == null))
        }
    }
}

sealed interface DistantRenderPublication {
    data class Published(val page: DistantHierarchyPage) : DistantRenderPublication
    data class Stale(val active: DistantHierarchyPage?) : DistantRenderPublication
}

sealed interface DistantSourceRemoval {
    class Removed(
        val snapshot: DistantPageIndexSnapshot,
        removedPages: Collection<TerrainPageKey>,
        sourceChangedPages: Collection<TerrainPageKey>,
        dirtiedPages: Collection<TerrainPageKey>,
    ) : DistantSourceRemoval {
        val removedPages: List<TerrainPageKey> = java.util.List.copyOf(removedPages)
        val sourceChangedPages: List<TerrainPageKey> = java.util.List.copyOf(sourceChangedPages)
        val dirtiedPages: List<TerrainPageKey> = java.util.List.copyOf(dirtiedPages)
    }

    data object Absent : DistantSourceRemoval
}

class DistantPageInvalidation(
    val snapshot: DistantPageIndexSnapshot,
    dirtiedPages: Collection<TerrainPageKey>,
) {
    val dirtiedPages: List<TerrainPageKey> = java.util.List.copyOf(dirtiedPages)
}

/**
 * Bounded, deterministic hierarchy state. Mutations publish a whole immutable
 * snapshot; capacity failure and stale publication leave the active index
 * untouched.
 */
class DistantPageHierarchyIndex(
    val worldEpoch: Long,
    val maximumDetailLevel: Int,
    val maximumPages: Int,
) {
    init {
        require(worldEpoch >= 0L) { "Distant hierarchy world epoch must not be negative" }
        require(maximumDetailLevel >= 0) { "Distant maximum detail level must not be negative" }
        require(maximumDetailLevel <= DistantPageHierarchy.MAXIMUM_DETAIL_LEVEL) {
            "Distant maximum detail level is too large"
        }
        require(maximumPages > 0) { "Distant hierarchy capacity must be positive" }
    }

    private var revision = 0L
    private var sourcePublicationRevision = 0L
    private var dirtyRevision = 0L
    private var pages = LinkedHashMap<TerrainPageKey, DistantHierarchyPage>()
    private val borrowedView = object : DistantPageIndexView {
        override val worldEpoch: Long get() = this@DistantPageHierarchyIndex.worldEpoch
        override val revision: Long get() = this@DistantPageHierarchyIndex.revision
        override val sourcePublicationRevision: Long get() = this@DistantPageHierarchyIndex.sourcePublicationRevision
        override val dirtyRevision: Long get() = this@DistantPageHierarchyIndex.dirtyRevision
        override val pages: Map<TerrainPageKey, DistantHierarchyPage> get() = this@DistantPageHierarchyIndex.pages
    }

    @Synchronized
    fun snapshot(): DistantPageIndexSnapshot = snapshotOf(pages)

    /** The view is valid only for the duration of [reader] under the index lock. */
    @Synchronized
    fun <T> readView(reader: (DistantPageIndexView) -> T): T = reader(borrowedView)

    @Synchronized
    operator fun get(page: TerrainPageKey): DistantHierarchyPage? {
        requirePage(page)
        return pages[page]
    }

    @Synchronized
    fun publishSource(
        page: TerrainPageKey,
        sourceRevision: Long,
        completeness: DistantSourceCompleteness,
        semanticDigest: DistantSemanticDigest,
    ): DistantSourcePublication {
        val mutation = mutateSource(page, sourceRevision, completeness, semanticDigest)
        return when (mutation.status) {
            DistantSourceMutationStatus.PUBLISHED -> DistantSourcePublication.Published(
                snapshot = snapshotOf(pages),
                sourceChangedPages = mutation.sourceChangedPages,
                dirtiedPages = mutation.dirtiedPages,
            )
            DistantSourceMutationStatus.UNCHANGED -> DistantSourcePublication.Unchanged(checkNotNull(mutation.active))
            DistantSourceMutationStatus.STALE -> DistantSourcePublication.Stale(checkNotNull(mutation.active))
            DistantSourceMutationStatus.CONFLICT -> DistantSourcePublication.Conflict(checkNotNull(mutation.active))
            DistantSourceMutationStatus.CAPACITY_REJECTED -> DistantSourcePublication.CapacityRejected(
                requiredPages = checkNotNull(mutation.requiredPages),
                maximumPages = checkNotNull(mutation.maximumPages),
            )
        }
    }

    @Synchronized
    fun publishSourceMutation(
        page: TerrainPageKey,
        sourceRevision: Long,
        completeness: DistantSourceCompleteness,
        semanticDigest: DistantSemanticDigest,
    ): DistantSourceMutation = mutateSource(page, sourceRevision, completeness, semanticDigest)

    /**
     * Publishes a set of independent source pages as one index mutation. Every
     * affected parent is derived once after all base updates are staged, which
     * keeps reset ingestion proportional to the resulting hierarchy rather
     * than to every source-page lineage.
     */
    @Synchronized
    fun publishSourceBatchMutation(updates: Collection<DistantSourcePageUpdate>): DistantSourceBatchMutation {
        if (updates.isEmpty()) return DistantSourceBatchMutation.Unchanged
        val unique = LinkedHashMap<TerrainPageKey, DistantSourcePageUpdate>()
        for (update in updates.sortedWith { first, second ->
            DistantPageHierarchy.order.compare(first.page, second.page)
        }) {
            requirePage(update.page)
            require(update.sourceRevision >= 0L) { "Distant source revision must not be negative" }
            val duplicate = unique.putIfAbsent(update.page, update)
            require(duplicate == null || duplicate == update) { "Conflicting duplicate distant source update" }
        }

        val changed = ArrayList<DistantSourcePageUpdate>(unique.size)
        for (update in unique.values) {
            val active = pages[update.page]
            if (active == null) {
                changed += update
                continue
            }
            if (update.sourceRevision < active.sourceRevision) {
                return DistantSourceBatchMutation.Rejected(
                    update.page,
                    DistantSourceMutationStatus.STALE,
                    active,
                )
            }
            if (update.sourceRevision == active.sourceRevision) {
                if (active.completeness != update.completeness || active.semanticDigest != update.semanticDigest) {
                    return DistantSourceBatchMutation.Rejected(
                        update.page,
                        DistantSourceMutationStatus.CONFLICT,
                        active,
                    )
                }
                continue
            }
            changed += update
        }
        if (changed.isEmpty()) return DistantSourceBatchMutation.Unchanged

        val lineages = changed.associate { update ->
            update.page to DistantPageHierarchy.lineage(update.page, maximumDetailLevel)
        }
        val requiredKeys = lineages.values.asSequence().flatten().filterNot(pages::containsKey).toSet()
        val requiredPages = Math.addExact(pages.size, requiredKeys.size)
        if (requiredPages > maximumPages) {
            return DistantSourceBatchMutation.Rejected(
                page = changed.first().page,
                status = DistantSourceMutationStatus.CAPACITY_REJECTED,
                requiredPages = requiredPages,
                maximumPages = maximumPages,
            )
        }

        val nextSourcePublicationRevision = Math.addExact(sourcePublicationRevision, changed.size.toLong())
        val eventDirtyRevision = Math.addExact(dirtyRevision, 1L)
        val nextRevision = Math.addExact(revision, 1L)
        val staged = LinkedHashMap<TerrainPageKey, DistantHierarchyPage>(requiredKeys.size)
        val sourceChanged = linkedSetOf<TerrainPageKey>()
        for (update in changed) {
            val previous = pages[update.page]
            staged[update.page] = sourcePage(
                key = update.page,
                sourceRevision = update.sourceRevision,
                dirtyRevision = eventDirtyRevision,
                completeness = update.completeness,
                semanticDigest = update.semanticDigest,
                previous = previous,
            )
            sourceChanged += update.page
        }

        val parents = lineages.values.asSequence().flatten().filter { it.detailLevel > 0 }
            .distinct().sortedWith(DistantPageHierarchy.order).toList()
        for (parent in parents) {
            val previous = pages[parent]
            val derived = deriveParent(
                key = parent,
                pageAt = { staged[it] ?: pages[it] },
                previous = previous,
                eventDirtyRevision = eventDirtyRevision,
                eventSourcePublicationRevision = nextSourcePublicationRevision,
            )
            staged[parent] = derived
            if (previous == null || previous.semanticDigest != derived.semanticDigest ||
                previous.completeness != derived.completeness || previous.presentChildMask != derived.presentChildMask ||
                previous.completeChildMask != derived.completeChildMask
            ) {
                sourceChanged += parent
            }
        }

        val dirtied = linkedSetOf<TerrainPageKey>()
        for (lineage in lineages.values) {
            for (page in lineage) {
                dirtied += page
                for (neighbour in DistantPageHierarchy.cardinalNeighbours(page)) {
                    if (neighbour in staged || neighbour in pages) dirtied += neighbour
                }
            }
        }
        for (dirtyPage in dirtied) {
            val current = staged[dirtyPage] ?: pages.getValue(dirtyPage)
            staged[dirtyPage] = current.copy(
                dirtyRevision = eventDirtyRevision,
                buildState = DistantPageBuildState.DIRTY,
            )
        }

        sourcePublicationRevision = nextSourcePublicationRevision
        dirtyRevision = eventDirtyRevision
        staged.forEach(pages::put)
        revision = nextRevision
        return DistantSourceBatchMutation.Published(
            publishedPages = changed.map(DistantSourcePageUpdate::page),
            sourceChangedPages = sourceChanged.sortedWith(DistantPageHierarchy.order),
            dirtiedPages = dirtied.sortedWith(DistantPageHierarchy.order),
        )
    }

    private fun mutateSource(
        page: TerrainPageKey,
        sourceRevision: Long,
        completeness: DistantSourceCompleteness,
        semanticDigest: DistantSemanticDigest,
    ): DistantSourceMutation {
        requirePage(page)
        require(sourceRevision >= 0L) { "Distant source revision must not be negative" }
        val active = pages[page]
        if (active != null) {
            if (sourceRevision < active.sourceRevision) {
                return DistantSourceMutation(DistantSourceMutationStatus.STALE, active)
            }
            if (sourceRevision == active.sourceRevision) {
                return if (active.completeness == completeness && active.semanticDigest == semanticDigest) {
                    DistantSourceMutation(DistantSourceMutationStatus.UNCHANGED, active)
                } else {
                    DistantSourceMutation(DistantSourceMutationStatus.CONFLICT, active)
                }
            }
        }

        val lineage = DistantPageHierarchy.lineage(page, maximumDetailLevel)
        val required = lineage.count { it !in pages }
        if (pages.size + required > maximumPages) {
            return DistantSourceMutation(
                DistantSourceMutationStatus.CAPACITY_REJECTED,
                requiredPages = pages.size + required,
                maximumPages = maximumPages,
            )
        }

        val nextSourcePublicationRevision = Math.addExact(sourcePublicationRevision, 1L)
        val eventDirtyRevision = Math.addExact(dirtyRevision, 1L)
        val nextRevision = Math.addExact(revision, 1L)
        val updates = LinkedHashMap<TerrainPageKey, DistantHierarchyPage>(lineage.size * 2)
        val sourceChanged = ArrayList<TerrainPageKey>(lineage.size)
        val oldBase = pages[page]
        updates[page] = sourcePage(
            key = page,
            sourceRevision = sourceRevision,
            dirtyRevision = eventDirtyRevision,
            completeness = completeness,
            semanticDigest = semanticDigest,
            previous = oldBase,
        )
        sourceChanged += page

        for (parent in lineage.drop(1)) {
            val previous = pages[parent]
            val derived = deriveParent(
                key = parent,
                pageAt = { updates[it] ?: pages[it] },
                previous = previous,
                eventDirtyRevision = eventDirtyRevision,
                eventSourcePublicationRevision = nextSourcePublicationRevision,
            )
            updates[parent] = derived
            if (previous == null || previous.semanticDigest != derived.semanticDigest ||
                previous.completeness != derived.completeness
            ) {
                sourceChanged += parent
            }
        }

        val dirtied = linkedSetOf<TerrainPageKey>()
        for (changed in lineage) {
            dirtied += changed
            for (neighbour in DistantPageHierarchy.cardinalNeighbours(changed)) {
                if (neighbour in updates || neighbour in pages) dirtied += neighbour
            }
        }
        for (dirtyPage in dirtied) {
            val current = updates[dirtyPage] ?: pages.getValue(dirtyPage)
            updates[dirtyPage] = current.copy(
                dirtyRevision = eventDirtyRevision,
                buildState = DistantPageBuildState.DIRTY,
            )
        }

        sourcePublicationRevision = nextSourcePublicationRevision
        dirtyRevision = eventDirtyRevision
        updates.forEach(pages::put)
        revision = nextRevision
        return DistantSourceMutation(
            status = DistantSourceMutationStatus.PUBLISHED,
            sourceChangedPages = sourceChanged.sortedWith(DistantPageHierarchy.order),
            dirtiedPages = dirtied.sortedWith(DistantPageHierarchy.order),
        )
    }

    @Synchronized
    fun removeSource(page: TerrainPageKey): DistantSourceRemoval {
        requirePage(page)
        if (page !in pages) return DistantSourceRemoval.Absent
        val lineage = DistantPageHierarchy.lineage(page, maximumDetailLevel)
        val next = LinkedHashMap(pages)
        val removed = linkedSetOf<TerrainPageKey>()
        val sourceChanged = linkedSetOf<TerrainPageKey>()
        next.remove(page)
        removed += page
        sourceChanged += page
        val nextSourcePublicationRevision = Math.addExact(sourcePublicationRevision, 1L)
        val eventDirtyRevision = Math.addExact(dirtyRevision, 1L)
        val nextRevision = Math.addExact(revision, 1L)

        for (parent in lineage.drop(1)) {
            val previous = next[parent] ?: continue
            val hasChildren = DistantPageHierarchy.children(parent).any(next::containsKey)
            if (!hasChildren && previous.derived) {
                next.remove(parent)
                removed += parent
                sourceChanged += parent
                continue
            }
            if (!hasChildren) continue
            val derived = deriveParent(
                key = parent,
                pageAt = next::get,
                previous = previous,
                eventDirtyRevision = eventDirtyRevision,
                eventSourcePublicationRevision = nextSourcePublicationRevision,
            )
            next[parent] = derived
            if (previous.semanticDigest != derived.semanticDigest ||
                previous.completeness != derived.completeness ||
                previous.presentChildMask != derived.presentChildMask ||
                previous.completeChildMask != derived.completeChildMask
            ) {
                sourceChanged += parent
            }
        }

        val dirtied = linkedSetOf<TerrainPageKey>()
        for (changed in lineage) {
            if (changed in next) dirtied += changed
            for (neighbour in DistantPageHierarchy.cardinalNeighbours(changed)) {
                if (neighbour in next) dirtied += neighbour
            }
        }
        for (dirtyPage in dirtied) {
            val current = next.getValue(dirtyPage)
            next[dirtyPage] = current.copy(
                dirtyRevision = eventDirtyRevision,
                buildState = DistantPageBuildState.DIRTY,
            )
        }
        sourcePublicationRevision = nextSourcePublicationRevision
        dirtyRevision = eventDirtyRevision
        pages = next
        revision = nextRevision
        return DistantSourceRemoval.Removed(
            snapshotOf(next),
            removed.sortedWith(DistantPageHierarchy.order),
            sourceChanged.sortedWith(DistantPageHierarchy.order),
            dirtied.sortedWith(DistantPageHierarchy.order),
        )
    }

    @Synchronized
    fun invalidateRender(requestedPages: Collection<TerrainPageKey>): DistantPageInvalidation {
        val dirtied = invalidateRenderPagesInternal(requestedPages)
        return DistantPageInvalidation(snapshotOf(pages), dirtied)
    }

    @Synchronized
    fun invalidateRenderPages(requestedPages: Collection<TerrainPageKey>): List<TerrainPageKey> =
        invalidateRenderPagesInternal(requestedPages)

    private fun invalidateRenderPagesInternal(requestedPages: Collection<TerrainPageKey>): List<TerrainPageKey> {
        requestedPages.forEach(::requirePage)
        val dirtied = requestedPages.asSequence().distinct().filter(pages::containsKey)
            .sortedWith(DistantPageHierarchy.order).toList()
        if (dirtied.isEmpty()) return emptyList()
        val nextDirtyRevision = Math.addExact(dirtyRevision, 1L)
        val nextRevision = Math.addExact(revision, 1L)
        for (page in dirtied) {
            pages[page] = pages.getValue(page).copy(
                dirtyRevision = nextDirtyRevision,
                buildState = DistantPageBuildState.DIRTY,
            )
        }
        dirtyRevision = nextDirtyRevision
        revision = nextRevision
        return dirtied
    }

    @Synchronized
    fun markRequested(page: TerrainPageKey, expectedDirtyRevision: Long): Boolean =
        transition(page, expectedDirtyRevision, DistantPageBuildState.DIRTY, DistantPageBuildState.REQUESTED)

    @Synchronized
    fun markBuilding(page: TerrainPageKey, expectedDirtyRevision: Long): Boolean =
        transition(page, expectedDirtyRevision, DistantPageBuildState.REQUESTED, DistantPageBuildState.BUILDING)

    @Synchronized
    fun markBuildFailed(page: TerrainPageKey, expectedDirtyRevision: Long): Boolean {
        requirePage(page)
        val active = pages[page] ?: return false
        if (active.dirtyRevision != expectedDirtyRevision || !active.dirty) return false
        if (active.buildState != DistantPageBuildState.REQUESTED &&
            active.buildState != DistantPageBuildState.BUILDING
        ) return false
        publishState(active.copy(buildState = DistantPageBuildState.DIRTY))
        return true
    }

    @Synchronized
    fun publishRender(
        page: TerrainPageKey,
        expectedSourceRevision: Long,
        expectedDirtyRevision: Long,
    ): DistantRenderPublication {
        requirePage(page)
        val active = pages[page]
        if (active == null || active.sourceRevision != expectedSourceRevision ||
            active.dirtyRevision != expectedDirtyRevision || !active.dirty
        ) return DistantRenderPublication.Stale(active)
        val published = active.copy(
            renderRevision = Math.addExact(active.renderRevision, 1L),
            renderedDirtyRevision = active.dirtyRevision,
            buildState = DistantPageBuildState.READY,
        )
        publishState(published)
        return DistantRenderPublication.Published(published)
    }

    private fun transition(
        page: TerrainPageKey,
        expectedDirtyRevision: Long,
        from: DistantPageBuildState,
        to: DistantPageBuildState,
    ): Boolean {
        requirePage(page)
        val active = pages[page] ?: return false
        if (active.dirtyRevision != expectedDirtyRevision || !active.dirty || active.buildState != from) return false
        publishState(active.copy(buildState = to))
        return true
    }

    private fun publishState(page: DistantHierarchyPage) {
        val nextRevision = Math.addExact(revision, 1L)
        pages[page.key] = page
        revision = nextRevision
    }

    private fun sourcePage(
        key: TerrainPageKey,
        sourceRevision: Long,
        dirtyRevision: Long,
        completeness: DistantSourceCompleteness,
        semanticDigest: DistantSemanticDigest,
        previous: DistantHierarchyPage?,
    ) = DistantHierarchyPage(
        key = key,
        sourceRevision = sourceRevision,
        dirtyRevision = dirtyRevision,
        renderRevision = previous?.renderRevision ?: 0L,
        renderedDirtyRevision = previous?.renderedDirtyRevision,
        completeness = completeness,
        presentChildMask = 0,
        completeChildMask = 0,
        semanticDigest = semanticDigest,
        derived = false,
        buildState = DistantPageBuildState.DIRTY,
    )

    private fun deriveParent(
        key: TerrainPageKey,
        pageAt: (TerrainPageKey) -> DistantHierarchyPage?,
        previous: DistantHierarchyPage?,
        eventDirtyRevision: Long,
        eventSourcePublicationRevision: Long,
    ): DistantHierarchyPage {
        val children = DistantPageHierarchy.children(key)
        var presentMask = 0
        var completeMask = 0
        for ((index, child) in children.withIndex()) {
            val value = pageAt(child) ?: continue
            presentMask = presentMask or (1 shl index)
            if (value.completeness == DistantSourceCompleteness.COMPLETE) {
                completeMask = completeMask or (1 shl index)
            }
        }
        val completeness = if (completeMask == DistantHierarchyPage.CHILD_MASK) {
            DistantSourceCompleteness.COMPLETE
        } else {
            DistantSourceCompleteness.PARTIAL
        }
        val digest = childDigest(key, children.map(pageAt))
        val sourceChanged = previous == null || previous.semanticDigest != digest ||
            previous.completeness != completeness || previous.presentChildMask != presentMask ||
            previous.completeChildMask != completeMask
        return DistantHierarchyPage(
            key = key,
            sourceRevision = if (sourceChanged) {
                maxOf(eventSourcePublicationRevision, previous?.sourceRevision?.let { Math.addExact(it, 1L) } ?: 0L)
            } else {
                previous.sourceRevision
            },
            dirtyRevision = eventDirtyRevision,
            renderRevision = previous?.renderRevision ?: 0L,
            renderedDirtyRevision = previous?.renderedDirtyRevision,
            completeness = completeness,
            presentChildMask = presentMask,
            completeChildMask = completeMask,
            semanticDigest = digest,
            derived = true,
            buildState = DistantPageBuildState.DIRTY,
        )
    }

    private fun childDigest(
        parent: TerrainPageKey,
        children: List<DistantHierarchyPage?>,
    ): DistantSemanticDigest {
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.update(DIGEST_SCHEMA.toByte())
        digest.putInt(parent.detailLevel)
        digest.putLong(parent.x)
        digest.putLong(parent.z)
        children.forEachIndexed { index, child ->
            digest.update(index.toByte())
            if (child == null) {
                digest.update(0)
            } else {
                digest.update(1)
                digest.putText(child.semanticDigest.encodedValue)
                digest.update(child.completeness.ordinal.toByte())
            }
        }
        return DistantSemanticDigest(DIGEST_SCHEMA, DIGEST_ALGORITHM, HexFormat.of().formatHex(digest.digest()))
    }

    private fun requirePage(page: TerrainPageKey) {
        DistantPageHierarchy.requireDistant(page)
        require(page.worldEpoch == worldEpoch) { "Distant page belongs to another world epoch" }
        require(page.detailLevel <= maximumDetailLevel) { "Distant page exceeds maximum detail level" }
    }

    private fun snapshotOf(current: Map<TerrainPageKey, DistantHierarchyPage>) =
        DistantPageIndexSnapshot(worldEpoch, revision, sourcePublicationRevision, dirtyRevision, current)

    private companion object {
        const val DIGEST_SCHEMA = 1
        const val DIGEST_ALGORITHM = "SHA-256"
    }
}

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
