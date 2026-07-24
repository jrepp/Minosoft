/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationException
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.debug.ModDebugProvider
import de.bixilon.minosoft.debug.ModDebugRegistrar
import java.util.concurrent.CompletableFuture

class FabricDiagnosticDebugProvider(
    private val id: String,
    private val version: String,
) : ModDebugProvider {
    override fun modId() = id
    override fun providerVersion() = "fabric-adapter-v1"

    override fun register(registrar: ModDebugRegistrar) {
        registrar.operation("summary") { _, _ ->
            val pack = FabricModDiagnostics.snapshot()
                ?: throw DebugOperationException("mod_unloaded", "Fabric pack is not active")
            val mod = pack.mods.firstOrNull { it.id == id && it.version == version }
                ?: throw DebugOperationException("mod_unloaded", "Fabric mod generation is no longer active: $id")
            val result = DebugJson.MAPPER.createObjectNode().apply {
                put("id", mod.id); put("name", mod.name); put("version", mod.version)
                put("adapterId", mod.adapterId); put("status", mod.status.wireName)
                put("trajectory", pack.trajectory); put("generation", pack.generation)
                put("activationNanos", mod.activationNanos); put("failure", mod.failure)
            }
            val hooks = result.putArray("hooks")
            mod.hooks.forEach { hook -> hooks.addObject().apply {
                put("kind", hook.kind); put("installed", hook.installed); put("invocations", hook.invocations)
                put("totalNanos", hook.totalNanos); put("averageNanos", hook.averageNanos); put("maxNanos", hook.maxNanos)
            } }
            CompletableFuture.completedFuture(DebugOperationResult.json(result))
        }
    }
}
