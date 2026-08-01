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

package de.bixilon.minosoft.terrain.distant.store

import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPageCodec
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun distantTerrainWorldIdentity(
    versionName: String,
    connectionIdentifier: String,
    normalizedLevelKey: String,
    persistenceFingerprint: String?,
): String {
    require(versionName.isNotBlank()) { "Distant world version must not be blank" }
    require(connectionIdentifier.isNotBlank()) { "Distant world connection must not be blank" }
    require(normalizedLevelKey.isNotBlank()) { "Distant world level key must not be blank" }
    require(persistenceFingerprint == null || persistenceFingerprint.isNotBlank()) {
        "Distant world persistence fingerprint must not be blank"
    }
    return buildString {
        append(versionName)
        append('\u0000')
        append(connectionIdentifier)
        append('\u0000')
        append(normalizedLevelKey)
        if (persistenceFingerprint != null) {
            append('\u0000')
            append(persistenceFingerprint)
        }
    }
}

fun distantTerrainPersistenceIdentity(
    versionName: String,
    connectionIdentifier: String,
    normalizedLevelKey: String,
    persistenceFingerprint: String?,
): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(
        distantTerrainWorldIdentity(
            versionName,
            connectionIdentifier,
            normalizedLevelKey,
            persistenceFingerprint,
        ).toByteArray(StandardCharsets.UTF_8),
    ),
)

