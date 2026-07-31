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

package de.bixilon.minosoft.terrain.distant.network

import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPageCodec
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.io.BoundedByteArrayOutputStream
import de.bixilon.minosoft.terrain.io.decodeStrictUtf8
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

data class DistantProtocolWorld(
    val connectionEpoch: Long,
    val worldEpoch: Long,
    val levelKey: String,
) {
    init {
        require(connectionEpoch >= 0L && worldEpoch >= 0L) { "Distant protocol epochs must not be negative" }
        require(NORMALIZED_KEY.matches(levelKey)) { "Distant protocol level key is not normalized" }
    }

    private companion object {
        val NORMALIZED_KEY = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}

data class DistantRequestedPage(
    val key: TerrainPageKey,
    val minimumSourceRevision: Long,
) {
    init {
        require(key.domain == TerrainDomain.DISTANT)
        require(key.y == 0L) { "Distant requested pages must cover full-height columns" }
        require(key.detailLevel <= DistantTerrainProtocolV2.MAXIMUM_DETAIL_LEVEL) {
            "Distant requested page detail level is unsupported"
        }
        require(minimumSourceRevision >= 0L)
    }
}

sealed interface DistantTerrainMessageV2 {
    val world: DistantProtocolWorld

    data class Hello(
        override val world: DistantProtocolWorld,
        val maximumPagesPerRequest: Int,
        val maximumRadiusChunks: Int,
        val maximumDetailLevel: Int,
    ) : DistantTerrainMessageV2 {
        init {
            require(maximumPagesPerRequest in 1..DistantTerrainProtocolV2.MAXIMUM_PAGES_PER_MESSAGE)
            require(maximumRadiusChunks in 1..DistantTerrainProtocolV2.MAXIMUM_RADIUS_CHUNKS)
            require(maximumDetailLevel in 0..DistantTerrainProtocolV2.MAXIMUM_DETAIL_LEVEL)
        }
    }

    class Request(
        override val world: DistantProtocolWorld,
        val requestId: Long,
        pages: Collection<DistantRequestedPage>,
    ) : DistantTerrainMessageV2 {
        val pages: List<DistantRequestedPage> = java.util.List.copyOf(pages)

        init {
            require(requestId >= 0L)
            require(pages.size in 1..DistantTerrainProtocolV2.MAXIMUM_PAGES_PER_MESSAGE)
            require(pages.map(DistantRequestedPage::key).toSet().size == pages.size) {
                "Duplicate distant request page"
            }
            require(pages.all { it.key.worldEpoch == world.worldEpoch }) {
                "Distant request page has wrong epoch"
            }
        }

        override fun equals(other: Any?): Boolean = other is Request &&
            world == other.world && requestId == other.requestId && pages == other.pages

        override fun hashCode(): Int = 31 * (31 * world.hashCode() + requestId.hashCode()) + pages.hashCode()

        override fun toString(): String = "Request(world=$world, requestId=$requestId, pages=$pages)"
    }

    class Response(
        override val world: DistantProtocolWorld,
        val requestId: Long,
        pages: Collection<DistantVerticalPage>,
    ) : DistantTerrainMessageV2 {
        val pages: List<DistantVerticalPage> = java.util.List.copyOf(pages)

        init {
            require(requestId >= 0L)
            require(pages.size in 1..DistantTerrainProtocolV2.MAXIMUM_PAGES_PER_MESSAGE)
            require(pages.map(DistantVerticalPage::key).toSet().size == pages.size) {
                "Duplicate distant response page"
            }
            require(pages.all { it.key.worldEpoch == world.worldEpoch }) {
                "Distant response page has wrong epoch"
            }
        }

        override fun equals(other: Any?): Boolean = other is Response &&
            world == other.world && requestId == other.requestId && pages == other.pages

        override fun hashCode(): Int = 31 * (31 * world.hashCode() + requestId.hashCode()) + pages.hashCode()

        override fun toString(): String = "Response(world=$world, requestId=$requestId, pages=$pages)"
    }

    data class Cancel(
        override val world: DistantProtocolWorld,
        val requestId: Long,
    ) : DistantTerrainMessageV2 {
        init {
            require(requestId >= 0L)
        }
    }
}

object DistantTerrainProtocolV2 {
    const val VERSION = 2
    const val DATA_SCHEMA_VERSION = DistantVerticalPageCodec.SCHEMA_VERSION
    const val MAXIMUM_PAGES_PER_MESSAGE = 32
    const val MAXIMUM_RADIUS_CHUNKS = 512
    const val MAXIMUM_DETAIL_LEVEL = 30
    const val MAXIMUM_PAYLOAD_BYTES = 1024 * 1024
    private const val MAGIC = 0x4D44484E
    private const val TYPE_HELLO = 0
    private const val TYPE_REQUEST = 1
    private const val TYPE_RESPONSE = 2
    private const val TYPE_CANCEL = 3
    private const val MAXIMUM_LEVEL_KEY_BYTES = 512

    fun isV2(payload: ByteArray): Boolean = payload.size >= 5 &&
        payload[0] == (MAGIC ushr 24).toByte() && payload[1] == (MAGIC ushr 16).toByte() &&
        payload[2] == (MAGIC ushr 8).toByte() && payload[3] == MAGIC.toByte() &&
        payload[4].toInt() and 0xFF == VERSION

    fun encode(message: DistantTerrainMessageV2): ByteArray {
        val bytes = BoundedByteArrayOutputStream(MAXIMUM_PAYLOAD_BYTES)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.writeShort(DATA_SCHEMA_VERSION)
            output.writeWorld(message.world)
            when (message) {
                is DistantTerrainMessageV2.Hello -> {
                    require(message.maximumPagesPerRequest in 1..MAXIMUM_PAGES_PER_MESSAGE)
                    require(message.maximumRadiusChunks in 1..MAXIMUM_RADIUS_CHUNKS)
                    require(message.maximumDetailLevel in 0..MAXIMUM_DETAIL_LEVEL)
                    output.writeByte(TYPE_HELLO)
                    output.writeShort(message.maximumPagesPerRequest)
                    output.writeShort(message.maximumRadiusChunks)
                    output.writeByte(message.maximumDetailLevel)
                }
                is DistantTerrainMessageV2.Request -> {
                    require(message.requestId >= 0L)
                    require(message.pages.size in 1..MAXIMUM_PAGES_PER_MESSAGE)
                    require(message.pages.map { it.key }.toSet().size == message.pages.size) { "Duplicate distant request page" }
                    output.writeByte(TYPE_REQUEST)
                    output.writeLong(message.requestId)
                    output.writeByte(message.pages.size)
                    message.pages.forEach { output.writeRequestedPage(message.world, it) }
                }
                is DistantTerrainMessageV2.Response -> {
                    require(message.requestId >= 0L)
                    require(message.pages.size in 1..MAXIMUM_PAGES_PER_MESSAGE)
                    require(message.pages.map { it.key }.toSet().size == message.pages.size) { "Duplicate distant response page" }
                    output.writeByte(TYPE_RESPONSE)
                    output.writeLong(message.requestId)
                    output.writeByte(message.pages.size)
                    message.pages.forEach { page ->
                        require(page.key.worldEpoch == message.world.worldEpoch) { "Distant response page has wrong epoch" }
                        val encoded = DistantVerticalPageCodec.encode(page)
                        output.writeInt(encoded.size)
                        output.write(encoded)
                    }
                }
                is DistantTerrainMessageV2.Cancel -> {
                    require(message.requestId >= 0L)
                    output.writeByte(TYPE_CANCEL)
                    output.writeLong(message.requestId)
                }
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= MAXIMUM_PAYLOAD_BYTES) { "Distant v2 payload exceeds $MAXIMUM_PAYLOAD_BYTES bytes" }
        }
    }

    fun decode(payload: ByteArray): DistantTerrainMessageV2 {
        require(payload.size <= MAXIMUM_PAYLOAD_BYTES) { "Distant v2 payload exceeds $MAXIMUM_PAYLOAD_BYTES bytes" }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid distant v2 magic" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported distant protocol version" }
            require(input.readUnsignedShort() == DATA_SCHEMA_VERSION) { "Unsupported distant data schema" }
            val world = input.readWorld()
            val message = when (val type = input.readUnsignedByte()) {
                TYPE_HELLO -> DistantTerrainMessageV2.Hello(
                    world,
                    input.readUnsignedShort().also { require(it in 1..MAXIMUM_PAGES_PER_MESSAGE) },
                    input.readUnsignedShort().also { require(it in 1..MAXIMUM_RADIUS_CHUNKS) },
                    input.readUnsignedByte().also { require(it in 0..MAXIMUM_DETAIL_LEVEL) },
                )
                TYPE_REQUEST -> {
                    val requestId = input.readLong().also { require(it >= 0L) }
                    val count = input.readCount()
                    DistantTerrainMessageV2.Request(world, requestId, List(count) { input.readRequestedPage(world) })
                        .also { require(it.pages.map(DistantRequestedPage::key).toSet().size == count) }
                }
                TYPE_RESPONSE -> {
                    val requestId = input.readLong().also { require(it >= 0L) }
                    val count = input.readCount()
                    val pages = List(count) {
                        val length = input.readInt()
                        require(length in 1..minOf(DistantVerticalPageCodec.MAXIMUM_ENCODED_BYTES, input.available()))
                        DistantVerticalPageCodec.decode(ByteArray(length).also(input::readFully)).also { page ->
                            require(page.key.worldEpoch == world.worldEpoch) { "Distant response page has wrong epoch" }
                        }
                    }
                    require(pages.map(DistantVerticalPage::key).toSet().size == count)
                    DistantTerrainMessageV2.Response(world, requestId, pages)
                }
                TYPE_CANCEL -> DistantTerrainMessageV2.Cancel(world, input.readLong().also { require(it >= 0L) })
                else -> throw IllegalArgumentException("Unknown distant v2 message type: $type")
            }
            require(input.read() == -1) { "Distant v2 payload has trailing data" }
            return message
        }
    }

    private fun DataOutputStream.writeWorld(world: DistantProtocolWorld) {
        writeLong(world.connectionEpoch)
        writeLong(world.worldEpoch)
        writeString(world.levelKey)
    }

    private fun DataInputStream.readWorld() = DistantProtocolWorld(readLong(), readLong(), readString())

    private fun DataOutputStream.writeRequestedPage(world: DistantProtocolWorld, page: DistantRequestedPage) {
        require(page.key.worldEpoch == world.worldEpoch) { "Distant request page has wrong epoch" }
        require(page.key.detailLevel in 0..MAXIMUM_DETAIL_LEVEL) { "Distant request detail level is unsupported" }
        writeByte(page.key.detailLevel)
        writeLong(page.key.x)
        writeLong(page.key.y)
        writeLong(page.key.z)
        writeLong(page.minimumSourceRevision)
    }

    private fun DataInputStream.readRequestedPage(world: DistantProtocolWorld): DistantRequestedPage = DistantRequestedPage(
        TerrainPageKey(TerrainDomain.DISTANT, readUnsignedByte(), readLong(), readLong(), readLong(), world.worldEpoch),
        readLong(),
    )

    private fun DataInputStream.readCount(): Int = readUnsignedByte().also {
        require(it in 1..MAXIMUM_PAGES_PER_MESSAGE) { "Distant v2 page count is out of bounds: $it" }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAXIMUM_LEVEL_KEY_BYTES)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readUnsignedShort()
        require(length <= MAXIMUM_LEVEL_KEY_BYTES)
        return decodeStrictUtf8(ByteArray(length).also(::readFully))
    }
}
