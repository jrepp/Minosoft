/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.modding.loader.ModOptions
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricModDiagnosticsTest {
    @Test
    fun `snapshots lifecycle and attributed hook timing`() {
        val adapter = object : FabricCompatibilityAdapter {
            override val id = "test:adapter"
            override val handledBlockers = emptySet<FabricCompatibilityBlocker>()
            override val capabilities = setOf(FabricHostCapability.FRAME_BATCHING)
            override fun supports(metadata: FabricMetadata) = true
            override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) = Unit
        }
        val metadata = FabricMetadata(
            id = "test_mod",
            version = "1.2.3",
            name = "Test Mod",
            environment = "client",
            entrypoints = emptySet(),
            dependencies = emptyMap(),
            provides = emptySet(),
            mixins = 0,
            accessWidener = null,
            nestedJarPaths = emptyList(),
            source = "test",
        )
        val probe = FabricModProbe(metadata, emptySet(), adapter)
        val report = FabricPackReport(Path.of("test"), metadata.copy(id = "test_pack", name = "Test Pack"), listOf(probe))
        val oldTrajectory = ModOptions.trajectory
        val oldGeneration = ModOptions.hotReloadGeneration
        try {
            ModOptions.trajectory = "diagnostics-test"
            ModOptions.hotReloadGeneration = 4
            FabricModDiagnostics.begin(report, 250_000L)
            FabricModDiagnostics.activationStarted(probe)
            FabricModDiagnostics.hookInstalled(adapter.id, "frame-batching")
            FabricModDiagnostics.hookInvoked(adapter.id, "frame-batching", 100L)
            FabricModDiagnostics.hookInvoked(adapter.id, "frame-batching", 300L)
            FabricModDiagnostics.activationCompleted(probe, 900L)
            FabricModDiagnostics.packActivated()

            val snapshot = requireNotNull(FabricModDiagnostics.snapshot())
            val mod = snapshot.mods.single()
            val hook = mod.hooks.single()
            assertEquals("diagnostics-test", snapshot.trajectory)
            assertEquals(4, snapshot.generation)
            assertEquals(250_000L, snapshot.preflightNanos)
            assertTrue(snapshot.active)
            assertEquals(FabricModRuntimeStatus.ACTIVE, mod.status)
            assertEquals(900L, mod.activationNanos)
            assertEquals(2L, hook.invocations)
            assertEquals(200L, hook.averageNanos)
            assertEquals(300L, hook.maxNanos)
            assertTrue(hook.installed)

            FabricModDiagnostics.hookUninstalled(adapter.id, "frame-batching")
            FabricModDiagnostics.packDeactivated()
            val inactive = requireNotNull(FabricModDiagnostics.snapshot())
            assertFalse(inactive.active)
            assertEquals(FabricModRuntimeStatus.INACTIVE, inactive.mods.single().status)
            assertFalse(inactive.mods.single().hooks.single().installed)
        } finally {
            ModOptions.trajectory = oldTrajectory
            ModOptions.hotReloadGeneration = oldGeneration
            FabricModDiagnostics.clear()
        }
    }
}
