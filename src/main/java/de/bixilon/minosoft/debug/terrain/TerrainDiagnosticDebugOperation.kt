/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.debug.terrain

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationHandler
import de.bixilon.minosoft.debug.DebugOperationRegistry
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCanonicalJson
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejection
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode

object TerrainDiagnosticDebugOperation {
    const val NAME: String = "render.terrain-diagnostics"
    const val OWNER: String = "client"

    fun register(
        registry: DebugOperationRegistry,
        handler: DebugOperationHandler,
    ): AutoCloseable = registry.register(OWNER, NAME, handler)

    fun execute(
        source: TerrainDiagnosticProviderCapture,
        body: JsonNode,
    ): DebugOperationResult {
        if (!body.isObject || !body.isEmpty) {
            val rejection = if (body.isObject && body.has("pageQuery")) {
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.MISSING_CAPABILITY,
                    subject = TerrainDiagnosticCapability.BOUNDED_PAGE_LIST.name,
                    diagnosticGeneration = source.diagnosticGeneration,
                )
            } else {
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
                    subject = "request",
                    diagnosticGeneration = source.diagnosticGeneration,
                )
            }
            return canonicalResult(TerrainDiagnosticCanonicalJson.encode(rejection))
        }
        return canonicalResult(
            TerrainDiagnosticCanonicalJson.encode(TerrainDiagnosticAssembler.assemble(source)),
        )
    }

    fun unavailableProvider(diagnosticGeneration: Long): DebugOperationResult = canonicalResult(
        TerrainDiagnosticCanonicalJson.encode(
            TerrainDiagnosticRejection(
                code = TerrainDiagnosticRejectionCode.RESOURCE_CONFLICT,
                subject = TerrainDiagnosticCapability.PROVIDER_DESCRIPTOR.name,
                diagnosticGeneration = diagnosticGeneration,
            ),
        ),
    )

    private fun canonicalResult(json: String): DebugOperationResult =
        DebugOperationResult.json(DebugJson.MAPPER.readTree(json))
}
