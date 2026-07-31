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

import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationRegistry
import de.bixilon.minosoft.gui.rendering.chunk.BuiltInChunkTerrainBackend
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TerrainDiagnosticDebugOperationTest {
    @Test
    fun `operation registers with client ownership and cleans up`() {
        val registry = DebugOperationRegistry()
        val source = source()
        val registration = TerrainDiagnosticDebugOperation.register(registry) { _, body ->
            CompletableFuture.completedFuture(TerrainDiagnosticDebugOperation.execute(source, body))
        }

        val registered = assertNotNull(registry.find(TerrainDiagnosticDebugOperation.NAME))
        assertEquals(TerrainDiagnosticDebugOperation.OWNER, registered.owner())
        val result = registered.handler()
            .handle(null, DebugJson.MAPPER.createObjectNode())
            .toCompletableFuture()
            .join()
            .result()
        assertEquals(1, result.path("schemaVersion").intValue())
        assertEquals(5L, result.path("snapshot").path("diagnosticGeneration").longValue())
        assertFalse(
            result.path("capabilities").path("capabilities")
                .any { it.textValue() == TerrainDiagnosticCapability.BOUNDED_PAGE_LIST.name },
        )

        registration.close()
        assertNull(registry.find(TerrainDiagnosticDebugOperation.NAME))
    }

    @Test
    fun `unsupported page query and unknown request reject structurally`() {
        val pageQuery = DebugJson.MAPPER.createObjectNode().apply { putObject("pageQuery") }
        val pageRejection = TerrainDiagnosticDebugOperation.execute(source(), pageQuery).result()
        assertEquals(
            TerrainDiagnosticRejectionCode.MISSING_CAPABILITY.name,
            pageRejection.path("code").textValue(),
        )
        assertEquals(
            TerrainDiagnosticCapability.BOUNDED_PAGE_LIST.name,
            pageRejection.path("subject").textValue(),
        )

        val unknown = TerrainDiagnosticDebugOperation.execute(
            source(),
            DebugJson.MAPPER.createObjectNode().put("unexpected", true),
        ).result()
        assertEquals(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR.name,
            unknown.path("code").textValue(),
        )
    }

    private fun source() = TerrainDiagnosticProviderCapture.capture(
        diagnosticGeneration = 5L,
        providerGeneration = 3L,
        descriptor = TerrainBackendDescriptor(
            owner = BuiltInChunkTerrainBackend.OWNER,
            implementation = "minosoft-built-in-chunk-renderer",
            materials = TerrainMaterialClass.entries.toSet(),
            vertexLayout = BuiltInTerrainVertexLayout.VALUE,
            supportsAuxiliaryViews = true,
        ),
    )
}
