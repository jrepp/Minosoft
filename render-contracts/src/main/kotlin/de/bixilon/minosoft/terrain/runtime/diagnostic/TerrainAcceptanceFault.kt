/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.diagnostic

enum class TerrainAcceptanceFault(val wireName: String) {
    REJECT_NEXT_NEAR_UPLOAD("reject-next-near-upload"),
    ;

    companion object {
        fun fromWireName(value: String): TerrainAcceptanceFault? = entries.firstOrNull { it.wireName == value }
    }
}

data class TerrainAcceptanceFaultScope(
    val worldEpoch: Long,
    val pipelineGeneration: Long,
    val shaderGeneration: Long,
    val nearLayoutGeneration: Long,
) {
    init {
        require(worldEpoch >= 0L) { "Terrain fault world epoch must not be negative" }
        require(pipelineGeneration >= 0L) { "Terrain fault pipeline generation must not be negative" }
        require(shaderGeneration >= 0L) { "Terrain fault shader generation must not be negative" }
        require(nearLayoutGeneration >= 0L) { "Terrain fault layout generation must not be negative" }
    }
}

data class TerrainAcceptanceFaultSnapshot(
    val generation: Long,
    val active: Boolean,
    val fault: TerrainAcceptanceFault?,
    val restorationToken: String?,
    val consumedCount: Long,
    val invalidatedCount: Long,
)

/** Acceptance-only, one-shot injection state. Runtime callers must supply their current generation scope. */
class TerrainAcceptanceFaultController {
    private data class Armed(
        val fault: TerrainAcceptanceFault,
        val scope: TerrainAcceptanceFaultScope,
        val token: String,
    )

    @Volatile
    private var armed: Armed? = null
    private var generation = 0L
    private var consumedCount = 0L
    private var invalidatedCount = 0L
    private var nextToken = 1L

    fun isArmed(fault: TerrainAcceptanceFault): Boolean = armed?.fault == fault

    @Synchronized
    fun arm(
        fault: TerrainAcceptanceFault,
        scope: TerrainAcceptanceFaultScope,
    ): TerrainAcceptanceFaultSnapshot {
        reconcile(scope)
        check(armed == null) { "A terrain acceptance fault is already armed" }
        val token = "terrain-fault-${nextToken.toString(36)}"
        nextToken = Math.incrementExact(nextToken)
        generation = Math.incrementExact(generation)
        armed = Armed(fault, scope, token)
        return snapshot()
    }

    @Synchronized
    fun consume(
        fault: TerrainAcceptanceFault,
        scope: TerrainAcceptanceFaultScope,
    ): Boolean {
        reconcile(scope)
        val current = armed ?: return false
        if (current.fault != fault) return false
        armed = null
        generation = Math.incrementExact(generation)
        consumedCount = Math.incrementExact(consumedCount)
        return true
    }

    @Synchronized
    fun restore(
        restorationToken: String,
        scope: TerrainAcceptanceFaultScope,
    ): TerrainAcceptanceFaultSnapshot {
        reconcile(scope)
        val current = armed ?: return snapshot()
        require(current.token == restorationToken) { "Terrain fault restoration token does not match" }
        armed = null
        generation = Math.incrementExact(generation)
        return snapshot()
    }

    @Synchronized
    fun snapshot(scope: TerrainAcceptanceFaultScope? = null): TerrainAcceptanceFaultSnapshot {
        if (scope != null) reconcile(scope)
        val current = armed
        return TerrainAcceptanceFaultSnapshot(
            generation = generation,
            active = current != null,
            fault = current?.fault,
            restorationToken = current?.token,
            consumedCount = consumedCount,
            invalidatedCount = invalidatedCount,
        )
    }

    @Synchronized
    fun invalidate(): TerrainAcceptanceFaultSnapshot {
        if (armed != null) {
            armed = null
            generation = Math.incrementExact(generation)
            invalidatedCount = Math.incrementExact(invalidatedCount)
        }
        return snapshot()
    }

    private fun reconcile(scope: TerrainAcceptanceFaultScope) {
        val current = armed ?: return
        if (current.scope == scope) return
        armed = null
        generation = Math.incrementExact(generation)
        invalidatedCount = Math.incrementExact(invalidatedCount)
    }
}
