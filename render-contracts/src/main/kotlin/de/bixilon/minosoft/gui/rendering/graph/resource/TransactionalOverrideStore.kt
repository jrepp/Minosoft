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

/**
 * One fallback generation with at most one replaceable override.
 *
 * Closing the current registration republishes the fallback. Closing a stale
 * registration cannot remove a newer override. Generation leases and deferred
 * retirement remain owned by [TransactionalGenerationStore].
 */
class TransactionalOverrideStore<T : Any>(
    private val fallback: T,
    retire: (T) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val generations = TransactionalGenerationStore(fallback, retire)
    private var nextToken = 1L
    private var activeToken: Long? = null
    private var closed = false

    fun acquire(): TransactionalGenerationStore.Lease<T> = generations.acquire()

    fun replace(prepare: () -> T): AutoCloseable {
        val token = synchronized(lock) {
            check(!closed) { "Override store is closed" }
            val token = nextToken++
            generations.replace(prepare)
            activeToken = token
            token
        }
        return Registration(token)
    }

    fun stats(): TransactionalGenerationStore.Stats = generations.stats()

    private fun remove(token: Long) {
        synchronized(lock) {
            if (closed || activeToken != token) return
            generations.replace { fallback }
            activeToken = null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeToken = null
        }
        generations.close()
    }

    private inner class Registration(
        private val token: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) remove(token)
        }
    }
}
