/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.protocol.network.session.play.PlaySession

enum class FabricResourceReloadType(val wireName: String) {
    SESSION_ASSETS("session-assets"),
    CONTENT_FIDELITY("content-fidelity"),
    SHADERS("shaders"),
    TEXTURES("textures"),
}

enum class FabricResourceReloadPhase(val wireName: String) {
    PREPARE("prepare"),
    APPLY("apply"),
    COMPLETE("complete"),
    FAILED("failed"),
}

data class FabricResourceReloadContext(
    val session: PlaySession,
    val type: FabricResourceReloadType,
    val phase: FabricResourceReloadPhase,
    val error: Throwable? = null,
)

fun interface FabricResourceReloadCallback {
    fun invoke(context: FabricResourceReloadContext)
}

internal object FabricReloadTransaction {
    fun <T> run(
        prepare: () -> T,
        apply: (T) -> Unit,
        phase: (FabricResourceReloadPhase, Throwable?) -> Unit,
    ): T {
        phase(FabricResourceReloadPhase.PREPARE, null)
        try {
            val candidate = prepare()
            phase(FabricResourceReloadPhase.APPLY, null)
            apply(candidate)
            phase(FabricResourceReloadPhase.COMPLETE, null)
            return candidate
        } catch (error: Throwable) {
            phase(FabricResourceReloadPhase.FAILED, error)
            throw error
        }
    }
}

/**
 * Source-level reload lifecycle. Callbacks execute on the initiating thread:
 * session asset preparation uses its loading worker, while shader and texture
 * apply operations use the render queue.
 */
object FabricResourceReloadEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("resource-reload-events")
    private val phases = FabricResourceReloadPhase.entries.associateWith {
        FabricOwnedEventRegistry<FabricResourceReloadContext>("resource-reload-event:${it.wireName}")
    }

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)

    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, phase: FabricResourceReloadPhase, callback: FabricResourceReloadCallback): AutoCloseable =
        phases.getValue(phase).register(owner, callback::invoke)

    fun registrations(phase: FabricResourceReloadPhase): List<String> = phases.getValue(phase).owners()

    fun <T> run(
        session: PlaySession,
        type: FabricResourceReloadType,
        prepare: () -> T,
        apply: (T) -> Unit,
    ): T = FabricReloadTransaction.run(prepare, apply) { phase, error ->
        dispatch(FabricResourceReloadContext(session, type, phase, error))
    }

    private fun dispatch(context: FabricResourceReloadContext) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "resource-reload-events", System.nanoTime() - started)
        }
        phases.getValue(context.phase).dispatch(context)
    }
}
