/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.modding.loader.ModOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

enum class FabricModRuntimeStatus(val wireName: String) {
    BLOCKED("blocked"),
    READY("ready"),
    ACTIVATING("activating"),
    ACTIVE("active"),
    FAILED("failed"),
    INACTIVE("inactive"),
}

data class FabricHookDiagnosticSnapshot(
    val kind: String,
    val installed: Boolean,
    val invocations: Long,
    val totalNanos: Long,
    val maxNanos: Long,
) {
    val averageNanos: Long get() = if (invocations == 0L) 0L else totalNanos / invocations
}

data class FabricModDiagnosticSnapshot(
    val id: String,
    val name: String,
    val version: String,
    val adapterId: String?,
    val status: FabricModRuntimeStatus,
    val activationNanos: Long,
    val failure: String?,
    val hooks: List<FabricHookDiagnosticSnapshot>,
) {
    val hookInvocations: Long get() = hooks.sumOf(FabricHookDiagnosticSnapshot::invocations)
}

data class FabricPackDiagnosticSnapshot(
    val packId: String,
    val packName: String,
    val trajectory: String,
    val generation: Int,
    val preflightNanos: Long,
    val uptimeNanos: Long,
    val active: Boolean,
    val mods: List<FabricModDiagnosticSnapshot>,
)

/**
 * Process-local evidence for adapted Fabric mods. This measures only Minosoft-owned
 * adapter and hook work; it intentionally does not attribute whole-frame time to a mod.
 * The parent launcher supplies a generation number so a fresh process is visible in game.
 */
object FabricModDiagnostics {
    private class MutableHook(val kind: String) {
        @Volatile var installed = false
        val invocations = LongAdder()
        val totalNanos = LongAdder()
        val maxNanos = AtomicLong()

        fun snapshot() = FabricHookDiagnosticSnapshot(kind, installed, invocations.sum(), totalNanos.sum(), maxNanos.get())
    }

    private class MutableMod(val probe: FabricModProbe) {
        @Volatile var status = FabricModRuntimeStatus.READY
        @Volatile var activationNanos = 0L
        @Volatile var failure: String? = null
        val hooks = ConcurrentHashMap<String, MutableHook>()

        fun snapshot() = FabricModDiagnosticSnapshot(
            id = probe.metadata.id,
            name = probe.metadata.name,
            version = probe.metadata.version,
            adapterId = probe.adapter?.id,
            status = status,
            activationNanos = activationNanos,
            failure = failure,
            hooks = hooks.values.map(MutableHook::snapshot).sortedBy(FabricHookDiagnosticSnapshot::kind),
        )
    }

    @Volatile private var report: FabricPackReport? = null
    @Volatile private var preflightNanos = 0L
    @Volatile private var active = false
    @Volatile private var activeSinceNanos = 0L
    private val modsByAdapter = ConcurrentHashMap<String, MutableMod>()
    private val modsById = ConcurrentHashMap<String, MutableMod>()

    @Synchronized
    fun begin(report: FabricPackReport, preflightNanos: Long) {
        this.report = report
        this.preflightNanos = preflightNanos.coerceAtLeast(0L)
        active = false
        activeSinceNanos = 0L
        modsByAdapter.clear()
        modsById.clear()
        for (probe in report.mods) {
            val diagnostic = MutableMod(probe)
            if (!probe.activatable) {
                diagnostic.status = FabricModRuntimeStatus.BLOCKED
                diagnostic.failure = buildString {
                    append("blockers=")
                    append(probe.blockers.joinToString(",") { it.name.lowercase() })
                    if (probe.dependencyIssues.isNotEmpty()) {
                        append("; dependencies=")
                        append(probe.dependencyIssues.joinToString(" | "))
                    }
                }
            }
            modsById[probe.metadata.id] = diagnostic
            probe.adapter?.id?.let { modsByAdapter[it] = diagnostic }
        }
    }

    fun activationStarted(probe: FabricModProbe) {
        modsById[probe.metadata.id]?.status = FabricModRuntimeStatus.ACTIVATING
    }

    fun activationCompleted(probe: FabricModProbe, elapsedNanos: Long) {
        modsById[probe.metadata.id]?.apply {
            activationNanos = elapsedNanos.coerceAtLeast(0L)
            status = FabricModRuntimeStatus.ACTIVE
        }
    }

    fun activationFailed(probe: FabricModProbe, elapsedNanos: Long, error: Throwable) {
        modsById[probe.metadata.id]?.apply {
            activationNanos = elapsedNanos.coerceAtLeast(0L)
            failure = error.message ?: error::class.java.simpleName
            status = FabricModRuntimeStatus.FAILED
        }
    }

    fun packActivated() {
        activeSinceNanos = System.nanoTime()
        active = true
    }

    fun packDeactivated() {
        active = false
        modsById.values.filter { it.status != FabricModRuntimeStatus.FAILED && it.status != FabricModRuntimeStatus.BLOCKED }
            .forEach { it.status = FabricModRuntimeStatus.INACTIVE }
    }

    fun hookInstalled(owner: String, kind: String) {
        modsByAdapter[owner]?.hooks?.computeIfAbsent(kind, ::MutableHook)?.installed = true
    }

    fun hookUninstalled(owner: String, kind: String) {
        modsByAdapter[owner]?.hooks?.get(kind)?.installed = false
    }

    fun hookInvoked(owner: String, kind: String, elapsedNanos: Long) {
        val hook = modsByAdapter[owner]?.hooks?.computeIfAbsent(kind, ::MutableHook) ?: return
        val elapsed = elapsedNanos.coerceAtLeast(0L)
        hook.invocations.increment()
        hook.totalNanos.add(elapsed)
        hook.maxNanos.accumulateAndGet(elapsed, ::maxOf)
    }

    fun snapshot(): FabricPackDiagnosticSnapshot? {
        val current = report ?: return null
        val since = activeSinceNanos
        return FabricPackDiagnosticSnapshot(
            packId = current.pack.id,
            packName = current.pack.name,
            trajectory = ModOptions.trajectory,
            generation = ModOptions.hotReloadGeneration,
            preflightNanos = preflightNanos,
            uptimeNanos = if (active && since != 0L) (System.nanoTime() - since).coerceAtLeast(0L) else 0L,
            active = active,
            mods = current.mods.mapNotNull { modsById[it.metadata.id]?.snapshot() },
        )
    }

    @Synchronized
    fun clear() {
        report = null
        preflightNanos = 0L
        active = false
        activeSinceNanos = 0L
        modsByAdapter.clear()
        modsById.clear()
    }
}
