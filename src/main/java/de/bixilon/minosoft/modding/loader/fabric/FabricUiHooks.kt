/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.GUIElement
import de.bixilon.minosoft.gui.rendering.gui.elements.LayoutedElement
import de.bixilon.minosoft.gui.rendering.gui.hud.HUDManager
import de.bixilon.minosoft.gui.rendering.gui.hud.elements.HUDBuilder
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.lang.ref.WeakReference

fun interface FabricScreenFactory {
    fun build(renderer: GUIRenderer): LayoutedElement
}

data class FabricScreenRegistration(
    val owner: String,
    val id: ResourceLocation,
    val title: String,
    val factory: FabricScreenFactory,
) {
    val active = AtomicBoolean(true)
    val screens = mutableListOf<WeakReference<GUIElement>>()
}

object FabricScreens {
    private val capabilityProviders = FabricHookRegistry<Unit>("screens")
    private val screens = linkedMapOf<ResourceLocation, FabricScreenRegistration>()

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    @Synchronized
    fun register(owner: String, id: ResourceLocation, title: String, factory: FabricScreenFactory): AutoCloseable {
        require(title.isNotBlank()) { "Fabric screen title must not be blank." }
        val registration = FabricScreenRegistration(owner, id, title, factory)
        require(screens.putIfAbsent(id, registration) == null) { "Fabric screen is already registered: $id" }
        FabricModDiagnostics.hookInstalled(owner, "screen:$id")
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            val openScreens = synchronized(this) {
                screens.remove(id, registration)
                registration.active.set(false)
                synchronized(registration) {
                    registration.screens.mapNotNull(WeakReference<GUIElement>::get).also {
                        registration.screens.clear()
                    }
                }
            }
            for (screen in openScreens) {
                screen.context.queue += { screen.guiRenderer.gui.popIfOpen(screen) }
            }
            FabricModDiagnostics.hookUninstalled(owner, "screen:$id")
        }
    }

    @Synchronized
    fun registrations(): List<FabricScreenRegistration> = screens.values.toList()

    fun open(renderer: GUIRenderer, id: ResourceLocation): Boolean {
        val registration = synchronized(this) { screens[id] } ?: return false
        renderer.context.queue += openScreen@{
            if (!registration.active.get()) return@openScreen
            val started = System.nanoTime()
            try {
                val screen = renderer.gui.push(registration.factory.build(renderer))
                synchronized(registration) {
                    if (registration.active.get()) {
                        registration.screens.removeIf { it.get() == null }
                        registration.screens += WeakReference(screen)
                    } else {
                        renderer.gui.popIfOpen(screen)
                    }
                }
            } catch (error: Throwable) {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                    "FABRIC_SCREEN_FAILED owner=${registration.owner} screen=$id error=${error::class.java.simpleName}: ${error.message}"
                }
            } finally {
                FabricModDiagnostics.hookInvoked(registration.owner, "screen:$id", System.nanoTime() - started)
            }
        }
        return true
    }
}

internal data class FabricHudLayerRegistration(
    val sequence: Long,
    val owner: String,
    val builder: HUDBuilder<*>,
)

internal fun interface FabricHudLayerTarget {
    fun bind(registration: FabricHudLayerRegistration): AutoCloseable
}

internal class FabricHudLayerRegistry {
    private val nextSequence = AtomicLong()
    private val registrations = linkedMapOf<ResourceLocation, FabricHudLayerRegistration>()
    private val targets = linkedMapOf<FabricHudLayerTarget, MutableMap<Long, AutoCloseable>>()

    @Synchronized
    fun register(owner: String, builder: HUDBuilder<*>): AutoCloseable {
        val registration = FabricHudLayerRegistration(nextSequence.incrementAndGet(), owner, builder)
        require(registrations.putIfAbsent(builder.identifier, registration) == null) {
            "Fabric HUD layer is already registered: ${builder.identifier}"
        }
        val attached = mutableListOf<Pair<MutableMap<Long, AutoCloseable>, AutoCloseable>>()
        try {
            for ((target, handles) in targets) {
                val handle = target.bind(registration)
                handles[registration.sequence] = handle
                attached += handles to handle
            }
        } catch (error: Throwable) {
            registrations.remove(builder.identifier, registration)
            for ((handles, handle) in attached.asReversed()) {
                handles.remove(registration.sequence, handle)
                try {
                    handle.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            unregister(registration)
        }
    }

    @Synchronized
    fun attach(target: FabricHudLayerTarget): AutoCloseable {
        require(target !in targets) { "Fabric HUD target is already attached." }
        val handles = linkedMapOf<Long, AutoCloseable>()
        targets[target] = handles
        try {
            for (registration in registrations.values) handles[registration.sequence] = target.bind(registration)
        } catch (error: Throwable) {
            targets.remove(target)
            handles.values.toList().asReversed().forEach { it.close() }
            throw error
        }
        return AutoCloseable { detach(target) }
    }

    @Synchronized
    fun owners(): List<String> = registrations.values.map(FabricHudLayerRegistration::owner)

    @Synchronized
    private fun unregister(registration: FabricHudLayerRegistration) {
        if (!registrations.remove(registration.builder.identifier, registration)) return
        for (handles in targets.values) handles.remove(registration.sequence)?.close()
    }

    @Synchronized
    private fun detach(target: FabricHudLayerTarget) {
        targets.remove(target)?.values?.toList()?.asReversed()?.forEach { it.close() }
    }
}

object FabricHudLayers {
    private val capabilityProviders = FabricHookRegistry<Unit>("hud-layers")
    private val registry = FabricHudLayerRegistry()

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, builder: HUDBuilder<*>): AutoCloseable {
        require(builder.ENABLE_KEY_BINDING == null && builder.ENABLE_KEY_BINDING_NAME == null) {
            "Fabric HUD layers must own toggles through FabricKeyBindings: ${builder.identifier}"
        }
        FabricModDiagnostics.hookInstalled(owner, "hud-layer:${builder.identifier}")
        val registration = try {
            registry.register(owner, builder)
        } catch (error: Throwable) {
            FabricModDiagnostics.hookUninstalled(owner, "hud-layer:${builder.identifier}")
            throw error
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            try {
                registration.close()
            } finally {
                FabricModDiagnostics.hookUninstalled(owner, "hud-layer:${builder.identifier}")
            }
        }
    }

    fun registrations(): List<String> = registry.owners()

    fun attach(manager: HUDManager): AutoCloseable = registry.attach(FabricHudLayerTarget { registration ->
        val active = AtomicBoolean(true)
        val applied = AtomicReference<AutoCloseable?>()
        manager.context.queue += {
            if (active.get()) {
                val started = System.nanoTime()
                try {
                    applied.set(manager.registerFabricLayer(registration.builder))
                } catch (error: Throwable) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                        "FABRIC_HUD_LAYER_FAILED owner=${registration.owner} layer=${registration.builder.identifier} error=${error::class.java.simpleName}: ${error.message}"
                    }
                } finally {
                    FabricModDiagnostics.hookInvoked(registration.owner, "hud-layer:${registration.builder.identifier}", System.nanoTime() - started)
                }
            }
        }
        AutoCloseable {
            if (!active.compareAndSet(true, false)) return@AutoCloseable
            manager.context.queue += { applied.getAndSet(null)?.close() }
        }
    })
}
