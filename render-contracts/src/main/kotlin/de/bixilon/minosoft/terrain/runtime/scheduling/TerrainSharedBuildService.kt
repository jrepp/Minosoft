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
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

data class TerrainSharedBuildTenantSnapshot(
    val ownerId: String,
    val tenant: TerrainSchedulerTenantId,
    val accepting: Boolean,
    val activeBuilds: Int,
    val outstanding: Int,
    val completionDepth: Int,
    val workerContextCount: Int,
) {
    init {
        require(ownerId.isNotBlank()) { "Terrain build owner ID must not be blank" }
        require(activeBuilds >= 0) { "Terrain tenant active-build count must not be negative" }
        require(outstanding >= 0) { "Terrain tenant outstanding count must not be negative" }
        require(completionDepth >= 0) { "Terrain tenant completion depth must not be negative" }
        require(workerContextCount >= 0) { "Terrain tenant worker-context count must not be negative" }
        require(completionDepth <= outstanding) {
            "Terrain tenant completion depth must not exceed its outstanding count"
        }
    }
}

class TerrainSharedBuildServiceSnapshot(
    val runtime: TerrainBuildRuntimeSnapshot,
    tenants: Collection<TerrainSharedBuildTenantSnapshot>,
) {
    val tenants: List<TerrainSharedBuildTenantSnapshot> = java.util.List.copyOf(
        tenants.sortedWith(compareBy({ it.tenant.value }, { it.ownerId })),
    )

    init {
        require(this.tenants.map { it.tenant }.toSet().size == this.tenants.size) {
            "Terrain build service snapshot contains duplicate tenants"
        }
    }
}

/**
 * Process-shareable typed facade over one bounded terrain scheduler/mailbox.
 *
 * Each worker lazily owns one reusable context per registered tenant. Raw
 * completions are routed into bounded-by-outstanding tenant mailboxes, so one
 * render thread may pump the shared mailbox without invoking another world's
 * publication callback. Closing a tenant cancels only that tenant, waits for
 * its logical work to leave worker code, disposes retained values, and closes
 * its reusable contexts without stopping the process service.
 */
