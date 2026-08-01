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

package de.bixilon.minosoft.terrain.runtime

import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey

enum class TerrainFailureCategory {
    STALE,
    CANCELLED,
    PRESSURE,
    TRANSIENT,
    CONTENT,
    PROVIDER,
    DEVICE_LOST,
}

enum class TerrainFailurePhase {
    ADMISSION,
    SNAPSHOT_CAPTURE,
    BUILD,
    PERSISTENCE,
    NETWORK,
    WORLD_GENERATION,
    STAGING,
    UPLOAD,
    PUBLICATION,
    SUBMISSION,
    RETIREMENT,
}

data class TerrainQuarantineKey(
    val sourceDataRevision: Long?,
    val schemaGeneration: Long,
    val materialGeneration: Long,
    val providerGeneration: Long,
) {
    init {
        require(sourceDataRevision == null || sourceDataRevision >= 0L) {
            "Terrain quarantine source-data revision must not be negative"
        }
        require(schemaGeneration >= 0L) { "Terrain quarantine schema generation must not be negative" }
        require(materialGeneration >= 0L) { "Terrain quarantine material generation must not be negative" }
        require(providerGeneration >= 0L) { "Terrain quarantine provider generation must not be negative" }
    }
}

sealed interface TerrainRetrySignal {
    data class ClockAdvanced(val monotonicNanos: Long) : TerrainRetrySignal {
        init {
            require(monotonicNanos >= 0L) { "Terrain retry clock must not be negative" }
        }
    }

    data class CapacityChanged(val capacityRevision: Long) : TerrainRetrySignal {
        init {
            require(capacityRevision >= 0L) { "Terrain capacity revision must not be negative" }
        }
    }

    data class QuarantineChanged(val key: TerrainQuarantineKey) : TerrainRetrySignal

    data class ProviderReplaced(val providerGeneration: Long) : TerrainRetrySignal {
        init {
            require(providerGeneration >= 0L) { "Terrain provider generation must not be negative" }
        }
    }

    data class DeviceRecreated(val deviceRuntime: TerrainDeviceRuntimeId) : TerrainRetrySignal
}

sealed interface TerrainRetryEligibility {
    fun isEligible(signal: TerrainRetrySignal): Boolean

    data object None : TerrainRetryEligibility {
        override fun isEligible(signal: TerrainRetrySignal): Boolean = false
    }

    data class AfterBackoff(
        val attemptsUsed: Int,
        val maximumAttempts: Int,
        val retryAtNanos: Long,
    ) : TerrainRetryEligibility {
        init {
            require(attemptsUsed >= 0) { "Terrain retry attempts used must not be negative" }
            require(maximumAttempts > 0) { "Terrain maximum retry attempts must be positive" }
            require(retryAtNanos >= 0L) { "Terrain retry deadline must not be negative" }
        }

        override fun isEligible(signal: TerrainRetrySignal): Boolean {
            return attemptsUsed < maximumAttempts &&
                signal is TerrainRetrySignal.ClockAdvanced &&
                signal.monotonicNanos >= retryAtNanos
        }
    }

    data class AfterCapacityChange(
        val capacityRevisionAtFailure: Long,
    ) : TerrainRetryEligibility {
        init {
            require(capacityRevisionAtFailure >= 0L) {
                "Terrain capacity revision at failure must not be negative"
            }
        }

        override fun isEligible(signal: TerrainRetrySignal): Boolean {
            return signal is TerrainRetrySignal.CapacityChanged &&
                signal.capacityRevision > capacityRevisionAtFailure
        }
    }

    data class AfterQuarantineChange(
        val failedKey: TerrainQuarantineKey,
    ) : TerrainRetryEligibility {
        override fun isEligible(signal: TerrainRetrySignal): Boolean {
            return signal is TerrainRetrySignal.QuarantineChanged && signal.key != failedKey
        }
    }

    data class AfterProviderReplacement(
        val failedProviderGeneration: Long,
    ) : TerrainRetryEligibility {
        init {
            require(failedProviderGeneration >= 0L) {
                "Terrain failed provider generation must not be negative"
            }
        }

        override fun isEligible(signal: TerrainRetrySignal): Boolean {
            return signal is TerrainRetrySignal.ProviderReplaced &&
                signal.providerGeneration > failedProviderGeneration
        }
    }

    data class AfterDeviceRecreation(
        val failedDeviceRuntime: TerrainDeviceRuntimeId,
    ) : TerrainRetryEligibility {
        override fun isEligible(signal: TerrainRetrySignal): Boolean {
            return signal is TerrainRetrySignal.DeviceRecreated &&
                signal.deviceRuntime != failedDeviceRuntime
        }
    }
}

