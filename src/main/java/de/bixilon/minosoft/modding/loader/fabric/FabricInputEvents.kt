/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyBinding
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.input.key.manager.InputManager
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class FabricInputEventType(val wireName: String) {
    KEY("key"),
    CHAR("char"),
    MOUSE_MOVE("mouse-move"),
    MOUSE_SCROLL("mouse-scroll"),
}

sealed interface FabricInputEvent {
    val context: RenderContext
    val consumerActive: Boolean
}

data class FabricKeyInput(
    override val context: RenderContext,
    val code: KeyCodes,
    val change: KeyChangeTypes,
    override val consumerActive: Boolean,
) : FabricInputEvent

data class FabricCharInput(
    override val context: RenderContext,
    val codePoint: Int,
    override val consumerActive: Boolean,
) : FabricInputEvent

data class FabricMouseMoveInput(
    override val context: RenderContext,
    val position: Vec2f,
    val delta: Vec2f,
    override val consumerActive: Boolean,
) : FabricInputEvent

data class FabricMouseScrollInput(
    override val context: RenderContext,
    val offset: Vec2f,
    override val consumerActive: Boolean,
) : FabricInputEvent

fun interface FabricInputCallback {
    fun invoke(event: FabricInputEvent)
}

/** Observes normalized host input after it has passed through Minosoft's normal event path. */
object FabricInputEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("input-events")
    private val events = FabricInputEventType.entries.associateWith {
        FabricOwnedEventRegistry<FabricInputEvent>("input-event:${it.wireName}")
    }

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)

    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, type: FabricInputEventType, callback: FabricInputCallback): AutoCloseable =
        events.getValue(type).register(owner, callback::invoke)

    fun registrations(type: FabricInputEventType): List<String> = events.getValue(type).owners()

    fun dispatch(type: FabricInputEventType, event: FabricInputEvent) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "input-events", System.nanoTime() - started)
        }
        events.getValue(type).dispatch(event)
    }
}

data class FabricKeyBindingRegistration internal constructor(
    internal val id: Long,
    val owner: String,
    val name: ResourceLocation,
    val default: KeyBinding,
    val initiallyPressed: Boolean,
    internal val callback: (Boolean) -> Unit,
)

internal fun interface FabricKeyBindingTarget {
    fun bind(registration: FabricKeyBindingRegistration): AutoCloseable
}

/**
 * Keeps definitions independent of a render generation and fans them into each
 * attached input manager. Closing either side removes only its own callbacks.
 */
internal class FabricKeyBindingRegistry {
    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<Long, FabricKeyBindingRegistration>()
    private val targets = linkedMapOf<FabricKeyBindingTarget, MutableMap<Long, AutoCloseable>>()

    @Synchronized
    fun register(
        owner: String,
        name: ResourceLocation,
        default: KeyBinding,
        initiallyPressed: Boolean,
        callback: (Boolean) -> Unit,
    ): AutoCloseable {
        require(owner.isNotBlank()) { "Fabric key binding owner must not be blank." }
        require(name.path.isNotBlank()) { "Fabric key binding path must not be blank." }
        require(registrations.values.none { it.name == name }) { "Fabric key binding is already registered: $name" }
        val registration = FabricKeyBindingRegistration(nextId.incrementAndGet(), owner, name, default, initiallyPressed, callback)
        registrations[registration.id] = registration
        val attached = mutableListOf<Pair<MutableMap<Long, AutoCloseable>, AutoCloseable>>()
        try {
            for ((target, handles) in targets) {
                val handle = target.bind(registration)
                handles[registration.id] = handle
                attached += handles to handle
            }
        } catch (error: Throwable) {
            registrations.remove(registration.id)
            attached.asReversed().forEach { (handles, handle) ->
                handles.remove(registration.id)
                try {
                    handle.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
        return AutoCloseable { unregister(registration) }
    }

    @Synchronized
    fun attach(target: FabricKeyBindingTarget): AutoCloseable {
        require(target !in targets) { "Fabric key binding target is already attached." }
        val handles = linkedMapOf<Long, AutoCloseable>()
        targets[target] = handles
        try {
            for ((id, registration) in registrations) handles[id] = target.bind(registration)
        } catch (error: Throwable) {
            targets.remove(target)
            closeAll(handles.values, error)
            throw error
        }
        return AutoCloseable { detach(target) }
    }

    @Synchronized
    fun owners(): List<String> = registrations.values.map(FabricKeyBindingRegistration::owner)

    @Synchronized
    private fun unregister(registration: FabricKeyBindingRegistration) {
        if (!registrations.remove(registration.id, registration)) return
        var failure: Throwable? = null
        for (handles in targets.values) {
            val handle = handles.remove(registration.id) ?: continue
            try {
                handle.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    private fun detach(target: FabricKeyBindingTarget) {
        val handles = targets.remove(target) ?: return
        closeAll(handles.values)
    }

    private fun closeAll(handles: Collection<AutoCloseable>, parent: Throwable? = null) {
        var failure: Throwable? = null
        for (handle in handles.toList().asReversed()) {
            try {
                handle.close()
            } catch (error: Throwable) {
                if (parent != null) parent.addSuppressed(error)
                else failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (parent == null) failure?.let { throw it }
    }
}

object FabricKeyBindings {
    private val capabilityProviders = FabricHookRegistry<Unit>("key-bindings")
    private val registry = FabricKeyBindingRegistry()

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)

    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(
        owner: String,
        name: ResourceLocation,
        default: KeyBinding,
        initiallyPressed: Boolean = false,
        callback: (Boolean) -> Unit,
    ): AutoCloseable {
        val kind = "key-binding:$name"
        FabricModDiagnostics.hookInstalled(owner, kind)
        val registration = try {
            registry.register(owner, name, KeyBinding(default.action, default.ignoreConsumer), initiallyPressed) { pressed ->
                val started = System.nanoTime()
                try {
                    callback(pressed)
                } catch (error: Throwable) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                        "FABRIC_KEY_BINDING_FAILED owner=$owner binding=$name error=${error::class.java.simpleName}: ${error.message}"
                    }
                } finally {
                    FabricModDiagnostics.hookInvoked(owner, kind, System.nanoTime() - started)
                }
            }
        } catch (error: Throwable) {
            FabricModDiagnostics.hookUninstalled(owner, kind)
            throw error
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            try {
                registration.close()
            } finally {
                FabricModDiagnostics.hookUninstalled(owner, kind)
            }
        }
    }

    fun registrations(): List<String> = registry.owners()

    fun attach(input: InputManager): AutoCloseable = registry.attach(FabricKeyBindingTarget { registration ->
        input.bindings.register(
            registration.name,
            KeyBinding(registration.default.action, registration.default.ignoreConsumer),
            registration.initiallyPressed,
            registration.callback,
        )
    })
}
