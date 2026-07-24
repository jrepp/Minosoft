/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.generation

/**
 * A prepared content value and every resource owned by it. The cleanup may
 * include renderer objects, but preparation itself must not mutate the active
 * generation.
 */
data class PreparedContent<T>(
    val value: T,
    val cleanup: AutoCloseable = AutoCloseable { },
)

/**
 * A small read-copy-update store for parsed and baked content.
 *
 * Preparation happens before the atomic commit. A failed preparation therefore
 * cannot disturb the active generation. Readers hold a lease, so resources from
 * a replaced generation are destroyed only after the final reader releases it.
 */
class ContentGenerationStore<T> : AutoCloseable {
    private val lock = Any()
    private var active: Generation<T>? = null
    private var nextId = 1L
    private var preparing = false
    private var closed = false

    val generationId: Long?
        get() = synchronized(lock) { active?.id }

    fun reload(
        commit: (T) -> Unit = { },
        prepare: (generationId: Long) -> PreparedContent<T>,
    ): Long {
        val id = synchronized(lock) {
            check(!closed) { "Content generation store is closed." }
            check(!preparing) { "Concurrent content generation preparation is not supported." }
            preparing = true
            nextId++
        }
        val prepared = try {
            prepare(id)
        } catch (error: Throwable) {
            synchronized(lock) { preparing = false }
            throw error
        }
        val candidate = Generation(id, prepared.value, prepared.cleanup)
        val (accepted, cleanup) = try {
            synchronized(lock) {
                check(preparing)
                preparing = false
                if (closed) {
                    candidate.retired = true
                    false to candidate
                } else {
                    commit(candidate.value)
                    val previous = active
                    active = candidate
                    previous?.retired = true
                    true to previous?.takeCleanupIfUnused()
                }
            }
        } catch (error: Throwable) {
            synchronized(lock) { preparing = false }
            candidate.retired = true
            try {
                candidate.takeCleanupIfUnused()?.closeCleanup()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
        if (!accepted) {
            cleanup?.closeCleanup()
            throw IllegalStateException("Content generation store was closed while preparing generation $id.")
        }
        cleanup?.closeCleanup()
        return id
    }

    fun lease(): ContentGenerationLease<T>? = synchronized(lock) {
        val generation = active ?: return null
        check(!generation.retired)
        generation.readers++
        ContentGenerationLease(generation.id, generation.value) {
            release(generation)
        }
    }

    private fun release(generation: Generation<T>) {
        val cleanup = synchronized(lock) {
            check(generation.readers > 0) { "Content generation ${generation.id} lease was released too many times." }
            generation.readers--
            generation.takeCleanupIfUnused()
        }
        cleanup?.closeCleanup()
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            if (closed) return
            closed = true
            val previous = active
            active = null
            previous?.retired = true
            previous?.takeCleanupIfUnused()
        }
        cleanup?.closeCleanup()
    }

    private class Generation<T>(
        val id: Long,
        val value: T,
        val cleanup: AutoCloseable,
        var readers: Int = 0,
        var retired: Boolean = false,
        var cleaned: Boolean = false,
    ) {
        fun takeCleanupIfUnused(): Generation<T>? {
            if (!retired || readers != 0 || cleaned) return null
            cleaned = true
            return this
        }

        fun closeCleanup() = cleanup.close()
    }
}

class ContentGenerationLease<T> internal constructor(
    val generationId: Long,
    val value: T,
    private val release: () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        release()
    }
}