data class TerrainFailureSnapshot(
    val category: TerrainFailureCategory,
    val phase: TerrainFailurePhase,
    val generation: Long,
    val retryEligibility: TerrainRetryEligibility,
) {
    init {
        require(generation >= 0L) { "Terrain failure generation must not be negative" }
        require(retryEligibility.matches(category)) {
            "Terrain retry eligibility $retryEligibility does not match failure category $category"
        }
    }

    private fun TerrainRetryEligibility.matches(category: TerrainFailureCategory): Boolean = when (category) {
        TerrainFailureCategory.STALE,
        TerrainFailureCategory.CANCELLED,
        -> this is TerrainRetryEligibility.None

        TerrainFailureCategory.PRESSURE -> this is TerrainRetryEligibility.AfterCapacityChange
        TerrainFailureCategory.TRANSIENT -> this is TerrainRetryEligibility.AfterBackoff
        TerrainFailureCategory.CONTENT -> this is TerrainRetryEligibility.AfterQuarantineChange
        TerrainFailureCategory.PROVIDER -> this is TerrainRetryEligibility.AfterProviderReplacement
        TerrainFailureCategory.DEVICE_LOST -> this is TerrainRetryEligibility.AfterDeviceRecreation
    }
}

data class TerrainPageFailureSnapshot(
    val page: TerrainPageKey,
    val failure: TerrainFailureSnapshot,
)

/** Bounded page-owner failure state with event-gated retry eligibility. */
class TerrainPageFailureRegistry(
    private val maximumEntries: Int,
) {
    private val failures = LinkedHashMap<TerrainPageKey, TerrainFailureSnapshot>()
    private val transientAttempts = HashMap<TerrainPageKey, Int>()
    var generation: Long = 0L
        private set

    val size: Int get() = failures.size

    init {
        require(maximumEntries > 0) { "Terrain page-failure capacity must be positive" }
    }

    operator fun get(page: TerrainPageKey): TerrainFailureSnapshot? = failures[page]

    fun recordTransient(
        page: TerrainPageKey,
        phase: TerrainFailurePhase,
        monotonicNanos: Long,
        maximumAttempts: Int,
        backoffNanos: Long,
    ): TerrainFailureSnapshot {
        require(monotonicNanos >= 0L) { "Terrain failure clock must not be negative" }
        require(maximumAttempts > 0) { "Terrain maximum retry attempts must be positive" }
        require(backoffNanos > 0L) { "Terrain retry backoff must be positive" }
        require(isTracked(page) || trackedSize() < maximumEntries) {
            "Terrain page-failure registry exceeds its bounded capacity"
        }
        val previousAttempts = transientAttempts[page] ?: 0
        val attempts = Math.addExact(previousAttempts, 1)
        transientAttempts[page] = attempts
        val delay = saturatingMultiply(backoffNanos, attempts.toLong())
        return record(
            page,
            TerrainFailureCategory.TRANSIENT,
            phase,
            TerrainRetryEligibility.AfterBackoff(
                attemptsUsed = attempts,
                maximumAttempts = maximumAttempts,
                retryAtNanos = saturatingAdd(monotonicNanos, delay),
            ),
        )
    }

    fun record(
        page: TerrainPageKey,
        category: TerrainFailureCategory,
        phase: TerrainFailurePhase,
        retryEligibility: TerrainRetryEligibility,
    ): TerrainFailureSnapshot {
        require(isTracked(page) || trackedSize() < maximumEntries) {
            "Terrain page-failure registry exceeds its bounded capacity"
        }
        if (category != TerrainFailureCategory.TRANSIENT) transientAttempts.remove(page)
        val snapshot = TerrainFailureSnapshot(
            category = category,
            phase = phase,
            generation = nextGeneration(),
            retryEligibility = retryEligibility,
        )
        failures[page] = snapshot
        return snapshot
    }

    fun clear(page: TerrainPageKey): Boolean {
        val removedFailure = failures.remove(page) != null
        val removedAttempts = transientAttempts.remove(page) != null
        if (!removedFailure && !removedAttempts) return false
        nextGeneration()
        return true
    }

    fun clear() {
        if (failures.isEmpty() && transientAttempts.isEmpty()) return
        failures.clear()
        transientAttempts.clear()
        nextGeneration()
    }

    fun releaseEligible(signal: TerrainRetrySignal): List<TerrainPageKey> {
        val eligible = failures.entries.asSequence()
            .filter { it.value.retryEligibility.isEligible(signal) }
            .map(Map.Entry<TerrainPageKey, TerrainFailureSnapshot>::key)
            .sortedWith(PAGE_ORDER)
            .toList()
        for (page in eligible) {
            failures.remove(page)
            nextGeneration()
        }
        return eligible
    }

    fun snapshot(maximumCount: Int = maximumEntries): List<TerrainPageFailureSnapshot> {
        require(maximumCount > 0) { "Terrain failure snapshot bound must be positive" }
        return failures.entries.asSequence()
            .sortedWith(compareBy(PAGE_ORDER, Map.Entry<TerrainPageKey, TerrainFailureSnapshot>::key))
            .take(maximumCount)
            .map { TerrainPageFailureSnapshot(it.key, it.value) }
            .toList()
    }

    private fun nextGeneration(): Long {
        generation = Math.incrementExact(generation)
        return generation
    }

    private fun isTracked(page: TerrainPageKey): Boolean = page in failures || page in transientAttempts

    private fun trackedSize(): Int = failures.size + transientAttempts.keys.count { it !in failures }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

    private companion object {
        val PAGE_ORDER: Comparator<TerrainPageKey> = compareBy(
            { it.domain.ordinal },
            { it.detailLevel },
            { it.z },
            { it.y },
            { it.x },
            { it.worldEpoch },
        )
    }
}
