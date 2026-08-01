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

class TerrainSubmissionTest {
    @Test
    fun `serials are positive ordered and checked`() {
        assertThrows<IllegalArgumentException> { TerrainSubmissionSerial(0L) }
        assertThrows<IllegalArgumentException> { TerrainSubmissionSerial(-1L) }

        val first = TerrainSubmissionSerial(1L)
        val second = first.next()

        assertTrue(first < second)
        assertEquals(TerrainSubmissionSerial(2L), second)
        assertThrows<ArithmeticException> { TerrainSubmissionSerial(Long.MAX_VALUE).next() }
    }

    @Test
    fun `completion is per serial and only complete permits range reuse`() {
        val device = TerrainDeviceRuntimeId(TerrainProcessScopeId(1L), 2L)
        val completion = object : TerrainSubmissionCompletion {
            override val deviceRuntime: TerrainDeviceRuntimeId = device

            override fun state(serial: TerrainSubmissionSerial): TerrainSubmissionState = when (serial.value) {
                1L -> TerrainSubmissionState.PENDING
                2L -> TerrainSubmissionState.COMPLETE
                else -> TerrainSubmissionState.FAILED
            }
        }

        assertFalse(completion.state(TerrainSubmissionSerial(1L)).permitsRangeReuse)
        assertTrue(completion.state(TerrainSubmissionSerial(2L)).permitsRangeReuse)
        assertFalse(completion.state(TerrainSubmissionSerial(3L)).permitsRangeReuse)
        assertFalse(TerrainSubmissionState.DEVICE_INVALIDATED.permitsRangeReuse)
    }

    @Test
    fun `one device sequencer gives every provider a unique monotonic submission`() {
        val device = TerrainDeviceRuntimeId(TerrainProcessScopeId(3L), 5L)
        val sequencer = TerrainSubmissionSequencer(device)

        assertEquals(null, sequencer.latest())
        val near = sequencer.next()
        val distant = sequencer.next()

        assertEquals(device, near.deviceRuntime)
        assertEquals(device, distant.deviceRuntime)
        assertEquals(TerrainSubmissionSerial(1L), near.serial)
        assertEquals(TerrainSubmissionSerial(2L), distant.serial)
        assertEquals(distant.serial, sequencer.latest())
    }
}
