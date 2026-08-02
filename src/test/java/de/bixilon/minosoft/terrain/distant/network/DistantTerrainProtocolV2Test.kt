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

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalSampler
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVoxelSample
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DistantTerrainProtocolV2Test {
    private val world = DistantProtocolWorld(7, 11, "minecraft:overworld")

    @Test
    fun `v2 messages round trip world request identity detail and page data`() {
        val request = DistantTerrainMessageV2.Request(world, 31, listOf(DistantRequestedPage(key(-2, 4), 8)))
        val response = DistantTerrainMessageV2.Response(world, 31, listOf(page(-2, 4, 9)))
        val hello = DistantTerrainMessageV2.Hello(world, 12, 200, 4)
        val cancel = DistantTerrainMessageV2.Cancel(world, 31)

        val fixture = checkNotNull(javaClass.getResource("distant-terrain-protocol-v2.hex"))
            .readText().lineSequence().filter(String::isNotBlank).associate { line ->
                line.substringBefore('=') to line.substringAfter('=')
            }
        for (message in listOf(hello, request, response, cancel)) {
            val encoded = DistantTerrainProtocolV2.encode(message)
            assertEquals(fixture.getValue(message::class.simpleName!!), encoded.toHexString())
            assertTrue(DistantTerrainProtocolV2.isV2(encoded))
            val decoded = DistantTerrainProtocolV2.decode(encoded)
            if (message is DistantTerrainMessageV2.Response) {
                decoded as DistantTerrainMessageV2.Response
                assertEquals(message.world, decoded.world)
                assertEquals(message.requestId, decoded.requestId)
                assertEquals(message.pages.single().semanticDigest, decoded.pages.single().semanticDigest)
            } else {
                assertEquals(message, decoded)
            }
        }
    }

    @Test
    fun `v2 codec rejects wrong epoch duplicate old schema oversize and trailing data`() {
        assertThrows<IllegalArgumentException> {
            DistantTerrainProtocolV2.encode(DistantTerrainMessageV2.Request(world, 1, listOf(DistantRequestedPage(key(0, 0).copy(worldEpoch = 9), 0))))
        }
        assertThrows<IllegalArgumentException> {
            val entry = DistantRequestedPage(key(0, 0), 0)
            DistantTerrainProtocolV2.encode(DistantTerrainMessageV2.Request(world, 1, listOf(entry, entry)))
        }
        val valid = DistantTerrainProtocolV2.encode(DistantTerrainMessageV2.Cancel(world, 2))
        assertThrows<IllegalArgumentException> { DistantTerrainProtocolV2.decode(valid + 0) }
        val oldSchema = valid.copyOf().also { it[6] = 1 }
        assertThrows<IllegalArgumentException> { DistantTerrainProtocolV2.decode(oldSchema) }
        assertThrows<IllegalArgumentException> {
            DistantTerrainProtocolV2.decode(ByteArray(DistantTerrainProtocolV2.MAXIMUM_PAYLOAD_BYTES + 1))
        }
        val malformedLevelKey = valid.copyOf().also { it[25] = 0xC3.toByte() }
        assertThrows<IllegalArgumentException> { DistantTerrainProtocolV2.decode(malformedLevelKey) }
    }

    @Test
    fun `tracker rejects invalid responses and consumes pages superseded by newer local data`() {
        val local = mutableMapOf<TerrainPageKey, Long>()
        val tracker = DistantTerrainRequestTracker(world, 2, 2, local::get)
        val requested = listOf(DistantRequestedPage(key(0, 0), 3), DistantRequestedPage(key(1, 0), 5))
        assertTrue(tracker.register(DistantTerrainMessageV2.Request(world, 50, requested)))

        assertRejected(
            tracker.admit(
                DistantTerrainMessageV2.Response(
                    world.copy(worldEpoch = 12),
                    50,
                    listOf(page(0, 0, 4, worldEpoch = 12)),
                ),
            ),
            DistantResponseRejection.WRONG_WORLD,
        )
        assertRejected(tracker.admit(DistantTerrainMessageV2.Response(world, 99, listOf(page(0, 0, 4)))), DistantResponseRejection.UNKNOWN_REQUEST)
        assertRejected(tracker.admit(DistantTerrainMessageV2.Response(world, 50, listOf(page(9, 9, 8)))), DistantResponseRejection.UNREQUESTED_PAGE)
        val supersededMinimum = assertIs<DistantResponseAdmission.Superseded>(
            tracker.admit(DistantTerrainMessageV2.Response(world, 50, listOf(page(1, 0, 4)))),
        )
        assertEquals(false, supersededMinimum.requestComplete)

        val first = assertIs<DistantResponseAdmission.Accepted>(tracker.admit(DistantTerrainMessageV2.Response(world, 50, listOf(page(0, 0, 4)))))
        assertTrue(first.requestComplete)
        assertTrue(tracker.outstandingRequestIds().isEmpty())

        val localKey = key(2, 0)
        local[localKey] = 7
        assertTrue(tracker.register(DistantTerrainMessageV2.Request(world, 51, listOf(DistantRequestedPage(localKey, 0)))))
        val supersededLocal = assertIs<DistantResponseAdmission.Superseded>(
            tracker.admit(DistantTerrainMessageV2.Response(world, 51, listOf(page(2, 0, 6)))),
        )
        assertTrue(supersededLocal.requestComplete)

        val freshKey = key(3, 0)
        val staleKey = key(4, 0)
        local[staleKey] = 9
        assertTrue(tracker.register(DistantTerrainMessageV2.Request(
            world,
            52,
            listOf(DistantRequestedPage(freshKey, 0), DistantRequestedPage(staleKey, 0)),
        )))
        val mixed = assertIs<DistantResponseAdmission.PartiallyAccepted>(
            tracker.admit(DistantTerrainMessageV2.Response(world, 52, listOf(page(3, 0, 4), page(4, 0, 8)))),
        )
        assertEquals(mixed.pages.map(DistantVerticalPage::key), listOf(freshKey))
        assertEquals(mixed.supersededKeys, listOf(staleKey))
        assertTrue(mixed.requestComplete)
        assertTrue(tracker.outstandingRequestIds().isEmpty())
    }

    @Test
    fun `world replacement cancels every outstanding request`() {
        val tracker = DistantTerrainRequestTracker(world, 3, 2)
        tracker.register(DistantTerrainMessageV2.Request(world, 1, listOf(DistantRequestedPage(key(0, 0), 0))))
        tracker.register(DistantTerrainMessageV2.Request(world, 2, listOf(DistantRequestedPage(key(1, 0), 0))))
        assertTrue(tracker.cancel(1))
        assertEquals(setOf(2L), tracker.outstandingRequestIds())
        assertEquals(setOf(2L), tracker.replaceWorld(DistantProtocolWorld(8, 1, "minecraft:the_nether")))
    }

    @Test
    fun `protocol messages detach input collections and tracker rejects reentrant world replacement`() {
        val duplicate = DistantRequestedPage(key(0, 0), 0)
        val duplicateTracker = DistantTerrainRequestTracker(world, 2, 2)
        val mutablePages = mutableListOf(duplicate)
        val detachedRequest = DistantTerrainMessageV2.Request(world, 1, mutablePages)
        mutablePages.clear()
        assertTrue(duplicateTracker.register(detachedRequest))

        val nextWorld = DistantProtocolWorld(8, 1, "minecraft:the_nether")
        lateinit var tracker: DistantTerrainRequestTracker
        tracker = DistantTerrainRequestTracker(world, 2, 2) {
            tracker.replaceWorld(nextWorld)
            0L
        }
        assertTrue(tracker.register(DistantTerrainMessageV2.Request(world, 2, listOf(duplicate))))
        assertRejected(
            tracker.admit(DistantTerrainMessageV2.Response(world, 2, listOf(page(0, 0, 1)))),
            DistantResponseRejection.WRONG_WORLD,
        )
    }

    private fun assertRejected(admission: DistantResponseAdmission, reason: DistantResponseRejection) {
        assertEquals(reason, assertIs<DistantResponseAdmission.Rejected>(admission).reason)
    }

    private fun key(x: Long, z: Long) = TerrainPageKey(TerrainDomain.DISTANT, 0, x, 0, z, world.worldEpoch)

    private fun page(
        x: Long,
        z: Long,
        revision: Long,
        worldEpoch: Long = world.worldEpoch,
    ): DistantVerticalPage = DistantVerticalSampler.capture(
        key(x, z).copy(worldEpoch = worldEpoch), 1, 0, 2, revision, DistantSourceCompleteness.COMPLETE,
    ) { _, y, _ -> if (y == 0) DistantVoxelSample(TerrainSemanticMaterialId("minecraft:stone"), opaque = true) else DistantVoxelSample(null) }
}
