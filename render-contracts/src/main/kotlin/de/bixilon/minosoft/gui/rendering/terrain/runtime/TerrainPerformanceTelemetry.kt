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

package de.bixilon.minosoft.gui.rendering.terrain.runtime

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.function.LongSupplier

enum class TerrainProductionPhase(val wireName: String) {
    QUEUE_WAIT("queueWait"),
    SNAPSHOT_CAPTURE("snapshotCapture"),
    MESH_BUILD("meshBuild"),
    WORKER_BUSY("workerBusy"),
    VISIBILITY("visibility"),
    UPLOAD("upload"),
}

data class TerrainLatencySnapshot(
    val samples: Long,
    val totalNanos: Long,
    val maximumNanos: Long,
    val medianNanos: Long,
    val p95Nanos: Long,
    val bucketUpperBoundsNanos: LongArray,
    val buckets: LongArray,
)

data class TerrainPerformanceSnapshot(
    val enabled: Boolean,
    val configuredWorkers: Int,
    val queuedBuilds: Int,
    val outstandingBuilds: Int,
    val activeWorkers: Int,
    val observationNanos: Long,
    val cumulativeWorkerUtilization: Double,
    val pendingUploads: Int,
    val queueHighWater: Int,
    val activeWorkerHighWater: Int,
    val pendingUploadHighWater: Int,
    val requestedBuilds: Long,
    val startedBuilds: Long,
    val successfulBuilds: Long,
    val cancelledBuilds: Long,
    val staleBuilds: Long,
    val rejectedBuilds: Long,
    val failedBuilds: Long,
    val failedUploads: Long,
    val outputBytes: Long,
    val uploadedBytes: Long,
    val visibleSections: Int,
    val phases: Map<TerrainProductionPhase, TerrainLatencySnapshot>,
)

/**
 * Allocation-free production terrain recording with bounded primitive storage.
 *
 * Human-readable maps are created only by [snapshot]. The hot path performs
 * primitive atomic increments and two monotonic clock reads around measured
 * phases. Disabling telemetry reduces each call to one volatile flag read.
 */
