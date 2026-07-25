/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.array

/**
 * Reference counts stable shader coordinates rather than texture names.
 * Reloaded pixels may reuse a coordinate, and a same-name texture may move to
 * another coordinate when its resolution changes.
 */
internal class StaticTextureSlotOwnership<S> {
    private val lock = Any()
    private val managed = linkedSetOf<S>()
    private val permanent = linkedSetOf<S>()
    private val references = linkedMapOf<S, Int>()

    fun retain(slots: Collection<S>, reclaimable: Collection<S>): AutoCloseable {
        val used = slots.toSet()
        val mayReclaim = reclaimable.toSet()
        val retained = synchronized(lock) {
            for (slot in used) {
                if (slot in mayReclaim && slot !in permanent) {
                    managed += slot
                } else if (slot !in managed) {
                    permanent += slot
                }
            }
            used.filterTo(linkedSetOf()) { slot ->
                if (slot !in managed) return@filterTo false
                references[slot] = (references[slot] ?: 0) + 1
                true
            }
        }
        return Lease {
            synchronized(lock) {
                for (slot in retained) {
                    val count = references[slot]
                        ?: throw IllegalStateException("Static texture slot $slot has no generation owner.")
                    check(count > 0) { "Static texture slot $slot was released too many times." }
                    if (count == 1) {
                        references.remove(slot)
                    } else {
                        references[slot] = count - 1
                    }
                }
            }
        }
    }

    fun isManaged(slot: S): Boolean = synchronized(lock) { slot in managed }

    fun freeSlots(): Set<S> = synchronized(lock) {
        managed.filterTo(linkedSetOf()) { (references[it] ?: 0) == 0 }
    }

    fun forget(slots: Collection<S>) {
        synchronized(lock) {
            for (slot in slots) {
                check((references[slot] ?: 0) == 0) {
                    "Can not compact live static texture slot $slot."
                }
                references.remove(slot)
                managed.remove(slot)
                permanent.remove(slot)
            }
        }
    }

    fun diagnostics(): StaticTextureSlotDiagnostics = synchronized(lock) {
        StaticTextureSlotDiagnostics(
            managed = managed.size,
            live = references.count { it.value > 0 },
            free = managed.count { (references[it] ?: 0) == 0 },
            permanent = permanent.size,
        )
    }

    private class Lease(private val release: () -> Unit) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            release()
        }
    }
}

data class StaticTextureSlotDiagnostics(
    val managed: Int,
    val live: Int,
    val free: Int,
    val permanent: Int,
)