data class DistantTerrainStoreIdentity(
    val worldIdentity: String,
    val normalizedLevelKey: String,
) {
    init {
        require(worldIdentity.isNotBlank()) { "Distant store world identity must not be blank" }
        require(NORMALIZED_KEY.matches(normalizedLevelKey)) { "Distant store level key is not normalized" }
    }

    private companion object {
        val NORMALIZED_KEY = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}

data class DistantTerrainStoreInspection(
    val schemaVersion: Int,
    val recordCount: Int,
    val totalBytes: Long,
    val ignoredTemporaryRecords: Int,
    val pinnedRecords: Int = 0,
    val evictionCount: Long = 0,
)

interface DistantTerrainStore : AutoCloseable {
    fun load(worldEpoch: Long): List<DistantVerticalPage>
    fun write(page: DistantVerticalPage)
    fun delete(key: TerrainPageKey): Boolean
    fun updateRetentionCenter(x: Long, z: Long) = Unit
    fun pin(key: TerrainPageKey) = Unit
    fun unpin(key: TerrainPageKey) = Unit
    fun inspect(): DistantTerrainStoreInspection
}

/**
 * Page-oriented atomic store. Every dirty page replaces one checksum-protected
 * record; an interrupted temporary record is ignored during recovery.
 */
class DistantDirectoryTerrainStore(
    private val directory: Path,
    private val identity: DistantTerrainStoreIdentity,
    private val maximumPages: Int,
) : DistantTerrainStore {
    private data class RecordMetadata(val key: TerrainPageKey, var lastAccessMillis: Long)

    private val records = linkedMapOf<String, RecordMetadata>()
    private val pinned = linkedSetOf<TerrainPageKey>()
    private var retentionX = 0L
    private var retentionZ = 0L
    private var evictions = 0L

    init {
        require(maximumPages > 0) { "Distant store page limit must be positive" }
        initializeManifest()
        for (path in pageFiles()) {
            val key = parseFileName(path.fileName.toString()) ?: continue
            records[path.fileName.toString()] = RecordMetadata(key, Files.getLastModifiedTime(path).toMillis())
        }
        while (records.size > maximumPages) evict(requireNotNull(selectVictim()))
    }

    @Synchronized
    override fun load(worldEpoch: Long): List<DistantVerticalPage> {
        require(worldEpoch >= 0L) { "Distant store target epoch must not be negative" }
        val files = pageFiles()
        require(files.size <= maximumPages) { "Distant store exceeds $maximumPages pages" }
        return files.map { path ->
            records[path.fileName.toString()]?.lastAccessMillis = System.currentTimeMillis()
            readRecord(path).withWorldEpoch(worldEpoch)
        }
    }

    @Synchronized
    override fun write(page: DistantVerticalPage) {
        val target = directory.resolve(fileName(page.key))
        val payload = DistantVerticalPageCodec.encode(page)
        val record = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(RECORD_MAGIC)
                output.writeShort(SCHEMA_VERSION)
                output.writeUTF(identityDigest())
                output.writeInt(payload.size)
                output.write(MessageDigest.getInstance("SHA-256").digest(payload))
                output.write(payload)
            }
        }.toByteArray()
        val victim = if (!Files.exists(target) && records.size >= maximumPages) {
            selectVictim() ?: throw IllegalStateException("Distant store capacity is pinned by dirty pages")
        } else null
        atomicWrite(target, record)
        records[target.fileName.toString()] = RecordMetadata(page.key.copy(worldEpoch = 0L), System.currentTimeMillis())
        if (victim != null) evict(victim)
    }

    @Synchronized
    override fun delete(key: TerrainPageKey): Boolean {
        if (key.copy(worldEpoch = 0L) in pinned) return false
        records.remove(fileName(key))
        return Files.deleteIfExists(directory.resolve(fileName(key)))
    }

    @Synchronized
    override fun updateRetentionCenter(x: Long, z: Long) {
        retentionX = x
        retentionZ = z
    }

    @Synchronized
    override fun pin(key: TerrainPageKey) {
        pinned += key.copy(worldEpoch = 0L)
    }

    @Synchronized
    override fun unpin(key: TerrainPageKey) {
        pinned -= key.copy(worldEpoch = 0L)
    }

    @Synchronized
    override fun inspect(): DistantTerrainStoreInspection {
        val files = pageFiles()
        val temporary = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(TEMPORARY_SUFFIX) }.count().toInt()
        }
        return DistantTerrainStoreInspection(
            SCHEMA_VERSION,
            files.size,
            files.sumOf(Files::size),
            temporary,
            pinned.size,
            evictions,
        )
    }

    override fun close() = Unit

    private fun readRecord(path: Path): DistantVerticalPage {
        require(Files.size(path) <= MAXIMUM_RECORD_BYTES) { "Distant page record is oversized: $path" }
        DataInputStream(Files.newInputStream(path)).use { input ->
            require(input.readInt() == RECORD_MAGIC) { "Invalid distant page record: $path" }
            require(input.readUnsignedShort() == SCHEMA_VERSION) { "Unsupported distant page record schema: $path" }
            require(input.readUTF() == identityDigest()) { "Distant page record belongs to another world: $path" }
            val length = input.readInt()
            require(length in 1..DistantVerticalPageCodec.MAXIMUM_ENCODED_BYTES) { "Invalid distant page length: $length" }
            val expectedDigest = ByteArray(32).also(input::readFully)
            val payload = ByteArray(length).also(input::readFully)
            require(input.read() == -1) { "Distant page record has trailing data: $path" }
            require(MessageDigest.isEqual(expectedDigest, MessageDigest.getInstance("SHA-256").digest(payload))) {
                "Distant page checksum mismatch: $path"
            }
            return DistantVerticalPageCodec.decode(payload)
        }
    }

    private fun initializeManifest() {
        Files.createDirectories(directory)
        val path = directory.resolve(MANIFEST_FILE)
        val expected = manifestBytes()
        if (Files.exists(path)) {
            require(Files.size(path) <= MAXIMUM_MANIFEST_BYTES) {
                "Distant store manifest is oversized: $path"
            }
            require(Files.readAllBytes(path).contentEquals(expected)) { "Distant store identity/schema mismatch: $path" }
        } else {
            atomicWrite(path, expected)
        }
    }

    private fun manifestBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MANIFEST_MAGIC)
            output.writeShort(SCHEMA_VERSION)
            output.writeBounded(identity.worldIdentity)
            output.writeBounded(identity.normalizedLevelKey)
        }
    }.toByteArray()

    private fun identityDigest(): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(manifestBytes()),
    )

    private fun pageFiles(): List<Path> {
        val scanLimit = Math.addExact(maximumPages.toLong(), MAXIMUM_RECOVERY_SURPLUS + 1L)
        val files = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(RECORD_SUFFIX) }
                .limit(scanLimit)
                .toList()
        }
        require(files.size.toLong() < scanLimit) {
            "Distant store exceeds the bounded recovery surplus"
        }
        return files.sorted()
    }

    private fun DistantVerticalPage.withWorldEpoch(epoch: Long) = DistantVerticalPage(
        key.copy(worldEpoch = epoch), width, originY, sourceRevision, completeness, columns,
    )

    private fun fileName(key: TerrainPageKey): String =
        "d${key.detailLevel}_x${key.x}_y${key.y}_z${key.z}$RECORD_SUFFIX"

    private fun parseFileName(name: String): TerrainPageKey? {
        val match = RECORD_PATTERN.matchEntire(name) ?: return null
        return TerrainPageKey(
            de.bixilon.minosoft.terrain.model.identity.TerrainDomain.DISTANT,
            match.groupValues[1].toInt(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
            match.groupValues[4].toLong(),
            0L,
        )
    }

    private fun distanceSquared(key: TerrainPageKey): java.math.BigInteger {
        val dx = java.math.BigInteger.valueOf(key.x).subtract(java.math.BigInteger.valueOf(retentionX))
        val dz = java.math.BigInteger.valueOf(key.z).subtract(java.math.BigInteger.valueOf(retentionZ))
        return dx.multiply(dx).add(dz.multiply(dz))
    }

    private fun selectVictim(): RecordMetadata? = records.values.asSequence()
        .filter { metadata -> metadata.key !in pinned }
        .maxWithOrNull(
            compareBy<RecordMetadata> { distanceSquared(it.key) }
                .thenBy { -it.lastAccessMillis },
        )

    private fun evict(victim: RecordMetadata) {
        Files.deleteIfExists(directory.resolve(fileName(victim.key)))
        records.remove(fileName(victim.key))
        evictions = Math.addExact(evictions, 1L)
    }

    private fun atomicWrite(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(directory, target.fileName.toString(), TEMPORARY_SUFFIX)
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun DataOutputStream.writeBounded(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAXIMUM_IDENTITY_BYTES) { "Distant store identity is too long" }
        writeShort(bytes.size)
        write(bytes)
    }

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MANIFEST_MAGIC = 0x4453544D
        const val RECORD_MAGIC = 0x44535450
        const val MANIFEST_FILE = "manifest.v2"
        const val RECORD_SUFFIX = ".page2"
        const val TEMPORARY_SUFFIX = ".pending"
        const val MAXIMUM_IDENTITY_BYTES = 4_096
        const val MAXIMUM_MANIFEST_BYTES = MAXIMUM_IDENTITY_BYTES * 2L + 16L
        const val MAXIMUM_RECORD_BYTES = DistantVerticalPageCodec.MAXIMUM_ENCODED_BYTES + 4_096L
        const val MAXIMUM_RECOVERY_SURPLUS = 1L
        val RECORD_PATTERN = Regex("d(\\d+)_x(-?\\d+)_y(-?\\d+)_z(-?\\d+)\\.page2")
    }
}