class TerrainPerformanceTelemetry(
    private val clock: LongSupplier = LongSupplier(System::nanoTime),
) {
    private val observationStartedNanos = clock.asLong
    private val completedObservationNanos = AtomicLong()
    @Volatile
    private var observationIntervalStartedNanos = observationStartedNanos

    private enum class Counter {
        REQUESTED,
        STARTED,
        SUCCEEDED,
        CANCELLED,
        STALE,
        REJECTED,
        FAILED,
        UPLOAD_FAILED,
        OUTPUT_BYTES,
        UPLOADED_BYTES,
    }

    @Volatile
    private var recordingEnabled: Boolean = true

    var enabled: Boolean
        get() = recordingEnabled
        set(value) {
            synchronized(this) {
                if (recordingEnabled == value) return
                val now = clock.asLong
                if (recordingEnabled) {
                    addSaturating(
                        completedObservationNanos,
                        (now - observationIntervalStartedNanos).coerceAtLeast(0L),
                    )
                } else {
                    observationIntervalStartedNanos = now
                }
                recordingEnabled = value
            }
        }

    private val counters = AtomicLongArray(Counter.entries.size)
    private val histograms = Array(TerrainProductionPhase.entries.size) { FixedTerrainLatencyHistogram() }
    private val configuredWorkers = AtomicInteger()
    private val outerQueuedBuilds = AtomicInteger()
    private val outstandingBuilds = AtomicInteger()
    private val activeWorkers = AtomicInteger()
    private val pendingUploads = AtomicInteger()
    private val queueHighWater = AtomicInteger()
    private val activeWorkerHighWater = AtomicInteger()
    private val pendingUploadHighWater = AtomicInteger()
    private val visibleSections = AtomicInteger()

    fun configureWorkers(count: Int) {
        require(count >= 0) { "Terrain worker count must not be negative" }
        configuredWorkers.set(count)
    }

    fun begin(phase: TerrainProductionPhase): Long {
        if (!enabled) return DISABLED_TIMESTAMP
        return clock.asLong
    }

    fun finish(phase: TerrainProductionPhase, startedNanos: Long) {
        if (
            startedNanos == DISABLED_TIMESTAMP ||
            !enabled ||
            startedNanos < observationIntervalStartedNanos
        ) {
            return
        }
        val elapsed = (clock.asLong - startedNanos).coerceAtLeast(0L)
        histograms[phase.ordinal].record(elapsed)
    }

    fun requested() = increment(Counter.REQUESTED)
    fun started() = increment(Counter.STARTED)
    fun succeeded() = increment(Counter.SUCCEEDED)
    fun cancelled() = increment(Counter.CANCELLED)
    fun stale() = increment(Counter.STALE)
    fun rejected() = increment(Counter.REJECTED)
    fun failed() = increment(Counter.FAILED)
    fun uploadFailed() = increment(Counter.UPLOAD_FAILED)

    fun output(bytes: Long) = add(Counter.OUTPUT_BYTES, bytes)
    fun uploaded(bytes: Long) = add(Counter.UPLOADED_BYTES, bytes)

    fun queueDepth(queued: Int) {
        require(queued >= 0) { "Terrain build queue depth must not be negative" }
        if (!enabled) return
        outerQueuedBuilds.set(queued)
        updateQueueHighWater()
    }

    fun outstandingBuilds(builds: Int) {
        require(builds >= 0) { "Terrain outstanding build count must not be negative" }
        if (!enabled) return
        outstandingBuilds.set(builds)
        updateQueueHighWater()
    }

    fun pendingUploads(uploads: Int) {
        require(uploads >= 0) { "Terrain upload queue depth must not be negative" }
        if (!enabled) return
        pendingUploads.set(uploads)
        updateMaximum(pendingUploadHighWater, uploads)
    }

    fun visible(sections: Int) {
        require(sections >= 0) { "Visible terrain section count must not be negative" }
        if (enabled) visibleSections.set(sections)
    }

    fun workerStarted(): Long {
        if (!enabled) return DISABLED_TIMESTAMP
        val active = activeWorkers.incrementAndGet()
        updateMaximum(activeWorkerHighWater, active)
        return clock.asLong
    }

    fun workerFinished(startedNanos: Long) {
        if (startedNanos == DISABLED_TIMESTAMP) return
        decrementPositive(activeWorkers, "Terrain active-worker telemetry underflow")
        finish(TerrainProductionPhase.WORKER_BUSY, startedNanos)
    }

    fun snapshot(): TerrainPerformanceSnapshot {
        val phaseSnapshots = TerrainProductionPhase.entries.associateWith {
            histograms[it.ordinal].snapshot()
        }
        val workers = configuredWorkers.get()
        val active = activeWorkers.get()
        val outstanding = outstandingBuilds.get()
        val queued = addSaturating(outerQueuedBuilds.get(), (outstanding - active).coerceAtLeast(0))
        val completedObservation = completedObservationNanos.get()
        val observationNanos = if (enabled) {
            val current = (clock.asLong - observationIntervalStartedNanos).coerceAtLeast(0L)
            if (Long.MAX_VALUE - completedObservation < current) Long.MAX_VALUE else completedObservation + current
        } else {
            completedObservation
        }
        val workerBusyNanos = phaseSnapshots.getValue(TerrainProductionPhase.WORKER_BUSY).totalNanos
        val workerUtilization = if (workers == 0 || observationNanos == 0L) {
            0.0
        } else {
            (workerBusyNanos.toDouble() / (observationNanos.toDouble() * workers)).coerceIn(0.0, 1.0)
        }
        return TerrainPerformanceSnapshot(
            enabled = enabled,
            configuredWorkers = workers,
            queuedBuilds = queued,
            outstandingBuilds = outstanding,
            activeWorkers = active,
            observationNanos = observationNanos,
            cumulativeWorkerUtilization = workerUtilization,
            pendingUploads = pendingUploads.get(),
            queueHighWater = queueHighWater.get(),
            activeWorkerHighWater = activeWorkerHighWater.get(),
            pendingUploadHighWater = pendingUploadHighWater.get(),
            requestedBuilds = counter(Counter.REQUESTED),
            startedBuilds = counter(Counter.STARTED),
            successfulBuilds = counter(Counter.SUCCEEDED),
            cancelledBuilds = counter(Counter.CANCELLED),
            staleBuilds = counter(Counter.STALE),
            rejectedBuilds = counter(Counter.REJECTED),
            failedBuilds = counter(Counter.FAILED),
            failedUploads = counter(Counter.UPLOAD_FAILED),
            outputBytes = counter(Counter.OUTPUT_BYTES),
            uploadedBytes = counter(Counter.UPLOADED_BYTES),
            visibleSections = visibleSections.get(),
            phases = phaseSnapshots,
        )
    }

    private fun increment(counter: Counter) {
        if (enabled) addSaturating(counters, counter.ordinal, 1L)
    }

    private fun add(counter: Counter, value: Long) {
        require(value >= 0L) { "Terrain telemetry value must not be negative" }
        if (enabled) addSaturating(counters, counter.ordinal, value)
    }

    private fun counter(counter: Counter): Long = counters.get(counter.ordinal)

    private fun updateMaximum(target: AtomicInteger, value: Int) {
        var current = target.get()
        while (value > current && !target.compareAndSet(current, value)) {
            current = target.get()
        }
    }

    private fun updateQueueHighWater() {
        val active = activeWorkers.get()
        val queued = addSaturating(
            outerQueuedBuilds.get(),
            (outstandingBuilds.get() - active).coerceAtLeast(0),
        )
        updateMaximum(queueHighWater, queued)
    }

    private fun addSaturating(first: Int, second: Int): Int =
        if (Int.MAX_VALUE - first < second) Int.MAX_VALUE else first + second

    private fun decrementPositive(target: AtomicInteger, message: String) {
        while (true) {
            val current = target.get()
            check(current > 0) { message }
            if (target.compareAndSet(current, current - 1)) return
        }
    }

    private fun addSaturating(target: AtomicLongArray, index: Int, value: Long) {
        while (true) {
            val current = target.get(index)
            val next = if (Long.MAX_VALUE - current < value) Long.MAX_VALUE else current + value
            if (target.compareAndSet(index, current, next)) return
        }
    }

    private fun addSaturating(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            val next = if (Long.MAX_VALUE - current < value) Long.MAX_VALUE else current + value
            if (target.compareAndSet(current, next)) return
        }
    }

    private class FixedTerrainLatencyHistogram {
        private val buckets = AtomicLongArray(LATENCY_BUCKET_UPPER_BOUNDS_NANOS.size + 1)
        private val totalNanos = AtomicLong()
        private val maximumNanos = AtomicLong()

        fun record(nanoseconds: Long) {
            require(nanoseconds >= 0L) { "Terrain latency must not be negative" }
            addSaturating(totalNanos, nanoseconds)
            updateMaximum(maximumNanos, nanoseconds)
            var bucket = 0
            while (
                bucket < LATENCY_BUCKET_UPPER_BOUNDS_NANOS.size &&
                nanoseconds > LATENCY_BUCKET_UPPER_BOUNDS_NANOS[bucket]
            ) {
                bucket++
            }
            addSaturating(buckets, bucket, 1L)
        }

        fun snapshot(): TerrainLatencySnapshot {
            val bucketSnapshot = LongArray(buckets.length()) { buckets.get(it) }
            val sampleCount = bucketSnapshot.fold(0L) { total, count ->
                if (Long.MAX_VALUE - total < count) Long.MAX_VALUE else total + count
            }
            return TerrainLatencySnapshot(
                samples = sampleCount,
                totalNanos = totalNanos.get(),
                maximumNanos = maximumNanos.get(),
                medianNanos = percentile(bucketSnapshot, sampleCount, 50L),
                p95Nanos = percentile(bucketSnapshot, sampleCount, 95L),
                bucketUpperBoundsNanos = LATENCY_BUCKET_UPPER_BOUNDS_NANOS.copyOf(),
                buckets = bucketSnapshot,
            )
        }

        private fun percentile(bucketSnapshot: LongArray, sampleCount: Long, percentile: Long): Long {
            if (sampleCount == 0L) return 0L
            val target =
                (sampleCount / 100L) * percentile +
                    (((sampleCount % 100L) * percentile) + 99L) / 100L
            var cumulative = 0L
            for (index in bucketSnapshot.indices) {
                val count = bucketSnapshot[index]
                if (count < target - cumulative) {
                    cumulative += count
                    continue
                }
                return LATENCY_BUCKET_UPPER_BOUNDS_NANOS.getOrElse(index) { Long.MAX_VALUE }
            }
            return Long.MAX_VALUE
        }

        private fun addSaturating(target: AtomicLong, value: Long) {
            while (true) {
                val current = target.get()
                val next = if (Long.MAX_VALUE - current < value) Long.MAX_VALUE else current + value
                if (target.compareAndSet(current, next)) return
            }
        }

        private fun addSaturating(target: AtomicLongArray, index: Int, value: Long) {
            while (true) {
                val current = target.get(index)
                val next = if (Long.MAX_VALUE - current < value) Long.MAX_VALUE else current + value
                if (target.compareAndSet(index, current, next)) return
            }
        }

        private fun updateMaximum(target: AtomicLong, value: Long) {
            var current = target.get()
            while (value > current && !target.compareAndSet(current, value)) {
                current = target.get()
            }
        }
    }

    companion object {
        private const val DISABLED_TIMESTAMP = Long.MIN_VALUE

        val LATENCY_BUCKET_UPPER_BOUNDS_NANOS = longArrayOf(
            50_000L,
            100_000L,
            250_000L,
            500_000L,
            1_000_000L,
            2_500_000L,
            5_000_000L,
            10_000_000L,
            25_000_000L,
            50_000_000L,
            100_000_000L,
            250_000_000L,
            500_000_000L,
            1_000_000_000L,
            2_500_000_000L,
            5_000_000_000L,
        )
    }
}
