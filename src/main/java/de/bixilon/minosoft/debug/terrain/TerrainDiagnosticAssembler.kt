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

import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass as LegacyTerrainMaterialClass
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.interop.TerrainLightingSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.model.interop.TerrainTintSemantics
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAvailableDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapabilities
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejection
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticResponse
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticSchema
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainProviderDiagnosticSnapshot

class TerrainDiagnosticProviderCapture(
    val diagnosticGeneration: Long,
    val providerGeneration: Long,
    val providerId: String,
    val physicalLayoutId: String,
    materials: Set<LegacyTerrainMaterialClass>,
    vertexSemantics: Set<String>,
    val supportsAuxiliaryViews: Boolean,
) {
    val materials: Set<LegacyTerrainMaterialClass> =
        java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(materials))
    val vertexSemantics: Set<String> =
        java.util.Collections.unmodifiableSet(java.util.TreeSet(vertexSemantics))

    init {
        require(diagnosticGeneration >= 0L) {
            "Terrain diagnostic capture generation must not be negative"
        }
        require(providerGeneration >= 0L) {
            "Terrain diagnostic provider generation must not be negative"
        }
        require(providerId.isNotBlank()) { "Terrain diagnostic provider ID must not be blank" }
        require(physicalLayoutId.isNotBlank()) {
            "Terrain diagnostic physical-layout ID must not be blank"
        }
        require(materials.isNotEmpty()) { "Terrain diagnostic materials must not be empty" }
        require(vertexSemantics.isNotEmpty() && vertexSemantics.none(String::isBlank)) {
            "Terrain diagnostic vertex semantics must be non-empty and named"
        }
    }

    companion object {
        fun capture(
            diagnosticGeneration: Long,
            providerGeneration: Long,
            descriptor: TerrainBackendDescriptor,
        ): TerrainDiagnosticProviderCapture = TerrainDiagnosticProviderCapture(
            diagnosticGeneration = diagnosticGeneration,
            providerGeneration = providerGeneration,
            providerId = descriptor.owner.value,
            physicalLayoutId = descriptor.vertexLayout.id.value,
            materials = descriptor.materials.toSet(),
            vertexSemantics = descriptor.vertexLayout.attributes
                .mapTo(sortedSetOf()) { it.semantic.name.lowercase() },
            supportsAuxiliaryViews = descriptor.supportsAuxiliaryViews,
        )
    }
}

object TerrainDiagnosticAssembler {
    val AVAILABLE_CAPABILITIES: Set<TerrainDiagnosticCapability> = setOf(
        TerrainDiagnosticCapability.ATOMIC_SNAPSHOT,
        TerrainDiagnosticCapability.PROVIDER_DESCRIPTOR,
        TerrainDiagnosticCapability.STRUCTURED_REJECTION,
    )

    fun assemble(source: TerrainDiagnosticProviderCapture): TerrainDiagnosticResponse {
        val providerCapabilities = if (source.supportsAuxiliaryViews) {
            setOf(TerrainProviderCapability.AUXILIARY_VIEWS)
        } else {
            emptySet()
        }
        val views = if (source.supportsAuxiliaryViews) {
            setOf("main", "shadow")
        } else {
            setOf("main")
        }
        val descriptor = TerrainInteropDescriptor(
            providerId = source.providerId,
            domains = setOf(TerrainDomain.NEAR),
            capabilities = providerCapabilities,
            materials = source.materials.mapTo(linkedSetOf()) {
                TerrainMaterialClass.valueOf(it.name)
            },
            semanticVertexLayoutId = source.physicalLayoutId,
            physicalLayoutIds = setOf(source.physicalLayoutId),
            supportedViews = views,
            uploadCapabilities = emptySet(),
            shaderInputs = source.vertexSemantics,
            lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
            tintSemantics = TerrainTintSemantics.RESOLVED_COLOR,
        )
        val capabilities = TerrainDiagnosticCapabilities(
            schemaVersion = TerrainDiagnosticSchema.VERSION,
            capabilities = AVAILABLE_CAPABILITIES,
            maximumPageCount = TerrainDiagnosticSchema.MAXIMUM_PAGE_COUNT,
            cursorSchemaVersion = TerrainDiagnosticSchema.CURSOR_VERSION,
            rejectionSchemaVersion = TerrainDiagnosticSchema.REJECTION_VERSION,
        )
        val unavailable = TerrainDiagnosticCapability.entries
            .filterNot(AVAILABLE_CAPABILITIES::contains)
            .map {
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.MISSING_CAPABILITY,
                    subject = it.name,
                    diagnosticGeneration = source.diagnosticGeneration,
                )
            }
        return TerrainDiagnosticResponse(
            schemaVersion = TerrainDiagnosticSchema.VERSION,
            capabilities = capabilities,
            snapshot = TerrainAvailableDiagnosticSnapshot(
                schemaVersion = TerrainDiagnosticSchema.VERSION,
                diagnosticGeneration = source.diagnosticGeneration,
                providers = listOf(
                    TerrainProviderDiagnosticSnapshot(
                        providerId = source.providerId,
                        generation = source.providerGeneration,
                        descriptor = descriptor,
                    ),
                ),
            ),
            unavailable = unavailable,
        )
    }
}
