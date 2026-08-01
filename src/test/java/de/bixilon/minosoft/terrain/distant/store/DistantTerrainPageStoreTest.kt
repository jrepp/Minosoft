/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.store

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalSampler
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVoxelSample
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DistantTerrainPageStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `same named worlds with different persistence fingerprints have distinct identities`() {
        val first = distantTerrainWorldIdentity(
            "1.20.4",
            "server.example:25565",
            "minecraft:overworld",
            "seed-hash:11",
        )
        val second = distantTerrainWorldIdentity(
            "1.20.4",
            "server.example:25565",
            "minecraft:overworld",
            "seed-hash:12",
        )

        assertNotEquals(first, second)
        assertNotEquals(
            distantTerrainPersistenceIdentity("1.20.4", "server.example:25565", "minecraft:overworld", "seed-hash:11"),
            distantTerrainPersistenceIdentity("1.20.4", "server.example:25565", "minecraft:overworld", "seed-hash:12"),
        )
        assertEquals(
            "1.20.4\u0000server.example:25565\u0000minecraft:overworld",
            distantTerrainWorldIdentity("1.20.4", "server.example:25565", "minecraft:overworld", null),
        )
    }

    @Test
    fun `store writes only dirty page records and rekeys a restored epoch`() {
        val store = store()
        val first = page(-4, 7, epoch = 10, revision = 2)
        val second = page(8, -9, epoch = 10, revision = 3)
        store.write(first)
        store.write(second)
        val before = store.inspect()
        store.write(page(-4, 7, epoch = 10, revision = 4))
        val after = store.inspect()

        assertEquals(2, before.recordCount)
        assertEquals(2, after.recordCount)
        val restored = store.load(worldEpoch = 88).associateBy { it.key.x to it.key.z }
        assertEquals(88, restored.getValue(-4L to 7L).key.worldEpoch)
        assertEquals(4, restored.getValue(-4L to 7L).sourceRevision)
    }

    @Test
    fun `store rejects another identity corruption oversize and stale temporaries safely`() {
        val store = store()
        store.write(page(0, 0, 1, 1))
        Files.writeString(temporary.resolve("interrupted.pending"), "partial")
        assertEquals(1, store.inspect().ignoredTemporaryRecords)
        assertThrows<IllegalArgumentException> {
            DistantDirectoryTerrainStore(
                temporary,
                DistantTerrainStoreIdentity("another-server", "minecraft:overworld"),
                4,
            )
        }
        val record = Files.list(temporary).use { files -> files.filter { it.toString().endsWith(".page2") }.findFirst().orElseThrow() }
        val bytes = Files.readAllBytes(record).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        Files.write(record, bytes)
        assertThrows<IllegalArgumentException> { store.load(2) }
    }

    @Test
    fun `store bounds manifest and recovery directory input before decoding`() {
        val oversizedManifest = temporary.resolve("oversized-manifest")
        Files.createDirectories(oversizedManifest)
        Files.write(oversizedManifest.resolve("manifest.v2"), ByteArray(8_209))
        assertThrows<IllegalArgumentException> {
            DistantDirectoryTerrainStore(
                oversizedManifest,
                DistantTerrainStoreIdentity("test-server/world", "minecraft:overworld"),
                1,
            )
        }

        val excessRecords = temporary.resolve("excess-records")
        DistantDirectoryTerrainStore(
            excessRecords,
            DistantTerrainStoreIdentity("test-server/world", "minecraft:overworld"),
            1,
        ).close()
        repeat(3) { index ->
            Files.write(excessRecords.resolve("d0_x${index}_y0_z0.page2"), byteArrayOf())
        }
        assertThrows<IllegalArgumentException> {
            DistantDirectoryTerrainStore(
                excessRecords,
                DistantTerrainStoreIdentity("test-server/world", "minecraft:overworld"),
                1,
            )
        }
    }

    @Test
    fun `writer coalesces bounded dirty keys and drains durably on close`() {
        val store = store()
        val writer = DistantTerrainStoreWriter(store, maximumPendingPages = 3)
        assertTrue(writer.markDirty(page(1, 1, 1, 1)))
        assertTrue(writer.markDirty(page(1, 1, 1, 2)))
        assertTrue(writer.markDirty(page(2, 2, 1, 1)))
        writer.close()

        val restored = store().load(9).associateBy { it.key.x to it.key.z }
        assertEquals(2, restored.size)
        assertEquals(2, restored.getValue(1L to 1L).sourceRevision)
    }

    @Test
    fun `capacity eviction is distance aware and never removes a pinned dirty page`() {
        val store = store(maximumPages = 2)
        val near = page(0, 0, 1, 1)
        val far = page(100, 0, 1, 1)
        store.write(near)
        store.write(far)
        store.updateRetentionCenter(0, 0)
        store.pin(far.key)
        store.write(page(1, 0, 1, 1))

        var keys = store.load(2).map { it.key.x to it.key.z }.toSet()
        assertEquals(setOf(100L to 0L, 1L to 0L), keys)
        assertEquals(1, store.inspect().pinnedRecords)

        store.unpin(far.key)
        store.write(page(2, 0, 1, 1))
        keys = store.load(3).map { it.key.x to it.key.z }.toSet()
        assertEquals(setOf(1L to 0L, 2L to 0L), keys)
        assertEquals(2, store.inspect().evictionCount)
    }

    private fun store(maximumPages: Int = 4) = DistantDirectoryTerrainStore(
        temporary,
        DistantTerrainStoreIdentity("test-server/world", "minecraft:overworld"),
        maximumPages = maximumPages,
    )

    private fun page(x: Long, z: Long, epoch: Long, revision: Long): DistantVerticalPage =
        DistantVerticalSampler.capture(
            TerrainPageKey(TerrainDomain.DISTANT, 0, x, 0, z, epoch),
            1,
            0,
            2,
            revision,
            DistantSourceCompleteness.COMPLETE,
        ) { _, y, _ ->
            if (y == 0) DistantVoxelSample(TerrainSemanticMaterialId("minecraft:stone"), opaque = true)
            else DistantVoxelSample(null)
        }
}
