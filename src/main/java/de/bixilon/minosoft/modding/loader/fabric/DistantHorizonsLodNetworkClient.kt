/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld
import de.bixilon.minosoft.terrain.distant.network.DistantRequestedPage
import de.bixilon.minosoft.terrain.distant.network.DistantResponseAdmission
import de.bixilon.minosoft.terrain.distant.network.DistantResponseRejection
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainRequestTracker
import de.bixilon.minosoft.terrain.distant.toTopOnlyCompatibilityTile
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.distant.maximumContiguousDistantRadius
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client half of the optional source-native server LOD channel.
 *
 * No request is sent until the server advertises support. Requests contain only
 * bounded explicit missing positions, and stale outstanding positions expire.
 */
internal class DistantLodNetworkClient(
    private val session: PlaySession,
    private val options: DistantHorizonsOptions,
    private val maximumResidentTiles: Int = options.maximumTiles,
    private val contains: (ChunkPosition) -> Boolean,
    private val localSourceRevision: (ChunkPosition) -> Long?,
    private val publishTile: (DistantLodTile) -> Unit,
    private val publishPage: (DistantLodTile, DistantVerticalPage) -> Unit,
    private val sendPayload: (ByteArray) -> Unit = { payload ->
        FabricClientPayloadChannels.send(session, DistantLodProtocol.CHANNEL, payload)
    },
) : Runnable, AutoCloseable {
    private val spiral = DistantChunkSpiral()
    private val pending = linkedMapOf<ChunkPosition, Long>()
    private val receivedV1Positions = hashSetOf<ChunkPosition>()
    private val v2PendingRequests = linkedMapOf<Long, V2PendingRequest>()
    private var serverMaximumTiles = 0
    private var serverMaximumRadius = 0
    private var center: ChunkPosition? = null
    private var innerRadius = -1
    private var outerRadius = -1
    private var exhausted = true
    private var tick = 0L
    private var schedulerLogged = false
    private var requestsSent = 0L
    private var receivedTiles = 0L
    private var cancellationsSent = 0L
    private var cancellationFailures = 0L
    private var worldResets = 0L
    private val responseRejections = LongArray(DistantResponseRejection.entries.size)
    private var v2World: DistantProtocolWorld? = null
    private var v2MaximumDetailLevel = 0
    private var v2Tracker: DistantTerrainRequestTracker? = null
    private var v2ClientWorldEpoch = Long.MIN_VALUE
    private var closed = false

    data class Inspection(
        val protocolVersion: Int,
        val serverMaximumPages: Int,
        val serverMaximumRadius: Int,
        val pendingPages: Int,
        val outstandingRequestIds: List<Long>,
        val requestsSent: Long,
        val receivedPages: Long,
        val cancellationsSent: Long,
        val cancellationFailures: Long,
        val worldResets: Long,
        val responseRejections: Map<DistantResponseRejection, Long>,
    )

    @Synchronized
    fun receive(message: DistantLodMessage) {
        if (closed) return
        when (message) {
            is DistantLodMessage.Hello -> {
                serverMaximumTiles = message.maximumTilesPerRequest
                serverMaximumRadius = message.maximumRadiusChunks
                receivedV1Positions.clear()
                exhausted = true
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "DISTANT_HORIZONS_NETWORK_READY radiusChunks=$serverMaximumRadius " +
                        "tilesPerRequest=$serverMaximumTiles"
                }
            }

            is DistantLodMessage.Response -> {
                for (tile in message.tiles) {
                    pending.remove(tile.position)
                    receivedV1Positions += tile.position
                    publishTile(tile)
                    receivedTiles++
                }
                if (receivedTiles == 1L || receivedTiles % 128L == 0L) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                        "DISTANT_HORIZONS_NETWORK_TILES received=$receivedTiles pending=${pending.size}"
                    }
                }
            }

            is DistantLodMessage.Request ->
                throw IllegalArgumentException("A server sent a client-only distant LOD request")
        }
    }

    @Synchronized
    fun receive(message: DistantTerrainMessageV2) {
        if (closed) return
        when (message) {
            is DistantTerrainMessageV2.Hello -> {
                val activeLevel = session.world.name?.toString() ?: "minosoft:unknown"
                require(message.world.levelKey == activeLevel) { "Distant v2 hello targets another level" }
                receivedV1Positions.clear()
                if (v2World != null) {
                    // The new server epoch supersedes every old request. A
                    // receive callback must never enqueue a payload back onto
                    // its own transport event loop.
                    cancelOutstanding(send = false)
                    worldResets++
                }
                serverMaximumTiles = message.maximumPagesPerRequest
                serverMaximumRadius = message.maximumRadiusChunks
                v2World = message.world
                v2MaximumDetailLevel = message.maximumDetailLevel
                v2Tracker = DistantTerrainRequestTracker(
                    message.world,
                    maximumOutstandingRequests = MAXIMUM_PENDING_TILES,
                    maximumDetailLevel = message.maximumDetailLevel,
                    localSourceRevision = { key ->
                        localSourceRevision(ChunkPosition(Math.toIntExact(key.x), Math.toIntExact(key.z)))
                    },
                )
                v2ClientWorldEpoch = session.world.terrainEpoch
                pending.clear()
                v2PendingRequests.clear()
                exhausted = true
            }
            is DistantTerrainMessageV2.Response -> {
                val tracker = v2Tracker
                val admission = when {
                    tracker == null -> DistantResponseAdmission.Rejected(DistantResponseRejection.UNKNOWN_REQUEST)
                    session.world.terrainEpoch != v2ClientWorldEpoch ||
                        session.world.name?.toString() != message.world.levelKey ->
                        DistantResponseAdmission.Rejected(DistantResponseRejection.WRONG_WORLD)
                    else -> tracker.admit(message)
                }
                when (admission) {
                    is DistantResponseAdmission.Accepted -> {
                        for (page in admission.pages) {
                            val localPage = page.withWorldEpoch(session.world.terrainEpoch)
                            val tile = localPage.toTopOnlyCompatibilityTile()
                            pending.remove(tile.position)
                            publishPage(tile, localPage)
                            receivedTiles++
                        }
                        if (admission.requestComplete) v2PendingRequests.remove(message.requestId)
                    }
                    is DistantResponseAdmission.Superseded -> {
                        responseRejections[DistantResponseRejection.STALE_PAGE.ordinal] = Math.incrementExact(
                            responseRejections[DistantResponseRejection.STALE_PAGE.ordinal],
                        )
                        admission.keys.forEach { key ->
                            pending.remove(ChunkPosition(Math.toIntExact(key.x), Math.toIntExact(key.z)))
                        }
                        if (admission.requestComplete) v2PendingRequests.remove(message.requestId)
                    }
                    is DistantResponseAdmission.PartiallyAccepted -> {
                        for (page in admission.pages) {
                            val localPage = page.withWorldEpoch(session.world.terrainEpoch)
                            val tile = localPage.toTopOnlyCompatibilityTile()
                            pending.remove(tile.position)
                            publishPage(tile, localPage)
                            receivedTiles++
                        }
                        responseRejections[DistantResponseRejection.STALE_PAGE.ordinal] = Math.incrementExact(
                            responseRejections[DistantResponseRejection.STALE_PAGE.ordinal],
                        )
                        admission.supersededKeys.forEach { key ->
                            pending.remove(ChunkPosition(Math.toIntExact(key.x), Math.toIntExact(key.z)))
                        }
                        if (admission.requestComplete) v2PendingRequests.remove(message.requestId)
                    }
                    is DistantResponseAdmission.Rejected -> rejectResponse(message, admission.reason)
                }
            }
            is DistantTerrainMessageV2.Request,
            is DistantTerrainMessageV2.Cancel,
            -> throw IllegalArgumentException("A server sent a client-only distant v2 message")
        }
    }

    @Synchronized
    override fun run() {
        if (closed) return
        tick++
        if (!options.enabled || !options.networkTransferEnabled || serverMaximumTiles == 0) return
        expirePendingRequests()

        val nextCenter = session.player.physics.positionInfo.chunkPosition
        val nextInner = session.world.view.viewDistance + 1
        val nextOuter = minOf(
            options.networkRadiusChunks,
            options.renderDistanceChunks,
            serverMaximumRadius,
            maximumContiguousDistantRadius(maximumResidentTiles),
        )
        if (!schedulerLogged) {
            schedulerLogged = true
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "DISTANT_HORIZONS_NETWORK_SCHEDULER innerRadiusChunks=$nextInner " +
                    "outerRadiusChunks=$nextOuter"
            }
        }
        if (nextOuter <= nextInner) return
        if (
            exhausted ||
            nextCenter != center ||
            nextInner != innerRadius ||
            nextOuter != outerRadius
        ) {
            center = nextCenter
            innerRadius = nextInner
            outerRadius = nextOuter
            spiral.reset(nextCenter, nextInner, nextOuter)
            exhausted = false
        }

        val available = MAXIMUM_PENDING_TILES - pending.size
        if (available <= 0) return
        val limit = minOf(
            options.networkRequestTiles,
            serverMaximumTiles,
            DistantLodProtocol.MAXIMUM_TILES_PER_MESSAGE,
            available,
        )
        val positions = ArrayList<ChunkPosition>(limit)
        while (positions.size < limit) {
            val position = spiral.next()
            if (position == null) {
                exhausted = true
                break
            }
            if (contains(position) || position in receivedV1Positions || position in pending) continue
            pending[position] = tick
            positions += position
        }
        if (positions.isEmpty()) return
        val requestId = Integer.toUnsignedLong(NEXT_REQUEST.incrementAndGet())
        val negotiatedWorld = v2World
        val payload = if (negotiatedWorld == null) {
            DistantLodProtocol.encode(DistantLodMessage.Request(requestId.toInt(), positions))
        } else {
            val request = DistantTerrainMessageV2.Request(
                negotiatedWorld,
                requestId,
                positions.map { position ->
                    DistantRequestedPage(
                        TerrainPageKey(
                            TerrainDomain.DISTANT,
                            detailLevel = 0.coerceAtMost(v2MaximumDetailLevel),
                            x = position.x.toLong(),
                            y = 0,
                            z = position.z.toLong(),
                            worldEpoch = negotiatedWorld.worldEpoch,
                        ),
                        minimumSourceRevision = localSourceRevision(position) ?: 0,
                    )
                },
            )
            check(v2Tracker?.register(request) == true) { "Distant v2 request ledger is saturated" }
            v2PendingRequests[requestId] = V2PendingRequest(tick, positions.toSet())
            DistantTerrainProtocolV2.encode(request)
        }
        try {
            sendPayload(payload)
        } catch (error: Throwable) {
            positions.forEach(pending::remove)
            v2PendingRequests.remove(requestId)
            v2Tracker?.cancel(requestId)
            throw error
        }
        requestsSent++
        if (requestsSent == 1L || requestsSent % 128L == 0L) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "DISTANT_HORIZONS_NETWORK_REQUESTS sent=$requestsSent " +
                    "positions=${positions.size} pending=${pending.size} protocol=${if (negotiatedWorld == null) 1 else 2}"
            }
        }
    }

    @Synchronized
    fun inspect(): Inspection = Inspection(
        protocolVersion = if (v2World == null) 1 else 2,
        serverMaximumPages = serverMaximumTiles,
        serverMaximumRadius = serverMaximumRadius,
        pendingPages = pending.size,
        outstandingRequestIds = v2Tracker?.outstandingRequestIds()?.sorted().orEmpty(),
        requestsSent = requestsSent,
        receivedPages = receivedTiles,
        cancellationsSent = cancellationsSent,
        cancellationFailures = cancellationFailures,
        worldResets = worldResets,
        responseRejections = DistantResponseRejection.entries.associateWith { responseRejections[it.ordinal] },
    )

    private fun rejectResponse(
        message: DistantTerrainMessageV2.Response,
        reason: DistantResponseRejection,
    ) {
        responseRejections[reason.ordinal] = Math.incrementExact(responseRejections[reason.ordinal])
        val request = v2PendingRequests.remove(message.requestId)
        request?.positions?.forEach(pending::remove)
        v2Tracker?.cancel(message.requestId)
        // A response is already terminal at the server. Sending a cancellation
        // while handling it is redundant and can stall a single-threaded
        // transport by re-entering its outbound path.
        val total = responseRejections.sum()
        if (total == 1L || total % 128L == 0L) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                "Distant v2 response rejected reason=$reason request=${message.requestId} total=$total"
            }
        }
    }

    private fun expirePendingRequests() {
        val world = v2World
        if (world == null) {
            pending.entries.removeIf { tick - it.value >= REQUEST_TIMEOUT_TICKS }
            return
        }
        val expired = v2PendingRequests.filterValues { tick - it.startedTick >= REQUEST_TIMEOUT_TICKS }
        for ((requestId, request) in expired) {
            v2PendingRequests.remove(requestId)
            v2Tracker?.cancel(requestId)
            request.positions.forEach(pending::remove)
            trySendCancellation(world, requestId)
        }
    }

    @Synchronized
    fun close(sendCancellation: Boolean) {
        if (closed) return
        closed = true
        cancelOutstanding(sendCancellation)
        pending.clear()
        receivedV1Positions.clear()
        v2PendingRequests.clear()
        v2Tracker = null
        v2World = null
        serverMaximumTiles = 0
        serverMaximumRadius = 0
        exhausted = true
    }

    override fun close() = close(sendCancellation = false)

    private fun cancelOutstanding(send: Boolean) {
        val world = v2World
        val requestIds = v2PendingRequests.keys.toList()
        requestIds.forEach { requestId ->
            v2PendingRequests.remove(requestId)?.positions?.forEach(pending::remove)
            v2Tracker?.cancel(requestId)
            if (send && world != null) trySendCancellation(world, requestId)
        }
    }

    private fun trySendCancellation(world: DistantProtocolWorld, requestId: Long) {
        try {
            sendPayload(DistantTerrainProtocolV2.encode(DistantTerrainMessageV2.Cancel(world, requestId)))
            cancellationsSent++
        } catch (error: Throwable) {
            cancellationFailures++
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                "Distant v2 cancellation failed request=$requestId world=${world.levelKey}: ${error.message}"
            }
        }
    }

    private companion object {
        private data class V2PendingRequest(val startedTick: Long, val positions: Set<ChunkPosition>)

        // The negotiated server may drain the complete bounded 32-page window
        // at only one page per second before accounting for asynchronous chunk
        // generation. Keep the timeout safely beyond that valid service window
        // so an accepted response cannot become UNKNOWN_REQUEST by construction.
        const val REQUEST_TIMEOUT_TICKS = 20L * 120L
        const val MAXIMUM_PENDING_TILES = 32
        val NEXT_REQUEST = AtomicInteger()
    }
}

/** Protocol epochs validate remote request/response identity; render pages use the local world epoch. */
private fun DistantVerticalPage.withWorldEpoch(worldEpoch: Long): DistantVerticalPage {
    if (key.worldEpoch == worldEpoch) return this
    return DistantVerticalPage(
        key = key.copy(worldEpoch = worldEpoch),
        width = width,
        originY = originY,
        sourceRevision = sourceRevision,
        completeness = completeness,
        columns = columns,
    )
}
