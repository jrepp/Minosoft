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

import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface DistantLodMessage {
    data class Hello(
        val maximumTilesPerRequest: Int,
        val maximumRadiusChunks: Int,
    ) : DistantLodMessage

    data class Request(
        val requestId: Int,
        val positions: List<ChunkPosition>,
    ) : DistantLodMessage

    data class Response(
        val requestId: Int,
        val tiles: List<DistantLodTile>,
    ) : DistantLodMessage
}

/**
 * Small, versioned source-native LOD channel shared with the managed Fabric
 * bridge. Every count is bounded before allocation or tile decoding.
 */
internal object DistantLodProtocol {
    val CHANNEL = minosoft("distant_horizons_lod")
    const val MAXIMUM_TILES_PER_MESSAGE = 32
    const val MAXIMUM_RADIUS_CHUNKS = 256
    private const val MAGIC = 0x4D44484E
    private const val VERSION = 1
    private const val TYPE_HELLO = 0
    private const val TYPE_REQUEST = 1
    private const val TYPE_RESPONSE = 2
    private const val MAXIMUM_PALETTE_SIZE = 1_024
    private const val MAXIMUM_STRING_BYTES = 512

    fun encode(message: DistantLodMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            when (message) {
                is DistantLodMessage.Hello -> {
                    require(message.maximumTilesPerRequest in 1..MAXIMUM_TILES_PER_MESSAGE)
                    require(message.maximumRadiusChunks in 1..MAXIMUM_RADIUS_CHUNKS)
                    output.writeByte(TYPE_HELLO)
                    output.writeShort(message.maximumTilesPerRequest)
                    output.writeShort(message.maximumRadiusChunks)
                }

                is DistantLodMessage.Request -> {
                    require(message.positions.size in 1..MAXIMUM_TILES_PER_MESSAGE)
                    output.writeByte(TYPE_REQUEST)
                    output.writeInt(message.requestId)
                    output.writeShort(message.positions.size)
                    message.positions.forEach {
                        output.writeInt(it.x)
                        output.writeInt(it.z)
                    }
                }

                is DistantLodMessage.Response -> {
                    require(message.tiles.size in 1..MAXIMUM_TILES_PER_MESSAGE)
                    output.writeByte(TYPE_RESPONSE)
                    output.writeInt(message.requestId)
                    output.writeShort(message.tiles.size)
                    message.tiles.forEach { output.writeTile(it) }
                }
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= FabricClientPayloadChannels.MAX_PAYLOAD_BYTES) {
                "Distant LOD payload exceeds ${FabricClientPayloadChannels.MAX_PAYLOAD_BYTES} bytes"
            }
        }
    }

    fun decode(payload: ByteArray): DistantLodMessage {
        require(payload.size <= FabricClientPayloadChannels.MAX_PAYLOAD_BYTES) {
            "Distant LOD payload exceeds ${FabricClientPayloadChannels.MAX_PAYLOAD_BYTES} bytes"
        }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid distant LOD payload magic" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported distant LOD payload version" }
            val result = when (val type = input.readUnsignedByte()) {
                TYPE_HELLO -> {
                    val maximumTiles = input.readUnsignedShort()
                    val maximumRadius = input.readUnsignedShort()
                    require(maximumTiles in 1..MAXIMUM_TILES_PER_MESSAGE) {
                        "Distant LOD server tile limit is out of bounds: $maximumTiles"
                    }
                    require(maximumRadius in 1..MAXIMUM_RADIUS_CHUNKS) {
                        "Distant LOD server radius is out of bounds: $maximumRadius"
                    }
                    DistantLodMessage.Hello(maximumTiles, maximumRadius)
                }

                TYPE_REQUEST -> {
                    val requestId = input.readInt()
                    val count = input.readTileCount()
                    DistantLodMessage.Request(
                        requestId,
                        List(count) { ChunkPosition(input.readInt(), input.readInt()) },
                    )
                }

                TYPE_RESPONSE -> {
                    val requestId = input.readInt()
                    val count = input.readTileCount()
                    DistantLodMessage.Response(requestId, List(count) { input.readTile() })
                }

                else -> throw IllegalArgumentException("Unknown distant LOD payload type: $type")
            }
            require(input.read() == -1) { "Distant LOD payload has trailing data" }
            return result
        }
    }

    fun decodeNegotiated(payload: ByteArray): Any = if (DistantTerrainProtocolV2.isV2(payload)) {
        DistantTerrainProtocolV2.decode(payload)
    } else {
        decode(payload)
    }

    private fun DataInputStream.readTileCount(): Int {
        val count = readUnsignedShort()
        require(count in 1..MAXIMUM_TILES_PER_MESSAGE) {
            "Distant LOD payload tile count is out of bounds: $count"
        }
        return count
    }

    private fun DataOutputStream.writeTile(tile: DistantLodTile) {
        writeInt(tile.position.x)
        writeInt(tile.position.z)
        val palette = linkedMapOf<ResourceLocation, Int>()
        for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
            for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
                val column = tile[x, z]
                column.material?.let { palette.putIfAbsent(it, palette.size + 1) }
                column.solidMaterial?.let { palette.putIfAbsent(it, palette.size + 1) }
            }
        }
        require(palette.size <= MAXIMUM_PALETTE_SIZE)
        writeShort(palette.size)
        palette.keys.forEach { writeString(it.toString()) }
        for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
            for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
                val column = tile[x, z]
                writeInt(column.surfaceY)
                writeShort(column.material?.let(palette::getValue) ?: 0)
                writeInt(column.solidY)
                writeShort(column.solidMaterial?.let(palette::getValue) ?: 0)
            }
        }
    }

    private fun DataInputStream.readTile(): DistantLodTile {
        val position = ChunkPosition(readInt(), readInt())
        val paletteSize = readUnsignedShort()
        require(paletteSize <= MAXIMUM_PALETTE_SIZE) {
            "Distant LOD payload palette exceeds $MAXIMUM_PALETTE_SIZE entries"
        }
        val palette = arrayOfNulls<ResourceLocation>(paletteSize + 1)
        for (index in 1..paletteSize) palette[index] = ResourceLocation.of(readString())
        return DistantLodTile.capture(position) { _, _ ->
            val surfaceY = readInt()
            val material = palette[readPaletteIndex(paletteSize)]
            val solidY = readInt()
            val solidMaterial = palette[readPaletteIndex(paletteSize)]
            DistantLodColumn(surfaceY, material, solidY, solidMaterial)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAXIMUM_STRING_BYTES)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readUnsignedShort()
        require(length <= MAXIMUM_STRING_BYTES) {
            "Distant LOD material identifier exceeds $MAXIMUM_STRING_BYTES bytes"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataInputStream.readPaletteIndex(maximum: Int): Int {
        val index = readUnsignedShort()
        require(index <= maximum) { "Distant LOD palette index is out of bounds: $index" }
        return index
    }
}
