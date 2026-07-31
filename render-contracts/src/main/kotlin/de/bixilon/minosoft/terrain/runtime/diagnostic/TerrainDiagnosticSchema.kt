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

package de.bixilon.minosoft.terrain.runtime.diagnostic

object TerrainDiagnosticSchema {
    const val VERSION: Int = 1
    const val CURSOR_VERSION: Int = 1
    const val REJECTION_VERSION: Int = 1
    const val MAXIMUM_PAGE_COUNT: Int = 1_024
    const val MAXIMUM_CURSOR_LENGTH: Int = 256
    const val MAXIMUM_SUBJECT_LENGTH: Int = 256
}

enum class TerrainDiagnosticCapability {
    ATOMIC_SNAPSHOT,
    BOUNDED_PAGE_LIST,
    COVERAGE_GENERATION,
    MATERIAL_GENERATION,
    OPAQUE_PAGE_CURSOR,
    PIPELINE_GENERATION,
    PROVIDER_DESCRIPTOR,
    RESIDENCY_GENERATION,
    STRUCTURED_REJECTION,
    SUBMISSION_GENERATION,
    WORLD_IDENTITY,
}

class TerrainDiagnosticCapabilities(
    val schemaVersion: Int,
    capabilities: Collection<TerrainDiagnosticCapability>,
    val maximumPageCount: Int,
    val cursorSchemaVersion: Int,
    val rejectionSchemaVersion: Int,
) {
    val capabilities: List<TerrainDiagnosticCapability> =
        java.util.List.copyOf(capabilities.distinct().sortedBy { it.name })

    init {
        require(schemaVersion == TerrainDiagnosticSchema.VERSION) {
            "Unsupported terrain diagnostic schema version: $schemaVersion"
        }
        require(this.capabilities.isNotEmpty()) {
            "Terrain diagnostic capabilities must not be empty"
        }
        require(this.capabilities.size == capabilities.size) {
            "Terrain diagnostic capabilities must not contain duplicates"
        }
        require(maximumPageCount in 1..TerrainDiagnosticSchema.MAXIMUM_PAGE_COUNT) {
            "Terrain diagnostic maximum page count is outside the schema bound"
        }
        require(cursorSchemaVersion == TerrainDiagnosticSchema.CURSOR_VERSION) {
            "Unsupported terrain diagnostic cursor schema version: $cursorSchemaVersion"
        }
        require(rejectionSchemaVersion == TerrainDiagnosticSchema.REJECTION_VERSION) {
            "Unsupported terrain diagnostic rejection schema version: $rejectionSchemaVersion"
        }
    }

    companion object {
        fun current(): TerrainDiagnosticCapabilities = TerrainDiagnosticCapabilities(
            schemaVersion = TerrainDiagnosticSchema.VERSION,
            capabilities = TerrainDiagnosticCapability.entries,
            maximumPageCount = TerrainDiagnosticSchema.MAXIMUM_PAGE_COUNT,
            cursorSchemaVersion = TerrainDiagnosticSchema.CURSOR_VERSION,
            rejectionSchemaVersion = TerrainDiagnosticSchema.REJECTION_VERSION,
        )
    }
}

class TerrainDiagnosticResponse(
    val schemaVersion: Int,
    val capabilities: TerrainDiagnosticCapabilities,
    val snapshot: TerrainAvailableDiagnosticSnapshot,
    unavailable: Collection<TerrainDiagnosticRejection>,
) {
    val unavailable: List<TerrainDiagnosticRejection> =
        java.util.List.copyOf(unavailable.sortedBy { it.subject })

    init {
        require(schemaVersion == TerrainDiagnosticSchema.VERSION) {
            "Unsupported terrain diagnostic response schema version: $schemaVersion"
        }
        require(capabilities.schemaVersion == schemaVersion && snapshot.schemaVersion == schemaVersion) {
            "Terrain diagnostic response components must use the response schema version"
        }
        require(this.unavailable.all { it.subject != null }) {
            "Terrain diagnostic unavailable capabilities must name their capability"
        }
        require(this.unavailable.map { it.subject }.toSet().size == this.unavailable.size) {
            "Terrain diagnostic response contains duplicate unavailable capabilities"
        }
        require(this.unavailable.all { it.code == TerrainDiagnosticRejectionCode.MISSING_CAPABILITY }) {
            "Terrain diagnostic response availability must use missing-capability rejections"
        }
        require(
            this.unavailable.all { rejection ->
                TerrainDiagnosticCapability.valueOf(checkNotNull(rejection.subject)) !in
                    capabilities.capabilities
            },
        ) {
            "Terrain diagnostic response cannot reject an advertised capability"
        }
    }
}

