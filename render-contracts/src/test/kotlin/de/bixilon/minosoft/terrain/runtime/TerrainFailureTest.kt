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

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainFailureTest {
    @Test
    fun `page failure registry bounds state and releases retries only after their signal`() {
        val registry = TerrainPageFailureRegistry(maximumEntries = 2)
        val first = page(1L)
        val second = page(2L)
        val third = page(3L)

        val failure = registry.recordTransient(
            page = second,
            phase = TerrainFailurePhase.BUILD,
            monotonicNanos = 100L,
            maximumAttempts = 3,
            backoffNanos = 10L,
        )
        registry.recordTransient(first, TerrainFailurePhase.BUILD, 100L, 3, 10L)
        assertEquals(1, (failure.retryEligibility as TerrainRetryEligibility.AfterBackoff).attemptsUsed)
        assertEquals(listOf(first, second), registry.snapshot().map(TerrainPageFailureSnapshot::page))
        assertTrue(registry.releaseEligible(TerrainRetrySignal.ClockAdvanced(109L)).isEmpty())
        assertEquals(listOf(first, second), registry.releaseEligible(TerrainRetrySignal.ClockAdvanced(110L)))
        assertEquals(0, registry.size)

        val secondAttempt = registry.recordTransient(second, TerrainFailurePhase.BUILD, 200L, 3, 10L)
        assertEquals(2, (secondAttempt.retryEligibility as TerrainRetryEligibility.AfterBackoff).attemptsUsed)
        assertEquals(listOf(second), registry.releaseEligible(TerrainRetrySignal.ClockAdvanced(220L)))
        val finalAttempt = registry.recordTransient(second, TerrainFailurePhase.BUILD, 300L, 3, 10L)
        assertEquals(3, (finalAttempt.retryEligibility as TerrainRetryEligibility.AfterBackoff).attemptsUsed)
        assertTrue(registry.releaseEligible(TerrainRetrySignal.ClockAdvanced(Long.MAX_VALUE)).isEmpty())
        assertThrows<IllegalArgumentException> {
            registry.recordTransient(third, TerrainFailurePhase.BUILD, 200L, 3, 10L)
        }
        registry.clear()
        assertEquals(0, registry.size)
    }

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
    fun `upload pressure remains quarantined until storage capacity advances`() {
        val registry = TerrainPageFailureRegistry(maximumEntries = 1)
        val page = page(9L)
        registry.record(
            page = page,
            category = TerrainFailureCategory.PRESSURE,
            phase = TerrainFailurePhase.UPLOAD,
            retryEligibility = TerrainRetryEligibility.AfterCapacityChange(12L),
        )

        assertTrue(registry.releaseEligible(TerrainRetrySignal.ClockAdvanced(Long.MAX_VALUE)).isEmpty())
        assertTrue(registry.releaseEligible(TerrainRetrySignal.CapacityChanged(12L)).isEmpty())
        assertEquals(listOf(page), registry.releaseEligible(TerrainRetrySignal.CapacityChanged(13L)))
        assertEquals(0, registry.size)
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

    private fun page(x: Long) = TerrainPageKey(
        domain = TerrainDomain.DISTANT,
        detailLevel = 0,
        x = x,
        y = 0L,
        z = 0L,
        worldEpoch = 1L,
    )
}
