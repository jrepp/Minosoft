/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.PlaySessionStates

enum class FabricClientConnectionPhase(val wireName: String) {
    CREATED("created"),
    STATE_CHANGED("state-changed"),
    JOINED("joined"),
    DISCONNECTED("disconnected"),
}

data class FabricClientConnectionContext(
    val session: PlaySession,
    val previous: PlaySessionStates?,
    val state: PlaySessionStates,
)

fun interface FabricClientConnectionCallback {
    fun invoke(context: FabricClientConnectionContext)
}

/** Source lifecycle over Minosoft's multi-session state machine. */
object FabricClientConnectionEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("client-connection-events")
    private val phases = FabricClientConnectionPhase.entries.associateWith {
        FabricOwnedEventRegistry<FabricClientConnectionContext>("client-connection-event:${it.wireName}")
    }

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)

    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, phase: FabricClientConnectionPhase, callback: FabricClientConnectionCallback): AutoCloseable =
        phases.getValue(phase).register(owner, callback::invoke)

    fun registrations(phase: FabricClientConnectionPhase): List<String> = phases.getValue(phase).owners()

    fun dispatch(phase: FabricClientConnectionPhase, context: FabricClientConnectionContext) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "client-connection-events", System.nanoTime() - started)
        }
        phases.getValue(phase).dispatch(context)
    }
}
