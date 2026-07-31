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

package de.bixilon.minosoft.gui.rendering.graph.resource

import java.util.concurrent.atomic.AtomicBoolean

class TransactionalGenerationStore<T : Any>(
    initial: T,
    private val retire: (T) -> Unit,
) : AutoCloseable {
    data class Stats(
        val activeGeneration: Long?,
        val activeLeases: Int,
        val retiredAwaitingLeases: Int,
        val closed: Boolean,
    )

    private class State<T>(
        val number: Long,
        val value: T,
        var leases: Int = 0,
        var retired: Boolean = false,
        var disposed: Boolean = false,
    )

    class Lease<T : Any> internal constructor(
        val generation: Long,
        val value: T,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private val lock = Any()
    private val states = linkedSetOf<State<T>>()
    private var active: State<T>? = State(0L, initial).also(states::add)
    private var closed = false

    fun acquire(): Lease<T> {
        val state = synchronized(lock) {
            check(!closed) { "Generation store is closed" }
            val current = checkNotNull(active) { "Generation store has no active generation" }
            current.leases++
            current
        }
        return Lease(state.number, state.value) { release(state) }
    }

    fun replace(prepare: () -> T): Long {
        val candidate = prepare()
        var retireNow: State<T>? = null
        var closedFailure: IllegalStateException? = null
        var number = -1L
        synchronized(lock) {
            if (closed) {
                closedFailure = IllegalStateException("Generation store closed while preparing a candidate")
            } else {
                val previous = checkNotNull(active)
                val replacement = State(previous.number + 1L, candidate)
                states += replacement
                active = replacement
                previous.retired = true
                if (previous.leases == 0) {
                    previous.disposed = true
                    states -= previous
                    retireNow = previous
                }
                number = replacement.number
            }
        }
        closedFailure?.let { failure ->
            retireSuppressing(candidate, failure)
            throw failure
        }
        retireNow?.let { retire(it.value) }
        return number
    }

    fun stats(): Stats = synchronized(lock) {
        val current = active
        Stats(
            activeGeneration = current?.number,
            activeLeases = current?.leases ?: 0,
            retiredAwaitingLeases = states.count { it.retired && !it.disposed },
            closed = closed,
        )
    }

    private fun release(state: State<T>) {
        var retireNow = false
        synchronized(lock) {
            check(state.leases > 0) { "Generation ${state.number} lease underflow" }
            state.leases--
            if (state.retired && state.leases == 0 && !state.disposed) {
                state.disposed = true
                states -= state
                retireNow = true
            }
        }
        if (retireNow) retire(state.value)
    }

    override fun close() {
        var retireNow: State<T>? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            val current = active
            active = null
            if (current != null) {
                current.retired = true
                if (current.leases == 0 && !current.disposed) {
                    current.disposed = true
                    states -= current
                    retireNow = current
                }
            }
        }
        retireNow?.let { retire(it.value) }
    }

    private fun retireSuppressing(value: T, primary: Throwable) {
        try {
            retire(value)
        } catch (cleanup: Throwable) {
            primary.addSuppressed(cleanup)
        }
    }
}
