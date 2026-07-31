/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.network

import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey

sealed interface DistantResponseAdmission {
    class Accepted(pages: Collection<DistantVerticalPage>, val requestComplete: Boolean) : DistantResponseAdmission {
        val pages: List<DistantVerticalPage> = java.util.List.copyOf(pages)
    }
    data class Rejected(val reason: DistantResponseRejection) : DistantResponseAdmission
}

enum class DistantResponseRejection {
    WRONG_WORLD,
    UNKNOWN_REQUEST,
    UNREQUESTED_PAGE,
    DUPLICATE_PAGE,
    UNSUPPORTED_DETAIL,
    STALE_PAGE,
}

/** Exact expected-page ledger. A rejected response publishes no partial subset. */
class DistantTerrainRequestTracker(
    private var world: DistantProtocolWorld,
    private val maximumOutstandingRequests: Int,
    private val maximumDetailLevel: Int,
    private val localSourceRevision: (TerrainPageKey) -> Long? = { null },
) {
    private data class Outstanding(
        val expected: Map<TerrainPageKey, Long>,
        val completed: MutableSet<TerrainPageKey> = linkedSetOf(),
    )

    init {
        require(maximumOutstandingRequests > 0)
        require(maximumDetailLevel in 0..DistantTerrainProtocolV2.MAXIMUM_DETAIL_LEVEL)
    }

    private val outstanding = linkedMapOf<Long, Outstanding>()

    @Synchronized
    fun register(request: DistantTerrainMessageV2.Request): Boolean {
        if (request.world != world || request.requestId in outstanding) return false
        if (outstanding.size >= maximumOutstandingRequests) return false
        if (request.pages.any { it.key.worldEpoch != world.worldEpoch || it.key.detailLevel > maximumDetailLevel }) return false
        val expected = request.pages.associate { it.key to it.minimumSourceRevision }
        if (expected.size != request.pages.size) return false
        outstanding[request.requestId] = Outstanding(java.util.Map.copyOf(expected))
        return true
    }

    fun admit(response: DistantTerrainMessageV2.Response): DistantResponseAdmission {
        val keys = response.pages.map(DistantVerticalPage::key)
        if (keys.toSet().size != keys.size) return DistantResponseAdmission.Rejected(DistantResponseRejection.DUPLICATE_PAGE)
        val request = synchronized(this) {
            if (response.world != world) {
                return DistantResponseAdmission.Rejected(DistantResponseRejection.WRONG_WORLD)
            }
            val active = outstanding[response.requestId]
                ?: return DistantResponseAdmission.Rejected(DistantResponseRejection.UNKNOWN_REQUEST)
            validateKeys(keys, active)?.let { return DistantResponseAdmission.Rejected(it) }
            active
        }
        val localRevisions = response.pages.associate { page -> page.key to (localSourceRevision(page.key) ?: 0L) }

        return synchronized(this) {
            if (response.world != world) {
                return@synchronized DistantResponseAdmission.Rejected(DistantResponseRejection.WRONG_WORLD)
            }
            if (outstanding[response.requestId] !== request) {
                return@synchronized DistantResponseAdmission.Rejected(DistantResponseRejection.UNKNOWN_REQUEST)
            }
            validateKeys(keys, request)?.let {
                return@synchronized DistantResponseAdmission.Rejected(it)
            }
            if (response.pages.any { page ->
                    val minimum = maxOf(request.expected.getValue(page.key), localRevisions.getValue(page.key))
                    page.sourceRevision < minimum
                }
            ) {
                return@synchronized DistantResponseAdmission.Rejected(DistantResponseRejection.STALE_PAGE)
            }

            request.completed += keys
            val complete = request.completed.size == request.expected.size
            if (complete) outstanding.remove(response.requestId)
            DistantResponseAdmission.Accepted(java.util.List.copyOf(response.pages), complete)
        }
    }

    @Synchronized
    fun cancel(requestId: Long): Boolean = outstanding.remove(requestId) != null

    @Synchronized
    fun replaceWorld(next: DistantProtocolWorld): Set<Long> {
        val cancelled = java.util.Set.copyOf(outstanding.keys)
        outstanding.clear()
        world = next
        return cancelled
    }

    @Synchronized
    fun outstandingRequestIds(): Set<Long> = java.util.Set.copyOf(outstanding.keys)

    private fun validateKeys(
        keys: List<TerrainPageKey>,
        request: Outstanding,
    ): DistantResponseRejection? = when {
        keys.any { it in request.completed } -> DistantResponseRejection.DUPLICATE_PAGE
        keys.any { it !in request.expected } -> DistantResponseRejection.UNREQUESTED_PAGE
        keys.any { it.detailLevel > maximumDetailLevel } -> DistantResponseRejection.UNSUPPORTED_DETAIL
        else -> null
    }
}