/** Bounded dirty-key coalescer; pages remain pending until a durable record replaces them. */
class DistantTerrainStoreWriter(
    private val store: DistantTerrainStore,
    private val maximumPendingPages: Int = 256,
) : AutoCloseable {
    init {
        require(maximumPendingPages > 0) { "Distant writer queue limit must be positive" }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DistantTerrainStore-${THREAD_NUMBER.incrementAndGet()}").apply { isDaemon = true }
    }
    private val pending = linkedMapOf<TerrainPageKey, DistantVerticalPage>()
    private var draining = false
    private var closed = false
    private var failure: Throwable? = null

    @Synchronized
    fun markDirty(page: DistantVerticalPage): Boolean {
        if (closed || failure != null) return false
        if (page.key !in pending && pending.size >= maximumPendingPages) return false
        if (page.key !in pending) store.pin(page.key)
        pending[page.key] = page
        if (!draining) {
            draining = true
            executor.execute(::drain)
        }
        return true
    }

    @Synchronized
    fun pendingKeys(): Set<TerrainPageKey> = pending.keys.toSet()

    private fun drain() {
        while (true) {
            val page = synchronized(this) {
                val next = pending.entries.firstOrNull()
                if (next == null) {
                    draining = false
                    return
                }
                next.value
            }
            try {
                store.write(page)
            } catch (error: Throwable) {
                synchronized(this) {
                    failure = error
                    draining = false
                }
                return
            }
            synchronized(this) {
                if (pending[page.key] === page) {
                    pending.remove(page.key)
                    store.unpin(page.key)
                }
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        executor.shutdown()
        var closeFailure: Throwable? = null
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                closeFailure = IllegalStateException("Distant store writer did not stop")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            closeFailure = error
        }
        synchronized(this) {
            failure?.let { error ->
                closeFailure = accumulate(closeFailure, IllegalStateException("Distant store writer failed", error))
            }
            if (pending.isNotEmpty()) {
                closeFailure = accumulate(
                    closeFailure,
                    IllegalStateException("Distant store writer closed with ${pending.size} dirty pages"),
                )
            }
        }
        try {
            store.close()
        } catch (error: Throwable) {
            closeFailure = accumulate(closeFailure, error)
        }
        if (closeFailure != null) throw closeFailure
    }

    private fun accumulate(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        current.addSuppressed(next)
        return current
    }

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
        val THREAD_NUMBER = AtomicInteger()
    }
}
