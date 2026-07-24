/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricClientEventsTest {
    @Test
    fun `owned callbacks preserve order isolate failures and unregister`() {
        val firstAdapter = adapter("test:first")
        val secondAdapter = adapter("test:second")
        val metadata = metadata("event_mod")
        val report = FabricPackReport(
            Path.of("test"),
            metadata.copy(id = "event_pack", name = "Event Pack"),
            listOf(FabricModProbe(metadata, emptySet(), firstAdapter), FabricModProbe(metadata("event_mod_2"), emptySet(), secondAdapter)),
        )
        val calls = mutableListOf<String>()
        val registry = FabricOwnedEventRegistry<Unit>("client-event:test")

        try {
            FabricModDiagnostics.begin(report, 0L)
            val first = registry.register(firstAdapter.id) {
                calls += "first"
                error("expected canary failure")
            }
            val second = registry.register(secondAdapter.id) { calls += "second" }
            val third = registry.register(secondAdapter.id) { calls += "third" }

            registry.dispatch(Unit)
            assertEquals(listOf("first", "second", "third"), calls)

            first.close()
            calls.clear()
            registry.dispatch(Unit)
            assertEquals(listOf("second", "third"), calls)

            val snapshots = requireNotNull(FabricModDiagnostics.snapshot()).mods.associateBy { it.adapterId }
            assertFalse(snapshots.getValue(firstAdapter.id).hooks.single().installed)
            assertEquals(1L, snapshots.getValue(firstAdapter.id).hooks.single().invocations)
            assertTrue(snapshots.getValue(secondAdapter.id).hooks.single().installed)
            assertEquals(4L, snapshots.getValue(secondAdapter.id).hooks.single().invocations)

            second.close()
            var current = requireNotNull(FabricModDiagnostics.snapshot()).mods.associateBy { it.adapterId }
            assertTrue(current.getValue(secondAdapter.id).hooks.single().installed)
            third.close()
            current = requireNotNull(FabricModDiagnostics.snapshot()).mods.associateBy { it.adapterId }
            assertFalse(current.getValue(secondAdapter.id).hooks.single().installed)
            assertTrue(registry.owners().isEmpty())
        } finally {
            FabricModDiagnostics.clear()
        }
    }

    private fun adapter(adapterId: String) = object : FabricCompatibilityAdapter {
        override val id = adapterId
        override val handledBlockers = emptySet<FabricCompatibilityBlocker>()
        override val capabilities = setOf(FabricHostCapability.CLIENT_EVENTS)
        override fun supports(metadata: FabricMetadata) = true
        override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) = Unit
    }

    private fun metadata(id: String) = FabricMetadata(
        id = id,
        version = "1.0.0",
        name = id,
        environment = "client",
        entrypoints = emptySet(),
        dependencies = emptyMap(),
        provides = emptySet(),
        mixins = 0,
        accessWidener = null,
        nestedJarPaths = emptyList(),
        source = "test",
    )
}