class TerrainSharedBuildService(
    workerCount: Int,
    queueCapacity: Int,
    nearBurstLimit: Int = 3,
    estimatedCpuBudgetNanos: Long = Long.MAX_VALUE,
    estimatedOutputBudgetBytes: Long = Long.MAX_VALUE,
    threadNamePrefix: String = "Terrain shared build",
) : AutoCloseable {
    private class ContextEntry(val context: AutoCloseable) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) context.close()
        }
    }

    private abstract class Registration(
        val ownerId: String,
        val tenant: TerrainSchedulerTenantId,
    ) {
        val accepting = AtomicBoolean(true)
        val activeBuilds = AtomicInteger()
        val outstanding = AtomicInteger()
        val contexts = ConcurrentLinkedQueue<ContextEntry>()
        val completions = ConcurrentLinkedQueue<TerrainBuildCompletion<Any?>>()

        abstract fun createContext(): AutoCloseable
        abstract fun dispose(value: Any?)

        fun closeContextsIfIdle() {
            if (accepting.get() || activeBuilds.get() != 0) return
            var failure: Throwable? = null
            while (true) {
                val context = contexts.poll() ?: break
                try {
                    context.close()
                } catch (error: Throwable) {
                    failure = combineFailures(failure, error)
                }
            }
            if (failure != null) throw failure
        }
    }

    private class TypedRegistration<C : AutoCloseable, T>(
        ownerId: String,
        tenant: TerrainSchedulerTenantId,
        private val contextFactory: () -> C,
        private val disposer: (T) -> Unit,
    ) : Registration(ownerId, tenant) {
        override fun createContext(): AutoCloseable = contextFactory()

        @Suppress("UNCHECKED_CAST")
        override fun dispose(value: Any?) = disposer(value as T)
    }

    private class SharedWorkerContext : AutoCloseable {
        private val contexts = IdentityHashMap<Registration, ContextEntry>()

        @Suppress("UNCHECKED_CAST")
        fun <C : AutoCloseable> context(registration: Registration): C {
            val entry = contexts[registration] ?: ContextEntry(registration.createContext()).also {
                contexts[registration] = it
                registration.contexts += it
            }
            return entry.context as C
        }

        override fun close() {
            var failure: Throwable? = null
            try {
                for (context in contexts.values) {
                    try {
                        context.close()
                    } catch (error: Throwable) {
                        failure = combineFailures(failure, error)
                    }
                }
            } finally {
                contexts.clear()
            }
            if (failure != null) throw failure
        }
    }

    class Lease<C : AutoCloseable, T> internal constructor(
        private val service: TerrainSharedBuildService,
        registration: Any,
    ) : AutoCloseable {
        @Suppress("UNCHECKED_CAST")
        private val registration = registration as TypedRegistration<C, T>

        val tenant: TerrainSchedulerTenantId get() = registration.tenant
        val outstanding: Int get() = registration.outstanding.get()

        fun submit(
            identity: TerrainBuildIdentity,
            urgency: TerrainBuildUrgency = TerrainBuildUrgency.NEXT_FRAME,
            estimate: TerrainBuildEstimate = TerrainBuildEstimate.MINIMUM,
            cancellation: TerrainCancellationToken = TerrainCancellationToken(),
            build: (C, TerrainCancellationToken) -> T,
        ): TerrainCancellationToken? {
            return synchronized(registration) {
                check(registration.accepting.get()) { "Terrain build tenant is closed" }
                registration.outstanding.incrementAndGet()
                val accepted = try {
                    service.runtime.submit(
                        registration.tenant,
                        identity,
                        urgency,
                        estimate,
                        cancellation,
                    ) { worker, token ->
                        registration.activeBuilds.incrementAndGet()
                        try {
                            if (!registration.accepting.get()) token.cancel()
                            build(worker.context(registration), token)
                        } finally {
                            registration.activeBuilds.decrementAndGet()
                        }
                    }
                } catch (failure: Throwable) {
                    registration.outstanding.decrementAndGet()
                    throw failure
                }
                if (accepted == null) registration.outstanding.decrementAndGet()
                accepted
            }
        }

        fun cancelAll() = service.runtime.cancelTenant(registration.tenant)

        fun drain(maxCompletions: Int, consumer: (TerrainBuildCompletion<T>) -> Unit): Int {
            require(maxCompletions > 0) { "Terrain completion drain limit must be positive" }
            service.pump(maxCompletions)
            var drained = 0
            while (drained < maxCompletions) {
                val completion = registration.completions.poll() ?: break
                try {
                    @Suppress("UNCHECKED_CAST")
                    consumer(completion as TerrainBuildCompletion<T>)
                } finally {
                    registration.outstanding.decrementAndGet()
                    service.runtime.releaseRouted(completion.estimate)
                    drained++
                }
            }
            return drained
        }

        fun snapshot(): TerrainBuildRuntimeSnapshot = service.runtime.snapshot()

        override fun close() {
            synchronized(registration) {
                if (!registration.accepting.compareAndSet(true, false)) return
            }
            cancelAll()
            var failure: Throwable? = null
            while (registration.outstanding.get() > 0) {
                try {
                    service.pump(PUMP_BATCH)
                } catch (error: Throwable) {
                    failure = combineFailures(failure, error)
                    break
                }
                while (true) {
                    val completion = registration.completions.poll() ?: break
                    try {
                        dispose(completion.outcome)
                    } catch (error: Throwable) {
                        failure = combineFailures(failure, error)
                    } finally {
                        registration.outstanding.decrementAndGet()
                        service.runtime.releaseRouted(completion.estimate)
                    }
                }
                if (registration.outstanding.get() > 0) LockSupport.parkNanos(CLOSE_PARK_NANOS)
            }
            try {
                registration.closeContextsIfIdle()
            } catch (error: Throwable) {
                failure = combineFailures(failure, error)
            } finally {
                service.registrations.remove(registration.tenant, registration)
            }
            if (failure != null) throw failure
        }

        private fun dispose(outcome: TerrainBuildOutcome<Any?>) {
            when (outcome) {
                is TerrainBuildOutcome.Success -> registration.dispose(outcome.value)
                is TerrainBuildOutcome.Cancelled -> outcome.completedValue?.let(registration::dispose)
                is TerrainBuildOutcome.Failure -> Unit
            }
        }
    }

    private val closed = AtomicBoolean()
    private val lifecycleLock = Any()
    private val registrations = ConcurrentHashMap<TerrainSchedulerTenantId, Registration>()
    private val runtime = TerrainBuildRuntime<SharedWorkerContext, Any?>(
        workerCount = workerCount,
        queueCapacity = queueCapacity,
        nearBurstLimit = nearBurstLimit,
        estimatedCpuBudgetNanos = estimatedCpuBudgetNanos,
        estimatedOutputBudgetBytes = estimatedOutputBudgetBytes,
        threadNamePrefix = threadNamePrefix,
        contextFactory = ::SharedWorkerContext,
    )

    fun <C : AutoCloseable, T> register(
        ownerId: String,
        tenant: TerrainSchedulerTenantId,
        contextFactory: () -> C,
        disposer: (T) -> Unit,
    ): Lease<C, T> {
        require(ownerId.isNotBlank()) { "Terrain build owner ID must not be blank" }
        val registration = TypedRegistration(ownerId, tenant, contextFactory, disposer)
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Terrain shared build service is closed" }
            check(registrations.putIfAbsent(tenant, registration) == null) {
                "Terrain scheduler tenant is already registered: ${tenant.value}"
            }
        }
        return Lease(this, registration)
    }

    fun snapshot(): TerrainSharedBuildServiceSnapshot {
        val tenantSnapshots = synchronized(lifecycleLock) {
            registrations.values.map { registration ->
                TerrainSharedBuildTenantSnapshot(
                    ownerId = registration.ownerId,
                    tenant = registration.tenant,
                    accepting = registration.accepting.get(),
                    activeBuilds = registration.activeBuilds.get(),
                    outstanding = registration.outstanding.get(),
                    completionDepth = registration.completions.size,
                    workerContextCount = registration.contexts.size,
                )
            }
        }
        return TerrainSharedBuildServiceSnapshot(runtime.snapshot(), tenantSnapshots)
    }

    private fun pump(maxCompletions: Int): Int = runtime.route(maxCompletions) { completion ->
        val registration = checkNotNull(registrations[completion.tenant]) {
            "Terrain completion has no registered tenant: ${completion.tenant.value}"
        }
        registration.completions += completion
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
        }
        registrations.values.forEach { registration ->
            synchronized(registration) { registration.accepting.set(false) }
            runtime.cancelTenant(registration.tenant)
        }
        var failure: Throwable? = null
        try {
            runtime.close()
        } catch (error: Throwable) {
            failure = combineFailures(failure, error)
        }
        while (runtime.snapshot().completionDepth > runtime.snapshot().routedCompletionDepth) {
            try {
                pump(PUMP_BATCH)
            } catch (error: Throwable) {
                failure = combineFailures(failure, error)
                break
            }
        }
        registrations.values.forEach { registration ->
            while (true) {
                val completion = registration.completions.poll() ?: break
                try {
                    when (val outcome = completion.outcome) {
                        is TerrainBuildOutcome.Success -> registration.dispose(outcome.value)
                        is TerrainBuildOutcome.Cancelled -> outcome.completedValue?.let(registration::dispose)
                        is TerrainBuildOutcome.Failure -> Unit
                    }
                } catch (error: Throwable) {
                    failure = combineFailures(failure, error)
                } finally {
                    registration.outstanding.decrementAndGet()
                    runtime.releaseRouted(completion.estimate)
                }
            }
            try {
                registration.closeContextsIfIdle()
            } catch (error: Throwable) {
                failure = combineFailures(failure, error)
            }
        }
        registrations.clear()
        if (failure != null) throw failure
    }

    private companion object {
        const val PUMP_BATCH = 64
        const val CLOSE_PARK_NANOS = 100_000L
    }
}

private fun combineFailures(primary: Throwable?, next: Throwable): Throwable {
    if (primary == null) return next
    primary.addSuppressed(next)
    return primary
}
