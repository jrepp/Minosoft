/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.protocol.network.session.play.PlaySession

enum class FabricClientTickPhase(val wireName: String) {
    START("start-client-tick"),
    END("end-client-tick"),
}

fun interface FabricClientTickCallback {
    fun invoke(session: PlaySession)
}

/**
 * Source-level client tick bridge. Callbacks run synchronously on Minosoft's
 * ordered session tick worker, not the render thread.
 */
object FabricClientTickEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("client-tick-events")
    private val phases = FabricClientTickPhase.entries.associateWith {
        FabricOwnedEventRegistry<PlaySession>("client-tick-event:${it.wireName}")
    }

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)

    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, phase: FabricClientTickPhase, callback: FabricClientTickCallback): AutoCloseable =
        phases.getValue(phase).register(owner, callback::invoke)

    fun registrations(phase: FabricClientTickPhase): List<String> = phases.getValue(phase).owners()

    fun dispatch(phase: FabricClientTickPhase, session: PlaySession) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "client-tick-events", System.nanoTime() - started)
        }
        phases.getValue(phase).dispatch(session)
    }
}
