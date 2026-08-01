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
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class TerrainSchedulerTenantId(val value: String) {
    init {
        require(value.isNotBlank()) { "Terrain scheduler tenant ID must not be blank" }
    }
}

enum class TerrainBuildUrgency {
    IMMEDIATE,
    NEXT_FRAME,
    DEFERRED,
}

class TerrainCancellationToken {
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
    val tenant: TerrainSchedulerTenantId,
    val identity: TerrainBuildIdentity,
    val estimate: TerrainBuildEstimate,
    val outcome: TerrainBuildOutcome<T>,
)

data class TerrainBuildEstimate(
    val cpuNanos: Long,
    val outputBytes: Long,
) {
    init {
        require(cpuNanos > 0L) { "Terrain build CPU estimate must be positive" }
        require(outputBytes > 0L) { "Terrain build output estimate must be positive" }
    }

    companion object {
        val MINIMUM = TerrainBuildEstimate(cpuNanos = 1L, outputBytes = 1L)
    }
}

data class TerrainBuildRuntimeSnapshot(
    val queueDepth: Int,
    val completionDepth: Int,
    val routedCompletionDepth: Int,
    val outstanding: Int,
    val outstandingEstimatedCpuNanos: Long,
    val outstandingEstimatedOutputBytes: Long,
    val active: Int,
    val queueHighWater: Int,
    val completionHighWater: Int,
    val outstandingHighWater: Int,
    val estimatedCpuHighWaterNanos: Long,
    val estimatedOutputHighWaterBytes: Long,
    val estimatedCpuBudgetNanos: Long,
    val estimatedOutputBudgetBytes: Long,
    val requested: Long,
    val admitted: Long,
    val rejected: Long,
    val estimatedCpuRejected: Long,
    val estimatedOutputRejected: Long,
    val started: Long,
    val completed: Long,
    val cancelled: Long,
    val failed: Long,
)

/**
 * A bounded terrain CPU scheduler and completion mailbox. The outstanding bound
 * covers queued, running, and completed-but-undrained work. Jobs are ordered by
 * urgency, rotate between tenants at equal urgency, prefer near work, and
 * reserve one slot for distant work after [nearBurstLimit] consecutive near
 * starts. Cancellation is logical and never interrupts a worker that may have
 * advanced to another job. Each worker owns one reusable context.
 */
