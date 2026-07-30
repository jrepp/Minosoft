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
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Versioned, bounded, atomic detached-LOD database.
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

    fun save(tiles: List<DistantLodTile>) {
        require(tiles.size <= maximumTiles) {
            "Distant LOD database contains ${tiles.size} tiles; maximum is $maximumTiles"
        }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temporary)))).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(VERSION)
                output.writeInt(tiles.size)
                tiles.forEach { writeTile(output, it) }
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeTile(output: DataOutputStream, tile: DistantLodTile) {
        output.writeInt(tile.position.x)
        output.writeInt(tile.position.z)
        val palette = linkedMapOf<ResourceLocation, Int>()
        for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
            for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
                val column = tile[x, z]
                column.material?.let { palette.putIfAbsent(it, palette.size + 1) }
                column.solidMaterial?.let { palette.putIfAbsent(it, palette.size + 1) }
            }
        }
        require(palette.size <= MAXIMUM_PALETTE_SIZE) {
            "Distant LOD tile palette exceeds $MAXIMUM_PALETTE_SIZE entries"
        }
        output.writeShort(palette.size)
        palette.keys.forEach { output.writeBoundedString(it.toString()) }
        for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
            for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
                val column = tile[x, z]
                output.writeInt(column.surfaceY)
                output.writeShort(column.material?.let(palette::getValue) ?: 0)
                output.writeInt(column.solidY)
                output.writeShort(column.solidMaterial?.let(palette::getValue) ?: 0)
            }
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

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAXIMUM_STRING_BYTES) {
            "Distant LOD material identifier exceeds $MAXIMUM_STRING_BYTES bytes"
        }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readUnsignedShort()
        require(length <= MAXIMUM_STRING_BYTES) {
            "Distant LOD material identifier exceeds $MAXIMUM_STRING_BYTES bytes: $length"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
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

/**
 * Coalesces hot chunk updates into a single immutable database snapshot and
 * keeps compression/file I/O off simulation and rendering threads.
 */
internal class DistantLodPersistenceWriter(
    private val persistence: DistantLodPersistence,
    private val delayMillis: Long = DEFAULT_DELAY_MILLIS,
) : AutoCloseable {
    init {
        require(delayMillis >= 0L) { "Distant LOD persistence delay must not be negative" }
    }

    private val threadNumber = THREAD_NUMBER.incrementAndGet()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DistantLodPersistence-$threadNumber").apply { isDaemon = true }
    }
    private var pending: List<DistantLodTile>? = null
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false

    @Synchronized
    fun markDirty(tiles: List<DistantLodTile>) {
        if (closed) return
        pending = tiles.toList()
        if (scheduled != null) return
        scheduled = executor.schedule(::flushScheduled, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun flushScheduled() {
        val snapshot = synchronized(this) {
            scheduled = null
            pending.also { pending = null }
        } ?: return
        save(snapshot)
        synchronized(this) {
            if (!closed && pending != null && scheduled == null) {
                scheduled = executor.schedule(::flushScheduled, delayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun save(tiles: List<DistantLodTile>) {
        try {
            persistence.save(tiles)
        } catch (error: Throwable) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
        }
    }

    override fun close() {
        val finalSnapshot = synchronized(this) {
            if (closed) return
            closed = true
            scheduled?.cancel(false)
            scheduled = null
            pending.also { pending = null }
        }
        if (finalSnapshot != null) executor.execute { save(finalSnapshot) }
        executor.shutdown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_TIMEOUT_SECONDS)
        var interruption: InterruptedException? = null
        while (!executor.isTerminated) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                executor.shutdownNow()
                break
            }
            try {
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow()
                }
            } catch (error: InterruptedException) {
                if (interruption == null) {
                    interruption = error
                } else {
                    interruption.addSuppressed(error)
                }
                executor.shutdownNow()
            }
        }
        if (interruption != null) {
            Thread.currentThread().interrupt()
            throw interruption
        }
    }

    private companion object {
        const val DEFAULT_DELAY_MILLIS = 2_000L
        const val CLOSE_TIMEOUT_SECONDS = 10L
        val THREAD_NUMBER = AtomicInteger()
    }
}
