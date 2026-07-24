/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicLong

/**
 * Stable source-level phases corresponding to the most broadly used Fabric
 * client lifecycle and rendering events. They intentionally expose Minosoft's
 * render context instead of pretending that Mojang client classes can link.
 * Callbacks run synchronously on the render thread in registration order.
 */
enum class FabricClientEventPhase(val wireName: String) {
    CLIENT_STARTED("client-started"),
    BEFORE_WORLD_RENDER("before-world-render"),
    AFTER_WORLD_RENDER("after-world-render"),
    BEFORE_HUD_RENDER("before-hud-render"),
    AFTER_HUD_RENDER("after-hud-render"),
    CLIENT_STOPPING("client-stopping"),
}

fun interface FabricClientEventCallback {
    fun invoke(context: RenderContext)
}

internal class FabricOwnedEventRegistry<T : Any>(private val kind: String) {
    private data class Registration<T>(val id: Long, val owner: String, val callback: (T) -> Unit)

    private val nextId = AtomicLong()
    private val callbacks = linkedMapOf<Long, Registration<T>>()
    private val ownerCounts = linkedMapOf<String, Int>()

    @Synchronized
    fun register(owner: String, callback: (T) -> Unit): AutoCloseable {
        require(owner.isNotBlank()) { "Fabric $kind callback owner must not be blank." }
        val registration = Registration(nextId.incrementAndGet(), owner, callback)
        callbacks[registration.id] = registration
        val count = ownerCounts.getOrDefault(owner, 0) + 1
        ownerCounts[owner] = count
        if (count == 1) FabricModDiagnostics.hookInstalled(owner, kind)
        return AutoCloseable {
            synchronized(this) {
                if (!callbacks.remove(registration.id, registration)) return@synchronized
                val remaining = ownerCounts.getValue(owner) - 1
                if (remaining == 0) {
                    ownerCounts.remove(owner)
                    FabricModDiagnostics.hookUninstalled(owner, kind)
                } else ownerCounts[owner] = remaining
            }
        }
    }

    @Synchronized
    fun owners(): List<String> = callbacks.values.map(Registration<T>::owner)

    fun dispatch(value: T) {
        val snapshot = synchronized(this) { callbacks.values.toList() }
        for (registration in snapshot) {
            val started = System.nanoTime()
            try {
                registration.callback(value)
            } catch (error: Throwable) {
                // One compatibility adapter must not abort the host boundary or
                // prevent later adapters from observing the same phase.
                Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                    "FABRIC_EVENT_FAILED owner=${registration.owner} event=$kind error=${error::class.java.simpleName}: ${error.message}"
                }
            } finally {
                FabricModDiagnostics.hookInvoked(registration.owner, kind, System.nanoTime() - started)
            }
        }
    }
}

object FabricClientEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("client-events")
    private val phases = FabricClientEventPhase.entries.associateWith {
        FabricOwnedEventRegistry<RenderContext>("client-event:${it.wireName}")
    }

    /** Publishes that an adapter provides the shared client-event bridge. */
    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)

    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, phase: FabricClientEventPhase, callback: FabricClientEventCallback): AutoCloseable =
        phases.getValue(phase).register(owner, callback::invoke)

    fun registrations(phase: FabricClientEventPhase): List<String> = phases.getValue(phase).owners()

    fun dispatch(phase: FabricClientEventPhase, context: RenderContext) {
        // Attribute the host-side bridge crossing separately from consumer work.
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "client-events", System.nanoTime() - started)
        }
        phases.getValue(phase).dispatch(context)
    }
}
