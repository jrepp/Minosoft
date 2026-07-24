/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.framebuffer.world

/**
 * Owner-scoped selection point for a world framebuffer presentation shader.
 *
 * The most recently installed processor wins. Closing its handle restores the
 * previous processor instead of disturbing another owner.
 */
class WorldPostProcessors<T : Any> {
    private data class Registration<T>(val owner: String, val processor: T)

    private val registrations = linkedMapOf<Any, Registration<T>>()

    val processor: T?
        @Synchronized get() = registrations.values.lastOrNull()?.processor

    @Synchronized
    fun install(owner: String, processor: T): AutoCloseable {
        require(owner.isNotBlank()) { "World post-processor owner must not be blank." }
        val token = Any()
        registrations[token] = Registration(owner, processor)
        return AutoCloseable {
            synchronized(this) {
                registrations.remove(token)
            }
        }
    }

    @Synchronized
    fun owners(): List<String> = registrations.values.map(Registration<T>::owner)
}
