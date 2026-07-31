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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainFailureTest {
    @Test
    fun `transient retries honor backoff and attempt bound`() {
        val retry = TerrainRetryEligibility.AfterBackoff(
            attemptsUsed = 2,
            maximumAttempts = 3,
            retryAtNanos = 1_000L,
        )
        TerrainFailureSnapshot(
            category = TerrainFailureCategory.TRANSIENT,
            phase = TerrainFailurePhase.NETWORK,
            generation = 4L,
            retryEligibility = retry,
        )

        assertFalse(retry.isEligible(TerrainRetrySignal.ClockAdvanced(999L)))
        assertTrue(retry.isEligible(TerrainRetrySignal.ClockAdvanced(1_000L)))
        assertFalse(
            retry.copy(attemptsUsed = 3).isEligible(TerrainRetrySignal.ClockAdvanced(1_000L)),
        )
    }

    @Test
    fun `pressure content provider and device failures require matching change`() {
        val pressure = TerrainRetryEligibility.AfterCapacityChange(7L)
        assertFalse(pressure.isEligible(TerrainRetrySignal.CapacityChanged(7L)))
        assertTrue(pressure.isEligible(TerrainRetrySignal.CapacityChanged(8L)))

        val quarantine = TerrainQuarantineKey(
            sourceDataRevision = 3L,
            schemaGeneration = 4L,
            materialGeneration = 5L,
            providerGeneration = 6L,
        )
        val content = TerrainRetryEligibility.AfterQuarantineChange(quarantine)
        assertFalse(content.isEligible(TerrainRetrySignal.QuarantineChanged(quarantine)))
        assertTrue(
            content.isEligible(
                TerrainRetrySignal.QuarantineChanged(quarantine.copy(materialGeneration = 7L)),
            ),
        )

        val provider = TerrainRetryEligibility.AfterProviderReplacement(6L)
        assertFalse(provider.isEligible(TerrainRetrySignal.ProviderReplaced(6L)))
        assertTrue(provider.isEligible(TerrainRetrySignal.ProviderReplaced(7L)))

        val failedDevice = TerrainDeviceRuntimeId(TerrainProcessScopeId(1L), 2L)
        val device = TerrainRetryEligibility.AfterDeviceRecreation(failedDevice)
        assertFalse(device.isEligible(TerrainRetrySignal.DeviceRecreated(failedDevice)))
        assertTrue(
            device.isEligible(
                TerrainRetrySignal.DeviceRecreated(failedDevice.copy(deviceGeneration = 3L)),
            ),
        )
    }

    @Test
    fun `failure categories reject mismatched retry rules`() {
        assertThrows<IllegalArgumentException> {
            TerrainFailureSnapshot(
                category = TerrainFailureCategory.CONTENT,
                phase = TerrainFailurePhase.BUILD,
                generation = 1L,
                retryEligibility = TerrainRetryEligibility.None,
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainFailureSnapshot(
                category = TerrainFailureCategory.CANCELLED,
                phase = TerrainFailurePhase.BUILD,
                generation = 1L,
                retryEligibility = TerrainRetryEligibility.AfterBackoff(0, 1, 0L),
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainRetryEligibility.AfterBackoff(
                attemptsUsed = 0,
                maximumAttempts = 0,
                retryAtNanos = 0L,
            )
        }
    }
}
