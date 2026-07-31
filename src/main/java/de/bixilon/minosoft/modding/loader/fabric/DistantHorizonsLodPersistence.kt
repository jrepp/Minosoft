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

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Bounded read-only decoder for the legacy detached-LOD database.
 *
 * The file contains no live world objects or registry IDs. Materials remain
 * resource identifiers so a later session can safely re-resolve them.
 */
internal class DistantLodPersistence(
    val path: Path,
    private val maximumTiles: Int,
    private val maximumUncompressedBytes: Long = MAXIMUM_UNCOMPRESSED_BYTES,
) {
    init {
        require(maximumTiles in 1..MAXIMUM_TILE_LIMIT) {
            "Distant LOD persistence limit must be in 1..$MAXIMUM_TILE_LIMIT"
        }
        require(maximumUncompressedBytes > 0L) {
            "Distant LOD uncompressed byte limit must be positive"
        }
    }

    fun load(): List<DistantLodTile> {
        if (!Files.isRegularFile(path)) return emptyList()
        require(Files.size(path) <= MAXIMUM_COMPRESSED_BYTES) {
            "Distant LOD database exceeds $MAXIMUM_COMPRESSED_BYTES bytes: $path"
        }
        val decompressed = GZIPInputStream(Files.newInputStream(path))
        val bounded = BoundedInputStream(decompressed, maximumUncompressedBytes)
        DataInputStream(BufferedInputStream(bounded)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid distant LOD database magic: $path" }
            require(input.readUnsignedShort() == VERSION) { "Unsupported distant LOD database version: $path" }
            val tileCount = input.readInt()
            require(tileCount in 0..maximumTiles) {
                "Distant LOD database tile count is out of bounds: $tileCount"
            }
            val tiles = ArrayList<DistantLodTile>(tileCount)
            repeat(tileCount) { tiles += readTile(input) }
            require(input.read() == -1) { "Distant LOD database has trailing data: $path" }
            return tiles
        }
    }

    private fun readTile(input: DataInputStream): DistantLodTile {
        val position = ChunkPosition(input.readInt(), input.readInt())
        val paletteSize = input.readUnsignedShort()
        require(paletteSize <= MAXIMUM_PALETTE_SIZE) {
            "Distant LOD tile palette exceeds $MAXIMUM_PALETTE_SIZE entries: $paletteSize"
        }
        val palette = arrayOfNulls<ResourceLocation>(paletteSize + 1)
        for (index in 1..paletteSize) {
            palette[index] = ResourceLocation.of(input.readBoundedString())
        }
        return DistantLodTile.capture(position) { _, _ ->
            val surfaceY = input.readInt()
            val material = palette[input.readPaletteIndex(paletteSize)]
            val solidY = input.readInt()
            val solidMaterial = palette[input.readPaletteIndex(paletteSize)]
            DistantLodColumn(surfaceY, material, solidY, solidMaterial)
        }
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readUnsignedShort()
        require(length <= MAXIMUM_STRING_BYTES) {
            "Distant LOD material identifier exceeds $MAXIMUM_STRING_BYTES bytes: $length"
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

    companion object {
        private const val MAGIC = 0x4D44484C
        private const val VERSION = 1
        private const val MAXIMUM_PALETTE_SIZE = 1_024
        private const val MAXIMUM_STRING_BYTES = 512
        private const val MAXIMUM_TILE_LIMIT = 65_536
        private const val MAXIMUM_COMPRESSED_BYTES = 512L * 1024L * 1024L
        private const val MAXIMUM_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L

        fun path(root: Path, session: PlaySession): Path {
            val identity = buildString {
                append(session.version.name)
                append('\u0000')
                append(session.connection.identifier)
                append('\u0000')
                append(session.world.name ?: "unknown")
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return root.resolve("$digest.lod.gz")
        }
    }
}

private class BoundedInputStream(
    input: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) consume(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = (maximumBytes - consumed).coerceAtLeast(0L)
        val remainingWithProbe = if (remaining == Long.MAX_VALUE) remaining else remaining + 1L
        val allowed = minOf(length.toLong(), remainingWithProbe, Int.MAX_VALUE.toLong()).toInt()
        val count = super.read(buffer, offset, allowed)
        if (count > 0) consume(count.toLong())
        return count
    }

    override fun skip(count: Long): Long {
        if (count <= 0L) return 0L
        val remaining = (maximumBytes - consumed).coerceAtLeast(0L)
        val remainingWithProbe = if (remaining == Long.MAX_VALUE) remaining else remaining + 1L
        val skipped = super.skip(minOf(count, remainingWithProbe))
        if (skipped > 0L) consume(skipped)
        return skipped
    }

    private fun consume(count: Long) {
        consumed += count
        if (consumed > maximumBytes) {
            throw IOException("Distant LOD database exceeds $maximumBytes uncompressed bytes")
        }
    }
}
