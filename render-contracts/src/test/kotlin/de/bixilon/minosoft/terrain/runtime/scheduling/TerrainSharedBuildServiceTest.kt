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
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentityMismatch
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

class TerrainSharedBuildServiceTest {
    @Test
    fun `near and distant barrier rejects old-world completions before publication`() {
        val service = TerrainSharedBuildService(workerCount = 2, queueCapacity = 4)
        val near = service.register<AutoCloseable, String>(
            ownerId = "test:near-world",
            tenant = TerrainSchedulerTenantId("test:near-world"),
            contextFactory = { AutoCloseable {} },
            disposer = {},
        )
        val distant = service.register<AutoCloseable, String>(
            ownerId = "test:distant-world",
            tenant = TerrainSchedulerTenantId("test:distant-world"),
            contextFactory = { AutoCloseable {} },
            disposer = {},
        )
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)

        fun build(value: String): (AutoCloseable, TerrainCancellationToken) -> String = { _, _ ->
            started.countDown()
            check(release.await(5L, TimeUnit.SECONDS)) { "Timed out releasing cross-provider build" }
            value
        }
        assertNotNull(near.submit(identity(TerrainDomain.NEAR, 1L), build = build("near")))
        assertNotNull(distant.submit(identity(TerrainDomain.DISTANT, 2L), build = build("distant")))
        assertTrue(started.await(5L, TimeUnit.SECONDS))

        release.countDown()
        awaitCompleted(near, 2)

        val mismatches = mutableListOf<TerrainBuildIdentityMismatch>()
        val published = mutableListOf<String>()
        fun accept(completion: TerrainBuildCompletion<String>) {
            val expected = completion.identity.copy(
                page = completion.identity.page.copy(worldEpoch = 2L),
            )
            val mismatch = completion.identity.mismatch(expected)
            if (mismatch == null) {
                published += (completion.outcome as TerrainBuildOutcome.Success).value
            } else {
                mismatches += mismatch
            }
        }
        assertEquals(1, near.drain(2, ::accept))
        assertEquals(1, distant.drain(2, ::accept))
        assertTrue(published.isEmpty())
        assertEquals(
            listOf(TerrainBuildIdentityMismatch.WORLD_EPOCH, TerrainBuildIdentityMismatch.WORLD_EPOCH),
            mismatches,
        )
        assertEquals(0, service.snapshot().runtime.outstanding)

