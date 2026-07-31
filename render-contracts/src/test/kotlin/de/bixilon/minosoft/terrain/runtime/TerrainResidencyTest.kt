/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainResidencyTest {
    @Test
    fun `cap snapshot accounts resident retired and pinned bytes`() {
        val snapshot = TerrainResidencyCapSnapshot(
            scope = TerrainResidencyScope.DEVICE,
            capBytes = 1_024L,
            accounting = listOf(
                TerrainResidencyCategoryAccounting(
                    TerrainResidencyCategory.RESIDENT_NEAR_GPU_RANGE,
                    residentBytes = 400L,
                    pinnedBytes = 250L,
                ),
                TerrainResidencyCategoryAccounting(
                    TerrainResidencyCategory.RETIRED_GPU_RANGE,
                    residentBytes = 0L,
                    retiredBytes = 100L,
                    pinnedBytes = 100L,
                ),
            ),
            highWaterMarkBytes = 700L,
            evictionCount = 2L,
            evictedBytes = 128L,
            deferralCount = 3L,
            hardFailureCount = 1L,
        )

        assertEquals(400L, snapshot.residentBytes)
        assertEquals(100L, snapshot.retiredBytes)
        assertEquals(350L, snapshot.pinnedBytes)
        assertEquals(500L, snapshot.accountedBytes)
        assertEquals(524L, snapshot.availableBytes)
        assertTrue(snapshot.canReserve(524L))
        assertFalse(snapshot.canReserve(525L))
    }

    @Test
    fun `accounting rejects invalid categories bounds and duplicates`() {
        assertThrows<IllegalArgumentException> {
            TerrainResidencyCategoryAccounting(
                TerrainResidencyCategory.STAGING_BUFFER,
                residentBytes = 10L,
                retiredBytes = 1L,
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainResidencyCategoryAccounting(
                TerrainResidencyCategory.RETIRED_GPU_RANGE,
                residentBytes = 1L,
                retiredBytes = 10L,
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainResidencyCategoryAccounting(
                TerrainResidencyCategory.DETACHED_BUILD_SNAPSHOT,
                residentBytes = 10L,
                pinnedBytes = 11L,
            )
        }
        assertThrows<IllegalArgumentException> {
            deviceSnapshot(
                capBytes = 9L,
                accounting = listOf(deviceAccounting(10L)),
                highWaterMarkBytes = 10L,
            )
        }
        assertThrows<IllegalArgumentException> {
            deviceSnapshot(
                capBytes = 20L,
                accounting = listOf(deviceAccounting(10L), deviceAccounting(10L)),
                highWaterMarkBytes = 20L,
            )
        }
        assertThrows<IllegalArgumentException> {
            deviceSnapshot(
                capBytes = 20L,
                accounting = listOf(
                    TerrainResidencyCategoryAccounting(
                        TerrainResidencyCategory.DETACHED_BUILD_SNAPSHOT,
                        residentBytes = 10L,
                    ),
                ),
                highWaterMarkBytes = 10L,
            )
        }
    }

    @Test
    fun `accounting uses checked byte arithmetic`() {
        assertThrows<ArithmeticException> {
            TerrainResidencyCategoryAccounting(
                TerrainResidencyCategory.RETIRED_GPU_RANGE,
                residentBytes = Long.MAX_VALUE,
                retiredBytes = 1L,
            )
        }
        assertThrows<ArithmeticException> {
            TerrainResidencyCategoryAccounting(
                TerrainResidencyCategory.STAGING_BUFFER,
                residentBytes = Long.MAX_VALUE,
            ).plus(
                TerrainResidencyCategoryAccounting(
                    TerrainResidencyCategory.STAGING_BUFFER,
                    residentBytes = 1L,
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            deviceSnapshot(
                capBytes = Long.MAX_VALUE,
                accounting = listOf(deviceAccounting(0L)),
                highWaterMarkBytes = 0L,
            ).canReserve(-1L)
        }
    }

    private fun deviceAccounting(bytes: Long) = TerrainResidencyCategoryAccounting(
        TerrainResidencyCategory.STAGING_BUFFER,
        residentBytes = bytes,
    )

    private fun deviceSnapshot(
        capBytes: Long,
        accounting: Collection<TerrainResidencyCategoryAccounting>,
        highWaterMarkBytes: Long,
    ) = TerrainResidencyCapSnapshot(
        scope = TerrainResidencyScope.DEVICE,
        capBytes = capBytes,
        accounting = accounting,
        highWaterMarkBytes = highWaterMarkBytes,
    )
}
