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

import de.bixilon.minosoft.data.world.positions.SectionPosition
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TerrainBuildIdentity(
    val position: SectionPosition,
    val requestRevision: Long,
    val modelRevision: Long,
    val backendGeneration: Long,
    val materialGeneration: String?,
) {
    init {
        require(requestRevision >= 0L) { "Terrain build request revision must not be negative" }
        require(modelRevision >= 0L) { "Terrain model revision must not be negative" }
        require(backendGeneration >= 0L) { "Terrain backend generation must not be negative" }
    }
}

class TerrainCancellationToken internal constructor() {
    private val cancelled = AtomicBoolean()

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)
}

sealed class TerrainBuildOutcome<out T> {
    data class Success<T>(val value: T) : TerrainBuildOutcome<T>()
    data class Failure(val error: Throwable) : TerrainBuildOutcome<Nothing>()
    data class Cancelled<T>(val completedValue: T? = null) : TerrainBuildOutcome<T>()
}

data class TerrainBuildCompletion<T>(
    val identity: TerrainBuildIdentity,
    val outcome: TerrainBuildOutcome<T>,
)

/**
 * A bounded, terrain-owned worker runtime. The outstanding bound covers queued,
 * running, and completed-but-undrained work. Cancellation is logical:
 * cancelling a build never interrupts a worker thread that may already have
 * advanced to another job. Each worker owns one reusable context and closes it
 * on shutdown.
 */
class TerrainBuildRuntime<C : AutoCloseable, T>(
    workerCount: Int,
    queueCapacity: Int,
    threadNamePrefix: String = "Terrain build",
    private val contextFactory: () -> C,
) : AutoCloseable {
    private data class Job<C, T>(
        val identity: TerrainBuildIdentity,
        val cancellation: TerrainCancellationToken,
        val build: (C, TerrainCancellationToken) -> T,
    )

    private data class QueueEntry<C, T>(
        val job: Job<C, T>?,
    )

    private val closed = AtomicBoolean()
    private val lifecycleLock = Any()
    private val queue: ArrayBlockingQueue<QueueEntry<C, T>>
    private val maximumOutstanding: Int
    private val outstanding = AtomicInteger()
    private val active = ConcurrentHashMap<TerrainCancellationToken, TerrainBuildIdentity>()
    private val completions = ConcurrentLinkedQueue<TerrainBuildCompletion<T>>()
    private val workerFailures = ConcurrentLinkedQueue<Throwable>()
    private val workers: List<Thread>

    init {
        require(workerCount > 0) { "Terrain build runtime requires at least one worker" }
        require(queueCapacity >= workerCount) {
            "Terrain build queue capacity must be at least the worker count"
        }
        require(threadNamePrefix.isNotBlank()) { "Terrain build thread name prefix must not be blank" }

        queue = ArrayBlockingQueue(queueCapacity)
        maximumOutstanding = Math.addExact(queueCapacity, workerCount)
        workers = List(workerCount) { index ->
            Thread({ workLoop() }, "$threadNamePrefix ${index + 1}").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun submit(
        identity: TerrainBuildIdentity,
        cancellation: TerrainCancellationToken = TerrainCancellationToken(),
        build: (C, TerrainCancellationToken) -> T,
    ): TerrainCancellationToken? {
        synchronized(lifecycleLock) {
            if (closed.get()) return null
            if (outstanding.get() >= maximumOutstanding) return null

            val job = Job(identity, cancellation, build)
            outstanding.incrementAndGet()
            active[cancellation] = identity
            if (!queue.offer(QueueEntry(job))) {
                active.remove(cancellation)
                outstanding.decrementAndGet()
                cancellation.cancel()
                return null
            }
            return cancellation
        }
    }

    fun cancelIf(predicate: (TerrainBuildIdentity) -> Boolean) {
        for ((cancellation, identity) in active) {
            if (predicate(identity)) cancellation.cancel()
        }
    }

    fun cancelAll() {
        for (cancellation in active.keys) cancellation.cancel()
    }

    fun drain(consumer: (TerrainBuildCompletion<T>) -> Unit) {
        while (true) {
            val completion = completions.poll() ?: return
            try {
                consumer(completion)
            } finally {
                outstanding.decrementAndGet()
            }
        }
    }

    private fun workLoop() {
        var context: C? = null
        try {
            context = contextFactory()
            while (true) {
                val job = queue.take().job ?: return
                execute(context, job)
            }
        } catch (error: Throwable) {
            workerFailures += error
        } finally {
            if (context != null) {
                try {
                    context.close()
                } catch (error: Throwable) {
                    workerFailures += error
                }
            }
        }
    }

    private fun execute(context: C, job: Job<C, T>) {
        val outcome = if (job.cancellation.isCancelled) {
            TerrainBuildOutcome.Cancelled()
        } else {
            try {
                val value = job.build(context, job.cancellation)
                if (job.cancellation.isCancelled) {
                    TerrainBuildOutcome.Cancelled(value)
                } else {
                    TerrainBuildOutcome.Success(value)
                }
            } catch (error: Throwable) {
                if (job.cancellation.isCancelled) {
                    TerrainBuildOutcome.Cancelled()
                } else {
                    TerrainBuildOutcome.Failure(error)
                }
            }
        }
        active.remove(job.cancellation)
        completions += TerrainBuildCompletion(job.identity, outcome)
    }

    override fun close() {
        val queued = ArrayList<QueueEntry<C, T>>(queue.size)
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            cancelAll()
            queue.drainTo(queued)
        }
        for (entry in queued) {
            val job = entry.job ?: continue
            active.remove(job.cancellation)
            job.cancellation.cancel()
            completions += TerrainBuildCompletion(job.identity, TerrainBuildOutcome.Cancelled())
        }

        repeat(workers.size) {
            check(queue.offer(QueueEntry(null))) {
                "Terrain build shutdown queue did not have room for every worker"
            }
        }
        var failure: Throwable? = null
        for (worker in workers) {
            while (worker.isAlive) {
                try {
                    worker.join()
                } catch (error: InterruptedException) {
                    if (failure == null) {
                        failure = error
                    } else {
                        failure.addSuppressed(error)
                    }
                }
            }
        }
        while (true) {
            val workerFailure = workerFailures.poll() ?: break
            if (failure == null) {
                failure = workerFailure
            } else {
                failure.addSuppressed(workerFailure)
            }
        }
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (failure != null) throw failure
    }
}