        near.close()
        distant.close()
        service.close()
    }

    @Test
    fun `routed tenant completions retain process capacity until owner drain`() {
        val service = TerrainSharedBuildService(workerCount = 1, queueCapacity = 1)
        val near = service.register<AutoCloseable, String>(
            ownerId = "test:near-owner",
            tenant = TerrainSchedulerTenantId("test:near"),
            contextFactory = { AutoCloseable {} },
            disposer = {},
        )
        val distant = service.register<AutoCloseable, String>(
            ownerId = "test:distant-owner",
            tenant = TerrainSchedulerTenantId("test:distant"),
            contextFactory = { AutoCloseable {} },
            disposer = {},
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        assertNotNull(distant.submit(identity(TerrainDomain.DISTANT, 1L)) { _, _ -> "distant" })
        awaitCompleted(distant, 1)
        assertEquals(0, near.drain(1) {})
        assertEquals(1, service.snapshot().runtime.routedCompletionDepth)

        assertNotNull(near.submit(identity(TerrainDomain.NEAR, 2L)) { _, _ ->
            started.countDown()
            check(release.await(5L, TimeUnit.SECONDS)) { "Timed out releasing near build" }
            "near"
        })
        assertTrue(started.await(5L, TimeUnit.SECONDS))
        assertNull(near.submit(identity(TerrainDomain.NEAR, 3L)) { _, _ -> "rejected" })

        release.countDown()
        awaitCompleted(near, 2)
        assertEquals(1, near.drain(1) {})
        assertEquals(1, distant.drain(1) {})
        assertEquals(0, service.snapshot().runtime.outstanding)
        near.close()
        distant.close()
        service.close()
    }

    @Test
    fun `typed tenant mailboxes do not publish on another tenant drain`() {
        val service = TerrainSharedBuildService(workerCount = 1, queueCapacity = 4)
        val closedContexts = AtomicInteger()
        val near = service.register<AutoCloseable, String>(
            ownerId = "test:near-owner",
            tenant = TerrainSchedulerTenantId("test:near"),
            contextFactory = { AutoCloseable { closedContexts.incrementAndGet() } },
            disposer = { _ -> },
        )
        val distant = service.register<AutoCloseable, Int>(
            ownerId = "test:distant-owner",
            tenant = TerrainSchedulerTenantId("test:distant"),
            contextFactory = { AutoCloseable { closedContexts.incrementAndGet() } },
            disposer = { _ -> },
        )

        assertNotNull(near.submit(identity(TerrainDomain.NEAR, 1L)) { _, _ -> "near" })
        assertNotNull(distant.submit(identity(TerrainDomain.DISTANT, 2L)) { _, _ -> 42 })
        awaitCompleted(near, 2)

        val nearValues = mutableListOf<String>()
        val distantValues = mutableListOf<Int>()
        assertEquals(1, near.drain(2) { completion ->
            nearValues += (completion.outcome as TerrainBuildOutcome.Success).value
        })
        assertEquals(listOf("near"), nearValues)
        assertTrue(distantValues.isEmpty())
        val routedSnapshot = service.snapshot()
        assertEquals(1, routedSnapshot.runtime.outstanding)
        assertEquals(1, routedSnapshot.runtime.completionDepth)
        assertEquals(1, routedSnapshot.runtime.routedCompletionDepth)
        assertEquals(1L, routedSnapshot.runtime.outstandingEstimatedCpuNanos)
        assertEquals(1L, routedSnapshot.runtime.outstandingEstimatedOutputBytes)
        assertEquals(
            listOf(
                "test:distant" to (1 to 1),
                "test:near" to (0 to 0),
            ),
            routedSnapshot.tenants.map {
                it.tenant.value to (it.outstanding to it.completionDepth)
            },
        )
        assertEquals(1, distant.drain(2) { completion ->
            distantValues += (completion.outcome as TerrainBuildOutcome.Success).value
        })
        assertEquals(listOf(42), distantValues)

        near.close()
        val replacementNear = service.register<AutoCloseable, String>(
            ownerId = "test:replacement-near-owner",
            tenant = TerrainSchedulerTenantId("test:near"),
            contextFactory = { AutoCloseable {} },
            disposer = { _ -> },
        )
        replacementNear.close()
        assertNotNull(distant.submit(identity(TerrainDomain.DISTANT, 3L)) { _, _ -> 43 })
        awaitCompleted(distant, 3)
        assertEquals(1, distant.drain(2) { completion ->
            distantValues += (completion.outcome as TerrainBuildOutcome.Success).value
        })
        assertEquals(listOf(42, 43), distantValues)
        assertEquals(0L, service.snapshot().runtime.outstandingEstimatedOutputBytes)
        distant.close()
        assertEquals(2, closedContexts.get())
        service.close()
    }

    @Test
    fun `closing tenant disposes retained completion without stopping service`() {
        val service = TerrainSharedBuildService(workerCount = 1, queueCapacity = 2)
        val disposed = mutableListOf<String>()
        val first = service.register<AutoCloseable, String>(
            ownerId = "test:first-owner",
            tenant = TerrainSchedulerTenantId("test:first"),
            contextFactory = { AutoCloseable {} },
            disposer = disposed::add,
        )
        val second = service.register<AutoCloseable, String>(
            ownerId = "test:second-owner",
            tenant = TerrainSchedulerTenantId("test:second"),
            contextFactory = { AutoCloseable {} },
            disposer = { _ -> },
        )

        assertNotNull(first.submit(identity(TerrainDomain.NEAR, 1L)) { _, _ -> "retained" })
        awaitCompleted(first, 1)
        first.close()
        assertEquals(listOf("retained"), disposed)

        assertNotNull(second.submit(identity(TerrainDomain.DISTANT, 2L)) { _, _ -> "alive" })
        awaitCompleted(second, 2)
        val values = mutableListOf<String>()
        second.drain(1) { completion ->
            values += (completion.outcome as TerrainBuildOutcome.Success).value
        }
        assertEquals(listOf("alive"), values)
        second.close()
        service.close()
    }

    @Test
    fun `tenant close drains every completion when a disposer fails`() {
        val service = TerrainSharedBuildService(workerCount = 1, queueCapacity = 3)
        val disposed = AtomicInteger()
        val tenant = TerrainSchedulerTenantId("test:failing-disposer")
        val lease = service.register<AutoCloseable, String>(
            ownerId = "test:failing-disposer-owner",
            tenant = tenant,
            contextFactory = { AutoCloseable {} },
            disposer = {
                if (disposed.incrementAndGet() == 1) throw IllegalStateException("first disposal failed")
            },
        )
        assertNotNull(lease.submit(identity(TerrainDomain.NEAR, 1L)) { _, _ -> "first" })
        assertNotNull(lease.submit(identity(TerrainDomain.NEAR, 2L)) { _, _ -> "second" })
        awaitCompleted(lease, 2)

        assertThrows<IllegalStateException> { lease.close() }
        assertEquals(2, disposed.get())

        service.register<AutoCloseable, String>(
            ownerId = "test:replacement-owner",
            tenant = tenant,
            contextFactory = { AutoCloseable {} },
            disposer = {},
        ).close()
        service.close()
    }

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

    private fun awaitCompleted(
        lease: TerrainSharedBuildService.Lease<*, *>,
        count: Long,
    ) {
        repeat(500) {
            if (lease.snapshot().completed >= count) return
            Thread.sleep(10L)
        }
        error("Timed out waiting for $count shared terrain completions")
    }
}
