/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.UUID

enum class FabricEntityEventPhase(val wireName: String) {
    ADDED("added"),
    REMOVED("removed"),
    CLEARED("cleared"),
}

data class FabricEntityChange(
    val entity: Entity,
    val entityId: Int?,
    val entityUuid: UUID?,
)

data class FabricEntityEventContext(
    /** Absent only for entities created before they are attached to a play session. */
    val session: PlaySession?,
    val phase: FabricEntityEventPhase,
    val changes: List<FabricEntityChange>,
)

fun interface FabricEntityEventCallback {
    fun invoke(context: FabricEntityEventContext)
}

object FabricEntityEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("entity-events")
    private val callbacks = FabricOwnedEventRegistry<FabricEntityEventContext>("entity-event")

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, callback: FabricEntityEventCallback): AutoCloseable = callbacks.register(owner, callback::invoke)
    fun registrations(): List<String> = callbacks.owners()

    fun dispatch(context: FabricEntityEventContext) {
        if (context.changes.isEmpty()) return
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "entity-events", System.nanoTime() - started)
        }
        callbacks.dispatch(context.copy(changes = context.changes.toList()))
    }
}
