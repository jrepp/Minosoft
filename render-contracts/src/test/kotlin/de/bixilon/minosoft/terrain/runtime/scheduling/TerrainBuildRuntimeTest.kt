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

package de.bixilon.minosoft.terrain.runtime.scheduling

import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainBuildRuntimeTest {
    @Test
    fun `estimated CPU and output budgets reject independently and retire on drain`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = TerrainBuildRuntime<AutoCloseable, String>(
            workerCount = 1,
            queueCapacity = 4,
            estimatedCpuBudgetNanos = 10L,
            estimatedOutputBudgetBytes = 100L,
            contextFactory = { AutoCloseable {} },
        )
        val tenant = TerrainSchedulerTenantId("test:estimated-admission")
        assertNotNull(runtime.submit(
            tenant,
            identity(TerrainDomain.NEAR, 1L),
            estimate = TerrainBuildEstimate(cpuNanos = 6L, outputBytes = 60L),
        ) { _, _ ->
            started.countDown()
            assertTrue(release.await(5L, TimeUnit.SECONDS))
            "accepted"
        })
        assertTrue(started.await(5L, TimeUnit.SECONDS))
        assertNull(runtime.submit(
            tenant,
            identity(TerrainDomain.NEAR, 2L),
            estimate = TerrainBuildEstimate(cpuNanos = 5L, outputBytes = 1L),
        ) { _, _ -> "cpu-rejected" })
        assertNull(runtime.submit(
            tenant,
            identity(TerrainDomain.NEAR, 3L),
            estimate = TerrainBuildEstimate(cpuNanos = 1L, outputBytes = 50L),
        ) { _, _ -> "output-rejected" })

        val admitted = runtime.snapshot()
        assertEquals(6L, admitted.outstandingEstimatedCpuNanos)
        assertEquals(60L, admitted.outstandingEstimatedOutputBytes)
        assertEquals(1L, admitted.estimatedCpuRejected)
        assertEquals(1L, admitted.estimatedOutputRejected)
        release.countDown()
        assertEquals(listOf("accepted"), awaitValues(runtime, 1))
        val drained = runtime.snapshot()
        assertEquals(0L, drained.outstandingEstimatedCpuNanos)
        assertEquals(0L, drained.outstandingEstimatedOutputBytes)
        runtime.close()
    }

    @Test
    fun `distant work receives reserved capacity during near churn`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val runtime = runtime(queueCapacity = 4, nearBurstLimit = 2)
        val near = TerrainSchedulerTenantId("test:near")
        val distant = TerrainSchedulerTenantId("test:distant")

        assertNotNull(runtime.submit(near, identity(TerrainDomain.NEAR, 0L)) { _, _ ->
            firstStarted.countDown()
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            "near-0"
        })
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        assertNotNull(runtime.submit(near, identity(TerrainDomain.NEAR, 1L)) { _, _ -> "near-1" })
        assertNotNull(runtime.submit(near, identity(TerrainDomain.NEAR, 2L)) { _, _ -> "near-2" })
        assertNotNull(runtime.submit(distant, identity(TerrainDomain.DISTANT, 3L)) { _, _ -> "distant" })
        assertNotNull(runtime.submit(near, identity(TerrainDomain.NEAR, 4L)) { _, _ -> "near-3" })
        releaseFirst.countDown()

        val values = awaitValues(runtime, 5)
        assertEquals(listOf("near-0", "near-1", "distant", "near-2", "near-3"), values)
        runtime.close()
    }

    @Test
    fun `equal urgency rotates between tenants`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val runtime = runtime(queueCapacity = 3)
        val first = TerrainSchedulerTenantId("test:first")
        val second = TerrainSchedulerTenantId("test:second")

        assertNotNull(runtime.submit(first, identity(TerrainDomain.NEAR, 0L)) { _, _ ->
            firstStarted.countDown()
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            "first-0"
        })
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        assertNotNull(runtime.submit(first, identity(TerrainDomain.NEAR, 1L)) { _, _ -> "first-1" })
        assertNotNull(runtime.submit(first, identity(TerrainDomain.NEAR, 2L)) { _, _ -> "first-2" })
        assertNotNull(runtime.submit(second, identity(TerrainDomain.NEAR, 3L)) { _, _ -> "second" })
        releaseFirst.countDown()

        val values = awaitValues(runtime, 4)
        assertEquals(listOf("first-0", "second", "first-1", "first-2"), values)
        runtime.close()
    }

    @Test
    fun `completion drain and telemetry stay bounded`() {
        val runtime = runtime(queueCapacity = 2)
        val tenant = TerrainSchedulerTenantId("test:near")
        assertNotNull(runtime.submit(tenant, identity(TerrainDomain.NEAR, 1L)) { _, _ -> "one" })
        assertNotNull(runtime.submit(tenant, identity(TerrainDomain.NEAR, 2L)) { _, _ -> "two" })
        awaitCompleted(runtime, 2)

        val values = mutableListOf<String>()
        assertEquals(1, runtime.drain(1) { completion ->
            values += (completion.outcome as TerrainBuildOutcome.Success).value
        })
        val afterOne = runtime.snapshot()
        assertEquals(1, afterOne.completionDepth)
        assertEquals(1, afterOne.outstanding)
        assertEquals(2L, afterOne.requested)
        assertEquals(2L, afterOne.admitted)
        assertEquals(2L, afterOne.started)
        assertEquals(2L, afterOne.completed)
        assertEquals(0L, afterOne.failed)
        assertEquals(0L, afterOne.cancelled)

        assertEquals(1, runtime.drain(1) { completion ->
            values += (completion.outcome as TerrainBuildOutcome.Success).value
        })
        assertEquals(listOf("one", "two"), values)
        assertEquals(0, runtime.snapshot().outstanding)
        runtime.close()
    }

    @Test
    fun `saturation rejects and cancels excess work`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = runtime(queueCapacity = 1)
        val tenant = TerrainSchedulerTenantId("test:near")
        assertNotNull(runtime.submit(tenant, identity(TerrainDomain.NEAR, 1L)) { _, _ ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "running"
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertNotNull(runtime.submit(tenant, identity(TerrainDomain.NEAR, 2L)) { _, _ -> "queued" })
        val rejectedToken = TerrainCancellationToken()
        assertNull(runtime.submit(
            tenant,
            identity(TerrainDomain.NEAR, 3L),
            cancellation = rejectedToken,
        ) { _, _ -> "rejected" })
        assertTrue(rejectedToken.isCancelled)

        val saturated = runtime.snapshot()
        assertEquals(1, saturated.queueDepth)
        assertEquals(2, saturated.outstanding)
        assertEquals(3L, saturated.requested)
        assertEquals(2L, saturated.admitted)
        assertEquals(1L, saturated.rejected)
        release.countDown()
        assertEquals(listOf("running", "queued"), awaitValues(runtime, 2))
        runtime.close()
    }

    @Test
    fun `active cancellation token cannot be reused or cancelled by rejection`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = runtime(queueCapacity = 1)
        val tenant = TerrainSchedulerTenantId("test:near")
        val token = TerrainCancellationToken()
        assertNotNull(runtime.submit(
            tenant,
            identity(TerrainDomain.NEAR, 1L),
            cancellation = token,
        ) { _, _ ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "running"
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))

        assertFailsWith<IllegalStateException> {
            runtime.submit(
                tenant,
                identity(TerrainDomain.NEAR, 2L),
                cancellation = token,
            ) { _, _ -> "duplicate" }
        }
        assertFalse(token.isCancelled)
        release.countDown()
        assertEquals(listOf("running"), awaitValues(runtime, 1))
        runtime.close()
    }

    private fun runtime(queueCapacity: Int, nearBurstLimit: Int = 3) =
        TerrainBuildRuntime<AutoCloseable, String>(
            workerCount = 1,
            queueCapacity = queueCapacity,
            nearBurstLimit = nearBurstLimit,
            contextFactory = { AutoCloseable {} },
        )

    private fun identity(domain: TerrainDomain, revision: Long) = TerrainBuildIdentity(
        page = TerrainPageKey(domain, 0, revision, 0L, 0L, 1L),
        requestRevision = revision,
        capturedModelRevision = revision,
        providerGeneration = 1L,
        layoutGeneration = 1L,
        materialGeneration = 1L,
        coverageGeneration = 0L,
        prioritySequence = revision,
    )

    private fun awaitValues(
        runtime: TerrainBuildRuntime<AutoCloseable, String>,
        count: Int,
    ): List<String> {
        awaitCompleted(runtime, count)
        val values = mutableListOf<String>()
        runtime.drain(count) { completion ->
            values += (completion.outcome as TerrainBuildOutcome.Success).value
        }
        return values
    }

    private fun awaitCompleted(runtime: TerrainBuildRuntime<AutoCloseable, String>, count: Int) {
        repeat(500) {
            if (runtime.snapshot().completed >= count) return
            Thread.sleep(10L)
        }
        error("Timed out waiting for $count terrain completions")
    }
}
