/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.datapack

import de.bixilon.minosoft.assets.model.generation.ContentFidelitySnapshot
import de.bixilon.minosoft.assets.model.generation.ContentGenerationLease
import de.bixilon.minosoft.assets.model.generation.ContentGenerationStore

/**
 * Owns the function-runtime view of a session content generation.
 *
 * A replacement library must complete its load tag before publication. The old
 * lease and scheduled runtime remain active when load fails, matching the
 * last-known-good contract used by model and texture content.
 */
class SessionDataPackRuntime(
    private val content: ContentGenerationStore<ContentFidelitySnapshot>,
    private val sink: DataPackCommandSink,
    private val limits: DataPackFunctionRuntime.Limits = DataPackFunctionRuntime.Limits(),
) : AutoCloseable {
    private var lease: ContentGenerationLease<ContentFidelitySnapshot>? = null
    private var runtime: DataPackFunctionRuntime? = null
    private var rejectedGenerationId: Long? = null
    private var closed = false

    val activeGenerationId: Long?
        @Synchronized get() = lease?.generationId

    val tick: Long
        @Synchronized get() = runtime?.tick ?: 0L

    /**
     * Returns true only when a new generation was loaded and published.
     */
    @Synchronized
    fun refresh(): Boolean {
        check(!closed) { "Session data-pack runtime is closed." }
        val candidateLease = content.lease() ?: return false
        if (candidateLease.generationId == lease?.generationId) {
            candidateLease.close()
            return false
        }
        if (candidateLease.generationId == rejectedGenerationId) {
            candidateLease.close()
            return false
        }

        val transaction = try {
            (sink as? DataPackTransactionalSink)?.beginTransaction()
        } catch (error: Throwable) {
            closeSuppressing(candidateLease, error)
            throw error
        }
        val candidateRuntime = DataPackFunctionRuntime(candidateLease.value.dataPackFunctions, sink, limits)
        try {
            candidateRuntime.load()
            transaction?.commit()
        } catch (error: Throwable) {
            try {
                transaction?.rollback()
            } catch (rollback: Throwable) {
                error.addSuppressed(rollback)
            }
            rejectedGenerationId = candidateLease.generationId
            closeSuppressing(candidateLease, error)
            throw error
        }

        val previous = lease
        lease = candidateLease
        runtime = candidateRuntime
        rejectedGenerationId = null
        previous?.close()
        return true
    }

    @Synchronized
    fun tick() {
        check(!closed) { "Session data-pack runtime is closed." }
        refresh()
        runtime?.tick()
    }

    @Synchronized
    fun execute(reference: String, arguments: Map<String, String> = emptyMap()): Int {
        check(!closed) { "Session data-pack runtime is closed." }
        refresh()
        return runtime?.execute(reference, arguments) ?: 0
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runtime = null
        lease?.close()
        lease = null
    }

    private fun closeSuppressing(
        lease: ContentGenerationLease<ContentFidelitySnapshot>,
        original: Throwable,
    ) {
        try {
            lease.close()
        } catch (cleanup: Throwable) {
            original.addSuppressed(cleanup)
        }
    }
}
