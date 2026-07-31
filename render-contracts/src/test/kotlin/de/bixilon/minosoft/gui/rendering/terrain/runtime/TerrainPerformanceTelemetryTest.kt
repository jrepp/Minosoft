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

import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainPerformanceTelemetryTest {
    @Test
    fun `production counters and fixed histograms snapshot without hot-path maps`() {
        val now = AtomicLong()
        val telemetry = TerrainPerformanceTelemetry(LongSupplier(now::get))
        telemetry.configureWorkers(3)
        telemetry.queueDepth(4)
        telemetry.outstandingBuilds(2)
        telemetry.pendingUploads(2)
        telemetry.visible(7)
        telemetry.requested()
        telemetry.started()
        telemetry.succeeded()
        telemetry.cancelled()
        telemetry.stale()
        telemetry.rejected()
        telemetry.failed()
        telemetry.uploadFailed()
        telemetry.output(4_096L)
        telemetry.uploaded(2_048L)

        val queueWait = telemetry.begin(TerrainProductionPhase.QUEUE_WAIT)
        now.set(750_000L)
        telemetry.finish(TerrainProductionPhase.QUEUE_WAIT, queueWait)
        val worker = telemetry.workerStarted()
        now.set(2_750_000L)
        telemetry.outstandingBuilds(1)
        telemetry.workerFinished(worker)

        val snapshot = telemetry.snapshot()
        assertEquals(3, snapshot.configuredWorkers)
        assertEquals(5, snapshot.queuedBuilds)
        assertEquals(6, snapshot.queueHighWater)
        assertEquals(1, snapshot.outstandingBuilds)
        assertEquals(0, snapshot.activeWorkers)
        assertEquals(1, snapshot.activeWorkerHighWater)
        assertEquals(2, snapshot.pendingUploads)
        assertEquals(2, snapshot.pendingUploadHighWater)
        assertEquals(7, snapshot.visibleSections)
        assertEquals(1L, snapshot.requestedBuilds)
        assertEquals(1L, snapshot.startedBuilds)
        assertEquals(1L, snapshot.successfulBuilds)
        assertEquals(1L, snapshot.cancelledBuilds)
        assertEquals(1L, snapshot.staleBuilds)
        assertEquals(1L, snapshot.rejectedBuilds)
        assertEquals(1L, snapshot.failedBuilds)
        assertEquals(1L, snapshot.failedUploads)
        assertEquals(4_096L, snapshot.outputBytes)
        assertEquals(2_048L, snapshot.uploadedBytes)
        assertEquals(1L, snapshot.phases.getValue(TerrainProductionPhase.QUEUE_WAIT).samples)
        assertEquals(1_000_000L, snapshot.phases.getValue(TerrainProductionPhase.QUEUE_WAIT).p95Nanos)
        assertEquals(1L, snapshot.phases.getValue(TerrainProductionPhase.WORKER_BUSY).samples)
        assertEquals(
            TerrainPerformanceTelemetry.LATENCY_BUCKET_UPPER_BOUNDS_NANOS.size + 1,
            snapshot.phases.getValue(TerrainProductionPhase.QUEUE_WAIT).buckets.size,
        )
    }

    @Test
    fun `disabled telemetry performs no clock reads or state mutation`() {
        val clockReads = AtomicLong()
        val telemetry = TerrainPerformanceTelemetry(LongSupplier(clockReads::incrementAndGet))
        telemetry.enabled = false
        val disabledAtReads = clockReads.get()

        val start = telemetry.begin(TerrainProductionPhase.MESH_BUILD)
        telemetry.finish(TerrainProductionPhase.MESH_BUILD, start)
        val worker = telemetry.workerStarted()
        telemetry.workerFinished(worker)
        telemetry.requested()
        telemetry.queueDepth(5)
        telemetry.pendingUploads(3)
        telemetry.visible(2)

        val snapshot = telemetry.snapshot()
        assertEquals(disabledAtReads, clockReads.get())
        assertEquals(0L, snapshot.requestedBuilds)
        assertEquals(0, snapshot.queuedBuilds)
        assertEquals(0, snapshot.pendingUploads)
        assertEquals(0, snapshot.visibleSections)
        assertEquals(0L, snapshot.phases.getValue(TerrainProductionPhase.MESH_BUILD).samples)
    }

    @Test
    fun `toggle rejects phases that cross a disabled interval`() {
        val now = AtomicLong()
        val telemetry = TerrainPerformanceTelemetry(LongSupplier(now::get))
        val crossed = telemetry.begin(TerrainProductionPhase.UPLOAD)
        now.set(10L)
        telemetry.enabled = false
        now.set(20L)
        telemetry.enabled = true
        now.set(30L)
        telemetry.finish(TerrainProductionPhase.UPLOAD, crossed)

        val accepted = telemetry.begin(TerrainProductionPhase.UPLOAD)
        now.set(40L)
        telemetry.finish(TerrainProductionPhase.UPLOAD, accepted)

        val snapshot = telemetry.snapshot()
        assertEquals(1L, snapshot.phases.getValue(TerrainProductionPhase.UPLOAD).samples)
        assertEquals(10L, snapshot.phases.getValue(TerrainProductionPhase.UPLOAD).totalNanos)
        assertEquals(30L, snapshot.observationNanos)
    }

    @Test
    fun `invalid worker completion does not corrupt active count`() {
        val telemetry = TerrainPerformanceTelemetry(LongSupplier { 1L })

        assertThrows<IllegalStateException> {
            telemetry.workerFinished(0L)
        }

        assertEquals(0, telemetry.snapshot().activeWorkers)
        assertEquals(0L, telemetry.snapshot().phases.getValue(TerrainProductionPhase.WORKER_BUSY).samples)
    }

    @Test
    fun `combined queue depth saturates without breaking telemetry`() {
        val telemetry = TerrainPerformanceTelemetry(LongSupplier { 1L })
        telemetry.queueDepth(Int.MAX_VALUE)
        telemetry.outstandingBuilds(Int.MAX_VALUE)

        val snapshot = telemetry.snapshot()

        assertEquals(Int.MAX_VALUE, snapshot.queuedBuilds)
        assertEquals(Int.MAX_VALUE, snapshot.queueHighWater)
    }

    @Test
    fun `instrumentation does not regress representative workload p95`() {
        repeat(WARMUP_ROUNDS) {
            representativeWorkload(TerrainPerformanceTelemetry().apply { enabled = false })
            representativeWorkload(TerrainPerformanceTelemetry())
        }

        val disabled = LongArray(MEASURED_ROUNDS)
        val enabled = LongArray(MEASURED_ROUNDS)
        repeat(MEASURED_ROUNDS) { round ->
            if (round and 1 == 0) {
                disabled[round] = representativeWorkload(TerrainPerformanceTelemetry().apply { this.enabled = false })
                enabled[round] = representativeWorkload(TerrainPerformanceTelemetry())
            } else {
                enabled[round] = representativeWorkload(TerrainPerformanceTelemetry())
                disabled[round] = representativeWorkload(TerrainPerformanceTelemetry().apply { this.enabled = false })
            }
        }

        val disabledP95 = percentile95(disabled)
        val enabledP95 = percentile95(enabled)
        val permitted = (disabledP95 * MAX_P95_RATIO).toLong() + ABSOLUTE_NOISE_NANOS
        println(
            "terrain telemetry A/B p95: disabled=$disabledP95 ns enabled=$enabledP95 ns " +
                "limit=$permitted ns",
        )
        assertTrue(
            enabledP95 <= permitted,
            "terrain instrumentation p95 regressed: disabled=$disabledP95 ns, enabled=$enabledP95 ns, limit=$permitted ns",
        )
    }

    private fun representativeWorkload(telemetry: TerrainPerformanceTelemetry): Long {
        val started = System.nanoTime()
        var value = performanceSink
        repeat(PHASES_PER_ROUND) {
            val phase = telemetry.begin(TerrainProductionPhase.MESH_BUILD)
            repeat(WORK_PER_PHASE) {
                value = value * 1_664_525L + 1_013_904_223L
                value = value xor (value ushr 17)
            }
            telemetry.finish(TerrainProductionPhase.MESH_BUILD, phase)
        }
        performanceSink = value
        return System.nanoTime() - started
    }

    private fun percentile95(values: LongArray): Long =
        values.sortedArray()[((values.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]

    private companion object {
        const val WARMUP_ROUNDS = 6
        const val MEASURED_ROUNDS = 100
        const val PHASES_PER_ROUND = 128
        const val WORK_PER_PHASE = 8_192
        const val MAX_P95_RATIO = 1.10
        const val ABSOLUTE_NOISE_NANOS = 100_000L

        @Volatile
        var performanceSink = 1L
    }
}
