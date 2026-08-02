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

import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildCompletion
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildOutcome
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildRuntime
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSchedulerTenantId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainBuildRuntimeTest {
    private val tenant = TerrainSchedulerTenantId("test:near")

    @Test
    fun `cancelled work cannot complete successfully`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = TerrainBuildRuntime<Context, String>(
            workerCount = 1,
            queueCapacity = 1,
            contextFactory = ::Context,
        )
        val identity = identity(1L)

        val cancellation = runtime.submit(tenant, identity) { _, _ ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "discard me"
        }
        assertNotNull(cancellation)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        cancellation.cancel()
        release.countDown()

        val completion = awaitCompletion(runtime)
        assertEquals(identity, completion.identity)
        val outcome = assertIs<TerrainBuildOutcome.Cancelled<String>>(completion.outcome)
        assertEquals("discard me", outcome.completedValue)
        runtime.close()
    }

    @Test
    fun `worker contexts are reused and closed`() {
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val runtime = TerrainBuildRuntime<Context, Int>(
            workerCount = 1,
            queueCapacity = 2,
            contextFactory = { Context(created, closed) },
        )

        assertNotNull(runtime.submit(tenant, identity(1L)) { context, _ -> context.calls.incrementAndGet() })
        assertNotNull(runtime.submit(tenant, identity(2L)) { context, _ -> context.calls.incrementAndGet() })
        val completions = awaitCompletions(runtime, 2)

        assertEquals(1, created.get())
        assertEquals(listOf(1, 2), completions.map {
            assertIs<TerrainBuildOutcome.Success<Int>>(it.outcome).value
        })
        runtime.close()
        assertEquals(1, closed.get())
    }

    @Test
    fun `close cancels queued work and rejects new work`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = TerrainBuildRuntime<Context, Int>(
            workerCount = 1,
            queueCapacity = 2,
            contextFactory = ::Context,
        )

        val running = assertNotNull(runtime.submit(tenant, identity(1L)) { _, cancellation ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            assertTrue(cancellation.isCancelled)
            1
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertNotNull(runtime.submit(tenant, identity(2L)) { _, _ -> 2 })
        assertNotNull(runtime.submit(tenant, identity(3L)) { _, _ -> 3 })
        assertNull(runtime.submit(tenant, identity(4L)) { _, _ -> 4 })

        val closer = Thread(runtime::close)
        closer.start()
        val cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (!running.isCancelled && System.nanoTime() < cancellationDeadline) {
            Thread.sleep(1L)
        }
        assertTrue(running.isCancelled)
        release.countDown()
        closer.join(5_000L)
        assertFalse(closer.isAlive)

        val completions = mutableListOf<TerrainBuildCompletion<Int>>()
        runtime.drain(Int.MAX_VALUE, completions::add)
        assertEquals(3, completions.size)
        assertTrue(completions.all { it.outcome is TerrainBuildOutcome.Cancelled })
        assertNull(runtime.submit(tenant, identity(5L)) { _, _ -> 5 })
    }

    @Test
    fun `close reports worker context cleanup failure`() {
        val runtime = TerrainBuildRuntime<AutoCloseable, Unit>(
            workerCount = 1,
            queueCapacity = 1,
            contextFactory = {
                AutoCloseable { throw IllegalStateException("context cleanup") }
            },
        )

        val failure = assertFailsWith<IllegalStateException> { runtime.close() }
        assertEquals("context cleanup", failure.message)
    }

    @Test
    fun `interrupted close still joins workers and closes their contexts`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contextClosed = CountDownLatch(1)
        val runtime = TerrainBuildRuntime<AutoCloseable, Unit>(
            workerCount = 1,
            queueCapacity = 1,
            contextFactory = { AutoCloseable(contextClosed::countDown) },
        )
        assertNotNull(runtime.submit(tenant, identity(1L)) { _, _ ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val closeFailure = AtomicReference<Throwable?>()
        val closer = Thread {
            try {
                runtime.close()
            } catch (error: Throwable) {
                closeFailure.set(error)
            }
        }
        closer.start()
        while (closer.state != Thread.State.WAITING) {
            assertTrue(closer.isAlive)
            Thread.yield()
        }
        closer.interrupt()
        release.countDown()
        closer.join(5_000L)

        assertFalse(closer.isAlive)
        assertTrue(closer.isInterrupted)
        assertIs<InterruptedException>(closeFailure.get())
        assertEquals(0L, contextClosed.count)
    }

    private fun <T> awaitCompletion(runtime: TerrainBuildRuntime<Context, T>): TerrainBuildCompletion<T> {
        return awaitCompletions(runtime, 1).single()
    }

    private fun <T> awaitCompletions(
        runtime: TerrainBuildRuntime<Context, T>,
        count: Int,
    ): List<TerrainBuildCompletion<T>> {
        val completions = mutableListOf<TerrainBuildCompletion<T>>()
        repeat(500) {
            runtime.drain(Int.MAX_VALUE, completions::add)
            if (completions.size >= count) return completions
            Thread.sleep(10L)
        }
        error("Timed out waiting for $count terrain build completions")
    }

    private fun identity(revision: Long) = TerrainBuildIdentity(
        page = TerrainPageKey(TerrainDomain.NEAR, 0, 1L, 2L, 3L, 7L),
        requestRevision = revision,
        capturedModelRevision = revision,
        providerGeneration = 4L,
        layoutGeneration = 5L,
        materialGeneration = 6L,
        coverageGeneration = 0L,
        prioritySequence = revision,
    )

    private class Context(
        created: AtomicInteger? = null,
        private val closed: AtomicInteger? = null,
    ) : AutoCloseable {
        val calls = AtomicInteger()

        init {
            created?.incrementAndGet()
        }

        override fun close() {
            closed?.incrementAndGet()
        }
    }
}
