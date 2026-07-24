/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

data class FabricHostHook<T : Any>(
    val owner: String,
    val hook: T,
)

class FabricHookRegistry<T : Any>(private val kind: String, private val diagnosticKind: String = kind) {
    private val hooks = linkedMapOf<String, T>()

    @Synchronized
    fun register(owner: String, hook: T): AutoCloseable {
        require(owner.isNotBlank()) { "Fabric $kind hook owner must not be blank." }
        require(hooks.putIfAbsent(owner, hook) == null) { "Fabric $kind hook is already registered by $owner." }
        FabricModDiagnostics.hookInstalled(owner, diagnosticKind)
        return AutoCloseable {
            synchronized(this) {
                if (hooks.remove(owner, hook)) {
                    FabricModDiagnostics.hookUninstalled(owner, diagnosticKind)
                }
            }
        }
    }

    @Synchronized
    fun snapshot(): List<FabricHostHook<T>> = hooks.map { FabricHostHook(it.key, it.value) }
}