/**
 * Atomic subset returned while production still uses the legacy terrain
 * registry. This deliberately does not weaken or populate the complete
 * [TerrainDiagnosticSnapshot] until its consolidated owners exist.
 */
class TerrainAvailableDiagnosticSnapshot(
    val schemaVersion: Int,
    val diagnosticGeneration: Long,
    providers: Collection<TerrainProviderDiagnosticSnapshot>,
) {
    val providers: List<TerrainProviderDiagnosticSnapshot> =
        java.util.List.copyOf(providers.sortedWith(compareBy({ it.providerId }, { it.generation })))

    init {
        require(schemaVersion == TerrainDiagnosticSchema.VERSION) {
            "Unsupported available terrain diagnostic schema version: $schemaVersion"
        }
        require(diagnosticGeneration >= 0L) {
            "Available terrain diagnostic generation must not be negative"
        }
        require(this.providers.isNotEmpty()) {
            "Available terrain diagnostics must contain at least one provider"
        }
        require(this.providers.map { it.providerId to it.generation }.toSet().size == this.providers.size) {
            "Available terrain diagnostics contain duplicate provider generations"
        }
    }
}

enum class TerrainDiagnosticRejectionCategory {
    PROVIDER,
    CAPABILITY,
    LAYOUT,
    MATERIAL,
    VIEW,
    RESOURCE,
    REQUEST,
    CURSOR,
}

enum class TerrainDiagnosticRejectionCode(
    val category: TerrainDiagnosticRejectionCategory,
) {
    UNSUPPORTED_PROVIDER(TerrainDiagnosticRejectionCategory.PROVIDER),
    MISSING_CAPABILITY(TerrainDiagnosticRejectionCategory.CAPABILITY),
    INCOMPATIBLE_LAYOUT(TerrainDiagnosticRejectionCategory.LAYOUT),
    INCOMPATIBLE_MATERIAL(TerrainDiagnosticRejectionCategory.MATERIAL),
    UNSUPPORTED_VIEW(TerrainDiagnosticRejectionCategory.VIEW),
    RESOURCE_CONFLICT(TerrainDiagnosticRejectionCategory.RESOURCE),
    UNSUPPORTED_SCHEMA(TerrainDiagnosticRejectionCategory.REQUEST),
    WRONG_WORLD_EPOCH(TerrainDiagnosticRejectionCategory.REQUEST),
    INVALID_SELECTOR(TerrainDiagnosticRejectionCategory.REQUEST),
    INVALID_MAXIMUM_COUNT(TerrainDiagnosticRejectionCategory.REQUEST),
    MALFORMED_CURSOR(TerrainDiagnosticRejectionCategory.CURSOR),
    STALE_CURSOR(TerrainDiagnosticRejectionCategory.CURSOR),
    CURSOR_SELECTOR_MISMATCH(TerrainDiagnosticRejectionCategory.CURSOR),
}

data class TerrainDiagnosticRejection(
    val schemaVersion: Int = TerrainDiagnosticSchema.REJECTION_VERSION,
    val code: TerrainDiagnosticRejectionCode,
    val subject: String? = null,
    val diagnosticGeneration: Long? = null,
    val expectedGeneration: Long? = null,
    val actualGeneration: Long? = null,
) {
    val category: TerrainDiagnosticRejectionCategory
        get() = code.category

    init {
        require(schemaVersion == TerrainDiagnosticSchema.REJECTION_VERSION) {
            "Unsupported terrain diagnostic rejection schema version: $schemaVersion"
        }
        require(subject == null || subject.isNotBlank()) {
            "Terrain diagnostic rejection subject must not be blank"
        }
        require(subject == null || subject.length <= TerrainDiagnosticSchema.MAXIMUM_SUBJECT_LENGTH) {
            "Terrain diagnostic rejection subject exceeds the schema bound"
        }
        require(diagnosticGeneration == null || diagnosticGeneration >= 0L) {
            "Terrain diagnostic generation must not be negative"
        }
        require(expectedGeneration == null || expectedGeneration >= 0L) {
            "Expected terrain generation must not be negative"
        }
        require(actualGeneration == null || actualGeneration >= 0L) {
            "Actual terrain generation must not be negative"
        }
        require(
            code != TerrainDiagnosticRejectionCode.STALE_CURSOR ||
                expectedGeneration != null && actualGeneration != null,
        ) {
            "Stale terrain cursor rejection requires expected and actual generations"
        }
    }
}
