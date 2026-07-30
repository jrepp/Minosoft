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
    private val contains: (ChunkPosition) -> Boolean,
    private val publish: (DistantLodTile) -> Unit,
) : Runnable {
    private val spiral = DistantChunkSpiral()
    private val pending = linkedMapOf<ChunkPosition, Long>()
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

    @Synchronized
    fun receive(message: DistantLodMessage) {
        when (message) {
            is DistantLodMessage.Hello -> {
                serverMaximumTiles = message.maximumTilesPerRequest
                serverMaximumRadius = message.maximumRadiusChunks
                exhausted = true
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "DISTANT_HORIZONS_NETWORK_READY radiusChunks=$serverMaximumRadius " +
                        "tilesPerRequest=$serverMaximumTiles"
                }
            }

            is DistantLodMessage.Response -> {
                for (tile in message.tiles) {
                    pending.remove(tile.position)
                    publish(tile)
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
    override fun run() {
        tick++
        if (!options.enabled || !options.networkTransferEnabled || serverMaximumTiles == 0) return
        pending.entries.removeIf { tick - it.value >= REQUEST_TIMEOUT_TICKS }

        val nextCenter = session.player.physics.positionInfo.chunkPosition
        val nextInner = session.world.view.viewDistance + 1
        val nextOuter = minOf(
            options.networkRadiusChunks,
            options.renderDistanceChunks,
            serverMaximumRadius,
            maximumContiguousDistantRadius(options.maximumTiles),
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
            if (contains(position) || position in pending) continue
            pending[position] = tick
            positions += position
        }
        if (positions.isEmpty()) return
        val request = DistantLodMessage.Request(NEXT_REQUEST.incrementAndGet(), positions)
        FabricClientPayloadChannels.send(
            session,
            DistantLodProtocol.CHANNEL,
            DistantLodProtocol.encode(request),
        )
        requestsSent++
        if (requestsSent == 1L || requestsSent % 128L == 0L) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "DISTANT_HORIZONS_NETWORK_REQUESTS sent=$requestsSent " +
                    "positions=${request.positions.size} pending=${pending.size}"
            }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_TICKS = 20L * 30L
        const val MAXIMUM_PENDING_TILES = 32
        val NEXT_REQUEST = AtomicInteger()
    }
}