class TerrainBuildRuntime<C : AutoCloseable, T>(
    workerCount: Int,
    private val queueCapacity: Int,
    private val nearBurstLimit: Int = 3,
    private val estimatedCpuBudgetNanos: Long = Long.MAX_VALUE,
    private val estimatedOutputBudgetBytes: Long = Long.MAX_VALUE,
    threadNamePrefix: String = "Terrain build",
    private val contextFactory: () -> C,
) : AutoCloseable {
    private data class Job<C, T>(
        val tenant: TerrainSchedulerTenantId,
        val identity: TerrainBuildIdentity,
        val urgency: TerrainBuildUrgency,
        val estimate: TerrainBuildEstimate,
        val cancellation: TerrainCancellationToken,
        val build: (C, TerrainCancellationToken) -> T,
    )

    private val closed = AtomicBoolean()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lifecycleLock = Object()
    private val queue = ArrayList<Job<C, T>>(queueCapacity)
    private val maximumOutstanding: Int
    private val outstanding = AtomicInteger()
    private val completionDepth = AtomicInteger()
    private val routedCompletionDepth = AtomicInteger()
    private var outstandingEstimatedCpuNanos = 0L
    private var outstandingEstimatedOutputBytes = 0L
    private data class ActiveBuild(
        val tenant: TerrainSchedulerTenantId,
        val identity: TerrainBuildIdentity,
    )

    private val active = ConcurrentHashMap<TerrainCancellationToken, ActiveBuild>()
    private val completions: ConcurrentLinkedQueue<TerrainBuildCompletion<T>> = ConcurrentLinkedQueue()
    private val workerFailures = ConcurrentLinkedQueue<Throwable>()
    private val workers: List<Thread>
    private val lastTenant = EnumMap<TerrainDomain, TerrainSchedulerTenantId>(TerrainDomain::class.java)
    private var consecutiveNearStarts = 0

    private val queueHighWater = AtomicInteger()
    private val completionHighWater = AtomicInteger()
    private val outstandingHighWater = AtomicInteger()
    private var estimatedCpuHighWaterNanos = 0L
    private var estimatedOutputHighWaterBytes = 0L
    private val requested = AtomicLong()
    private val admitted = AtomicLong()
    private val rejected = AtomicLong()
    private val estimatedCpuRejected = AtomicLong()
    private val estimatedOutputRejected = AtomicLong()
    private val started = AtomicLong()
    private val completed = AtomicLong()
    private val cancelled = AtomicLong()
    private val failed = AtomicLong()

    init {
        require(workerCount > 0) { "Terrain build runtime requires at least one worker" }
        require(queueCapacity >= workerCount) {
            "Terrain build queue capacity must be at least the worker count"
        }
        require(nearBurstLimit > 0) { "Terrain near burst limit must be positive" }
        require(estimatedCpuBudgetNanos > 0L) { "Terrain estimated CPU budget must be positive" }
        require(estimatedOutputBudgetBytes > 0L) { "Terrain estimated output budget must be positive" }
        require(threadNamePrefix.isNotBlank()) { "Terrain build thread name prefix must not be blank" }

        maximumOutstanding = Math.addExact(queueCapacity, workerCount)
        workers = List(workerCount) { index ->
            Thread({ workLoop() }, "$threadNamePrefix ${index + 1}").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun submit(
        tenant: TerrainSchedulerTenantId,
        identity: TerrainBuildIdentity,
        urgency: TerrainBuildUrgency = TerrainBuildUrgency.NEXT_FRAME,
        estimate: TerrainBuildEstimate = TerrainBuildEstimate.MINIMUM,
        cancellation: TerrainCancellationToken = TerrainCancellationToken(),
        build: (C, TerrainCancellationToken) -> T,
    ): TerrainCancellationToken? {
        require(!cancellation.isCancelled) { "Terrain build cancellation token is already cancelled" }
        incrementSaturating(requested)
        synchronized(lifecycleLock) {
            check(!active.containsKey(cancellation)) { "Terrain build cancellation token is already active" }
            val cpuRejected = exceedsBudget(
                outstandingEstimatedCpuNanos,
                estimate.cpuNanos,
                estimatedCpuBudgetNanos,
            )
            val outputRejected = exceedsBudget(
                outstandingEstimatedOutputBytes,
                estimate.outputBytes,
                estimatedOutputBudgetBytes,
            )
            if (closed.get() || outstanding.get() >= maximumOutstanding || queue.size >= queueCapacity ||
                cpuRejected || outputRejected
            ) {
                incrementSaturating(rejected)
                if (cpuRejected) incrementSaturating(estimatedCpuRejected)
                if (outputRejected) incrementSaturating(estimatedOutputRejected)
                cancellation.cancel()
                return null
            }

            val job = Job(tenant, identity, urgency, estimate, cancellation, build)
            active[cancellation] = ActiveBuild(tenant, identity)
            queue += job
            outstandingEstimatedCpuNanos = Math.addExact(outstandingEstimatedCpuNanos, estimate.cpuNanos)
            outstandingEstimatedOutputBytes = Math.addExact(
                outstandingEstimatedOutputBytes,
                estimate.outputBytes,
            )
            estimatedCpuHighWaterNanos = maxOf(estimatedCpuHighWaterNanos, outstandingEstimatedCpuNanos)
            estimatedOutputHighWaterBytes = maxOf(
                estimatedOutputHighWaterBytes,
                outstandingEstimatedOutputBytes,
            )
            updateHighWater(queueHighWater, queue.size)
            updateHighWater(outstandingHighWater, outstanding.incrementAndGet())
            incrementSaturating(admitted)
            lifecycleLock.notify()
            return cancellation
        }
    }

    fun cancelIf(predicate: (TerrainBuildIdentity) -> Boolean) {
        for ((cancellation, build) in active) {
            if (predicate(build.identity)) cancellation.cancel()
        }
    }

    fun cancelTenant(tenant: TerrainSchedulerTenantId) {
        for ((cancellation, build) in active) {
            if (build.tenant == tenant) cancellation.cancel()
        }
    }

    fun cancelAll() {
        for (cancellation in active.keys) cancellation.cancel()
    }

    fun drain(maxCompletions: Int, consumer: (TerrainBuildCompletion<T>) -> Unit): Int {
        return drainInternal(maxCompletions, route = false, consumer)
    }

    internal fun route(maxCompletions: Int, consumer: (TerrainBuildCompletion<T>) -> Unit): Int {
        return drainInternal(maxCompletions, route = true, consumer)
    }

    internal fun releaseRouted(estimate: TerrainBuildEstimate) {
        decrementChecked(routedCompletionDepth, 1, "routed completion")
        releaseOutstanding(estimate)
    }

    private fun drainInternal(
        maxCompletions: Int,
        route: Boolean,
        consumer: (TerrainBuildCompletion<T>) -> Unit,
    ): Int {
        require(maxCompletions > 0) { "Terrain completion drain limit must be positive" }
        var drained = 0
        while (drained < maxCompletions) {
            val completion = completions.poll() ?: break
            var delivered = false
            try {
                consumer(completion)
                delivered = true
            } finally {
                completionDepth.decrementAndGet()
                if (route && delivered) {
                    routedCompletionDepth.incrementAndGet()
                } else {
                    releaseOutstanding(completion.estimate)
                }
                drained++
            }
        }
        return drained
    }

    fun snapshot(): TerrainBuildRuntimeSnapshot = synchronized(lifecycleLock) {
        TerrainBuildRuntimeSnapshot(
            queueDepth = queue.size,
            completionDepth = Math.addExact(completionDepth.get(), routedCompletionDepth.get()),
            routedCompletionDepth = routedCompletionDepth.get(),
            outstanding = outstanding.get(),
            outstandingEstimatedCpuNanos = outstandingEstimatedCpuNanos,
            outstandingEstimatedOutputBytes = outstandingEstimatedOutputBytes,
            active = active.size,
            queueHighWater = queueHighWater.get(),
            completionHighWater = completionHighWater.get(),
            outstandingHighWater = outstandingHighWater.get(),
            estimatedCpuHighWaterNanos = estimatedCpuHighWaterNanos,
            estimatedOutputHighWaterBytes = estimatedOutputHighWaterBytes,
            estimatedCpuBudgetNanos = estimatedCpuBudgetNanos,
            estimatedOutputBudgetBytes = estimatedOutputBudgetBytes,
            requested = requested.get(),
            admitted = admitted.get(),
            rejected = rejected.get(),
            estimatedCpuRejected = estimatedCpuRejected.get(),
            estimatedOutputRejected = estimatedOutputRejected.get(),
            started = started.get(),
            completed = completed.get(),
            cancelled = cancelled.get(),
            failed = failed.get(),
        )
    }

    private fun workLoop() {
        var context: C? = null
        try {
            context = contextFactory()
            while (true) {
                val job = takeJob() ?: return
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

    private fun takeJob(): Job<C, T>? = synchronized(lifecycleLock) {
        while (queue.isEmpty() && !closed.get()) lifecycleLock.wait()
        if (queue.isEmpty()) return@synchronized null

        val index = selectJobIndex()
        val job = queue.removeAt(index)
        val domain = job.identity.page.domain
        lastTenant[domain] = job.tenant
        if (domain == TerrainDomain.NEAR) {
            consecutiveNearStarts = minOf(nearBurstLimit, consecutiveNearStarts + 1)
        } else {
            consecutiveNearStarts = 0
        }
        incrementSaturating(started)
        job
    }

    private fun selectJobIndex(): Int {
        val distantReserved = consecutiveNearStarts >= nearBurstLimit &&
            queue.any { it.identity.page.domain == TerrainDomain.DISTANT }
        val domain = if (distantReserved) {
            TerrainDomain.DISTANT
        } else {
            val bestUrgency = queue.minOf { it.urgency.ordinal }
            if (queue.any { it.urgency.ordinal == bestUrgency && it.identity.page.domain == TerrainDomain.NEAR }) {
                TerrainDomain.NEAR
            } else {
                TerrainDomain.DISTANT
            }
        }
        val urgency = queue.asSequence()
            .filter { it.identity.page.domain == domain }
            .minOf { it.urgency.ordinal }
        val tenants = queue.asSequence()
            .filter { it.identity.page.domain == domain && it.urgency.ordinal == urgency }
            .map(Job<C, T>::tenant)
            .distinct()
            .toList()
        val previous = lastTenant[domain]
        val tenant = if (previous == null || previous !in tenants) {
            tenants.first()
        } else {
            tenants[(tenants.indexOf(previous) + 1) % tenants.size]
        }
        return queue.indexOfFirst {
            it.identity.page.domain == domain && it.urgency.ordinal == urgency && it.tenant == tenant
        }.also { check(it >= 0) { "Terrain scheduler did not select a queued job" } }
    }

    private fun execute(context: C, job: Job<C, T>) {
        val outcome = if (job.cancellation.isCancelled) {
            incrementSaturating(cancelled)
            TerrainBuildOutcome.Cancelled()
        } else {
            try {
                val value = job.build(context, job.cancellation)
                if (job.cancellation.isCancelled) {
                    incrementSaturating(cancelled)
                    TerrainBuildOutcome.Cancelled(value)
                } else {
                    TerrainBuildOutcome.Success(value)
                }
            } catch (error: Throwable) {
                if (job.cancellation.isCancelled) {
                    incrementSaturating(cancelled)
                    TerrainBuildOutcome.Cancelled()
                } else {
                    incrementSaturating(failed)
                    TerrainBuildOutcome.Failure(error)
                }
            }
        }
        active.remove(job.cancellation)
        val depth = completionDepth.incrementAndGet()
        completions += TerrainBuildCompletion(job.tenant, job.identity, job.estimate, outcome)
        incrementSaturating(completed)
        updateHighWater(completionHighWater, Math.addExact(depth, routedCompletionDepth.get()))
    }

    override fun close() {
        val queued: List<Job<C, T>>
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            cancelAll()
            queued = queue.toList()
            queue.clear()
            lifecycleLock.notifyAll()
        }
        for (job in queued) {
            active.remove(job.cancellation)
            job.cancellation.cancel()
            completionDepth.incrementAndGet()
            completions += TerrainBuildCompletion(
                job.tenant,
                job.identity,
                job.estimate,
                TerrainBuildOutcome.Cancelled(),
            )
            incrementSaturating(cancelled)
            incrementSaturating(completed)
        }
        updateHighWater(
            completionHighWater,
            Math.addExact(completionDepth.get(), routedCompletionDepth.get()),
        )

        var failure: Throwable? = null
        for (worker in workers) {
            while (worker.isAlive) {
                try {
                    worker.join()
                } catch (error: InterruptedException) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
        }
        while (true) {
            val workerFailure = workerFailures.poll() ?: break
            if (failure == null) failure = workerFailure else failure.addSuppressed(workerFailure)
        }
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure != null) throw failure
    }

    private fun updateHighWater(target: AtomicInteger, value: Int) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) return
        }
    }

    private fun incrementSaturating(target: AtomicLong) {
        while (true) {
            val current = target.get()
            if (current == Long.MAX_VALUE || target.compareAndSet(current, current + 1L)) return
        }
    }

    private fun decrementChecked(target: AtomicInteger, count: Int, subject: String) {
        while (true) {
            val current = target.get()
            check(current >= count) { "Terrain $subject count underflow" }
            if (target.compareAndSet(current, current - count)) return
        }
    }

    private fun releaseOutstanding(estimate: TerrainBuildEstimate) = synchronized(lifecycleLock) {
        decrementChecked(outstanding, 1, "outstanding build")
        outstandingEstimatedCpuNanos = Math.subtractExact(outstandingEstimatedCpuNanos, estimate.cpuNanos)
        outstandingEstimatedOutputBytes = Math.subtractExact(
            outstandingEstimatedOutputBytes,
            estimate.outputBytes,
        )
        check(outstandingEstimatedCpuNanos >= 0L) { "Terrain estimated CPU accounting underflow" }
        check(outstandingEstimatedOutputBytes >= 0L) { "Terrain estimated output accounting underflow" }
    }

    private fun exceedsBudget(current: Long, estimate: Long, budget: Long): Boolean =
        estimate > budget || current > budget - estimate
}
