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

package de.bixilon.minosoft.gui.rendering.terrain.near.provider

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import java.util.IdentityHashMap

/**
 * Selects one configured near-terrain provider for every attached render
 * context. Artifact-specific adapters own the descriptor and this owner keeps
 * only renderer lifecycle state.
 */
class TerrainProviderSelection(
    private val descriptor: TerrainBackendDescriptor,
) : AutoCloseable {
    private val registrations = IdentityHashMap<RenderContext, AutoCloseable>()
    @Volatile private var enabled = true
    @Volatile private var closed = false

    fun attach(context: RenderContext): Boolean {
        val registration = synchronized(this) {
            check(!closed) { "Terrain provider selection is closed" }
            if (!enabled) return false
            if (registrations.containsKey(context)) return true
            val chunks = requireNotNull(context.renderer[ChunkRenderer]) { "Near terrain provider requires terrain" }
            chunks.terrain.replace {
                ChunkRendererNearTerrainBackend(chunks, descriptor)
            }.also { registrations[context] = it }
        }
        try {
            context.renderer.pipeline.rebuild()
        } catch (failure: Throwable) {
            synchronized(this) { registrations.remove(context, registration) }
            try {
                registration.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return synchronized(this) { registrations[context] === registration }
    }

    fun setEnabled(context: RenderContext, enabled: Boolean): Boolean {
        synchronized(this) {
            check(!closed) { "Terrain provider selection is closed" }
            this.enabled = enabled
        }
        if (enabled) return attach(context)
        detach(context)
        return false
    }

    fun isEnabled(): Boolean = enabled

    fun isInstalled(context: RenderContext): Boolean = synchronized(this) {
        registrations.containsKey(context)
    }

    fun detach(context: RenderContext) {
        val registration = synchronized(this) { registrations.remove(context) }
        var failure: Throwable? = null
        try {
            registration?.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            if (context.state != RenderingStates.QUITTING && context.state != RenderingStates.STOPPED) {
                context.renderer.pipeline.rebuild()
            }
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        if (failure != null) throw failure
    }

    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            registrations.values.toList().also { registrations.clear() }
        }
        var failure: Throwable? = null
        for (registration in owned) {
            try {
                registration.close()
            } catch (cleanup: Throwable) {
                if (failure == null) {
                    failure = cleanup
                } else {
                    failure.addSuppressed(cleanup)
                }
            }
        }
        failure?.let { throw it }
    }
}
