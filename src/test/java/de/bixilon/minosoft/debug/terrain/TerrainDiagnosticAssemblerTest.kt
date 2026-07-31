/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug.terrain

import de.bixilon.minosoft.gui.rendering.chunk.BuiltInChunkTerrainBackend
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCanonicalJson
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainDiagnosticAssemblerTest {
    @Test
    fun `legacy capture maps only frozen provider truth`() {
        val capture = TerrainDiagnosticProviderCapture.capture(
            diagnosticGeneration = 101L,
            providerGeneration = 7L,
            descriptor = descriptor(),
        )
        val response = TerrainDiagnosticAssembler.assemble(capture)
        val provider = response.snapshot.providers.single()

        assertEquals(TerrainDiagnosticAssembler.AVAILABLE_CAPABILITIES, response.capabilities.capabilities.toSet())
        assertEquals("minosoft:built-in-terrain", provider.providerId)
        assertEquals(7L, provider.generation)
        assertEquals(setOf(TerrainDomain.NEAR), provider.descriptor.domains)
        assertEquals(
            setOf(TerrainProviderCapability.AUXILIARY_VIEWS),
            provider.descriptor.capabilities,
        )
        assertEquals(
            BuiltInTerrainVertexLayout.VALUE.id.value,
            provider.descriptor.semanticVertexLayoutId,
        )
        assertEquals(
            TerrainDiagnosticCapability.entries
                .filterNot(TerrainDiagnosticAssembler.AVAILABLE_CAPABILITIES::contains)
                .map { it.name }
                .sorted(),
            response.unavailable.map { it.subject },
        )
        assertTrue(
            response.unavailable.all {
                it.code == TerrainDiagnosticRejectionCode.MISSING_CAPABILITY
            },
        )
    }

    @Test
    fun `provider-only response has deterministic schema without fabricated sections`() {
        val response = TerrainDiagnosticAssembler.assemble(
            TerrainDiagnosticProviderCapture.capture(10L, 2L, descriptor()),
        )
        val first = TerrainDiagnosticCanonicalJson.encode(response)
        val second = TerrainDiagnosticCanonicalJson.encode(response)
        val json = de.bixilon.minosoft.debug.DebugJson.MAPPER.readTree(first)

        assertEquals(first, second)
        assertEquals(10L, json.path("snapshot").path("diagnosticGeneration").longValue())
        assertEquals(1, json.path("snapshot").path("providers").size())
        assertFalse(json.path("snapshot").has("worldIdentity"))
        assertFalse(json.path("snapshot").has("pipeline"))
        assertFalse(json.path("snapshot").has("coverage"))
        assertFalse(json.path("snapshot").has("residency"))
        assertFalse(json.path("snapshot").has("submission"))
        assertNull(
            response.unavailable.firstOrNull {
                it.subject == TerrainDiagnosticCapability.PROVIDER_DESCRIPTOR.name
            },
        )
    }

    private fun descriptor() = TerrainBackendDescriptor(
        owner = BuiltInChunkTerrainBackend.OWNER,
        implementation = "minosoft-built-in-chunk-renderer",
        materials = TerrainMaterialClass.entries.toSet(),
        vertexLayout = BuiltInTerrainVertexLayout.VALUE,
        supportsAuxiliaryViews = true,
    )
}
