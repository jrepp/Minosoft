/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.debug.ClientDebugChannel
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.nio.file.Path

data class FabricPackActivation(
    val report: FabricPackReport,
    val scope: FabricRegistrationScope,
)

object FabricPackLoader {
    private var active: FabricPackActivation? = null

    @Synchronized
    fun activate(root: Path): FabricPackActivation {
        check(active == null) { "A Fabric pack is already active." }
        val preflightStarted = System.nanoTime()
        val report = FabricPackPreflight.inspect(root)
        FabricModDiagnostics.begin(report, System.nanoTime() - preflightStarted)
        report.log()
        require(report.launchable) { "Fabric pack ${report.pack.id} is blocked: ${report.mods.flatMap { it.blockers }.distinct()}" }

        val scope = FabricRegistrationScope()
        try {
            for (probe in report.activatableMods) {
                val adapter = requireNotNull(probe.adapter) { "No direct Fabric activation path exists for ${probe.metadata.id}." }
                FabricModDiagnostics.activationStarted(probe)
                val started = System.nanoTime()
                try {
                    adapter.activate(probe, scope)
                    scope.own(ClientDebugChannel.register(FabricDiagnosticDebugProvider(probe.metadata.id, probe.metadata.version)))
                    FabricModDiagnostics.activationCompleted(probe, System.nanoTime() - started)
                } catch (error: Throwable) {
                    FabricModDiagnostics.activationFailed(probe, System.nanoTime() - started, error)
                    throw error
                }
            }
            return FabricPackActivation(report, scope).also { active = it }.also {
                FabricModDiagnostics.packActivated()
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "FABRIC_PACK_ACTIVE pack=${report.pack.id} mode=${report.activation.name.lowercase()} mods=${report.activatableMods.joinToString { probe -> probe.metadata.id }} blocked=${report.blockedMods.joinToString { probe -> probe.metadata.id }}"
                }
            }
        } catch (error: Throwable) {
            try {
                scope.close()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    @Synchronized
    fun deactivate() {
        val activation = active ?: return
        active = null
        try {
            activation.scope.close()
        } finally {
            FabricModDiagnostics.packDeactivated()
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "FABRIC_PACK_INACTIVE pack=${activation.report.pack.id} mods=${activation.report.mods.joinToString { it.metadata.id }}"
            }
        }
    }

    @Synchronized
    fun current(): FabricPackActivation? = active
}
