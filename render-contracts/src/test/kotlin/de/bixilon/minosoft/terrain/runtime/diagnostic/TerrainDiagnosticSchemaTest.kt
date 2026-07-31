/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.diagnostic

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class TerrainDiagnosticSchemaTest {
    @Test
    fun `current capabilities are explicitly versioned and deterministically ordered`() {
        val capabilities = TerrainDiagnosticCapabilities.current()

        assertEquals(TerrainDiagnosticSchema.VERSION, capabilities.schemaVersion)
        assertEquals(TerrainDiagnosticSchema.CURSOR_VERSION, capabilities.cursorSchemaVersion)
        assertEquals(TerrainDiagnosticSchema.REJECTION_VERSION, capabilities.rejectionSchemaVersion)
        assertEquals(capabilities.capabilities.sortedBy { it.name }, capabilities.capabilities)
        assertEquals(
            golden("terrain-diagnostic-capabilities-v1.json"),
            TerrainDiagnosticCanonicalJson.encode(capabilities),
        )
    }

    @Test
    fun `rejections are bounded structured and schema versioned`() {
        val rejection = TerrainDiagnosticRejection(
            code = TerrainDiagnosticRejectionCode.STALE_CURSOR,
            subject = "page-list",
            diagnosticGeneration = 9L,
            expectedGeneration = 9L,
            actualGeneration = 8L,
        )

        assertEquals(TerrainDiagnosticRejectionCategory.CURSOR, rejection.category)
        assertEquals(
            golden("terrain-diagnostic-rejection-v1.json"),
            TerrainDiagnosticCanonicalJson.encode(rejection),
        )
        assertThrows<IllegalArgumentException> {
            rejection.copy(schemaVersion = TerrainDiagnosticSchema.REJECTION_VERSION + 1)
        }
        assertThrows<IllegalArgumentException> {
            TerrainDiagnosticRejection(
                code = TerrainDiagnosticRejectionCode.STALE_CURSOR,
                expectedGeneration = 1L,
            )
        }
        assertThrows<IllegalArgumentException> {
            TerrainDiagnosticRejection(
                code = TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
                subject = "x".repeat(TerrainDiagnosticSchema.MAXIMUM_SUBJECT_LENGTH + 1),
            )
        }
    }

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/de/bixilon/minosoft/terrain/runtime/diagnostic/$name"))
            .readText()
            .trimEnd()
}
