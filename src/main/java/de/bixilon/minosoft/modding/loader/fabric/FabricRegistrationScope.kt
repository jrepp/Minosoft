/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

class FabricRegistrationScope : AutoCloseable {
    private val cleanup = ArrayDeque<AutoCloseable>()
    var closed = false
        private set

    @Synchronized
    fun <T : AutoCloseable> own(registration: T): T {
        check(!closed) { "Registration scope is closed." }
        cleanup.addFirst(registration)
        return registration
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        while (cleanup.isNotEmpty()) {
            try {
                cleanup.removeFirst().close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}
