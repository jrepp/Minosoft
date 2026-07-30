/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.container.Container
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import java.util.concurrent.atomic.AtomicBoolean

data class FabricContainerScreenExtensionContext(
    val renderer: GUIRenderer,
    val container: Container,
    val contentSize: Vec2f,
)

data class FabricContainerScreenExtension(
    val offset: Vec2f,
    val element: Element,
    val creativeTabLabel: String? = null,
)

fun interface FabricContainerScreenExtensionFactory {
    fun build(context: FabricContainerScreenExtensionContext): FabricContainerScreenExtension?
}

internal class FabricContainerScreenExtensionRegistration(
    val owner: String,
    val id: ResourceLocation,
    val factory: FabricContainerScreenExtensionFactory,
) {
    val active = AtomicBoolean(true)
    val bindings = mutableSetOf<FabricContainerScreenExtensionBinding>()
}

class FabricContainerScreenExtensionBinding internal constructor(
    private val registration: FabricContainerScreenExtensionRegistration,
    val extension: FabricContainerScreenExtension,
) : AutoCloseable {
    private val open = AtomicBoolean(true)
    val active: Boolean get() = open.get() && registration.active.get()

    internal fun deactivate() {
        if (!open.compareAndSet(true, false)) return
        extension.element.cacheUpToDate = false
        extension.element.onClose()
    }

    override fun close() {
        deactivate()
        synchronized(registration) { registration.bindings -= this }
    }
}

object FabricContainerScreenExtensions {
    private val registrations = linkedMapOf<ResourceLocation, FabricContainerScreenExtensionRegistration>()

    @Synchronized
    fun register(owner: String, id: ResourceLocation, factory: FabricContainerScreenExtensionFactory): AutoCloseable {
        val registration = FabricContainerScreenExtensionRegistration(owner, id, factory)
        require(registrations.putIfAbsent(id, registration) == null) { "Fabric container-screen extension is already registered: $id" }
        FabricModDiagnostics.hookInstalled(owner, "container-screen:$id")
        return AutoCloseable {
            val bindings = synchronized(this) {
                if (!registrations.remove(id, registration)) return@AutoCloseable
                registration.active.set(false)
                synchronized(registration) { registration.bindings.toList() }
            }
            bindings.forEach(FabricContainerScreenExtensionBinding::deactivate)
            FabricModDiagnostics.hookUninstalled(owner, "container-screen:$id")
        }
    }

    internal fun build(context: FabricContainerScreenExtensionContext): List<FabricContainerScreenExtensionBinding> {
        val current = synchronized(this) { registrations.values.toList() }
        return current.mapNotNull { registration ->
            if (!registration.active.get()) return@mapNotNull null
            val started = System.nanoTime()
            val extension = try {
                registration.factory.build(context)
            } finally {
                FabricModDiagnostics.hookInvoked(registration.owner, "container-screen:${registration.id}", System.nanoTime() - started)
            } ?: return@mapNotNull null
            val binding = FabricContainerScreenExtensionBinding(registration, extension)
            synchronized(registration) {
                if (!registration.active.get()) {
                    binding.deactivate()
                    null
                } else {
                    registration.bindings += binding
                    binding
                }
            }
        }
    }

    @Synchronized
    fun registrations(): List<ResourceLocation> = registrations.keys.toList()
}
