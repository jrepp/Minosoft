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

import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainPhysicalLayout
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.identity.TerrainWorldIdentity
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainResidencyCapSnapshot

object TerrainDiagnosticCanonicalJson {
    fun encode(capabilities: TerrainDiagnosticCapabilities): String = capabilities(capabilities).encode()

    fun encode(rejection: TerrainDiagnosticRejection): String = rejection(rejection).encode()

    fun encode(response: TerrainDiagnosticResponse): String = jsonObject(
        "capabilities" to capabilities(response.capabilities),
        "schemaVersion" to jsonNumber(response.schemaVersion),
        "snapshot" to availableSnapshot(response.snapshot),
        "unavailable" to jsonArray(response.unavailable.map(::rejection)),
    ).encode()

    fun encode(snapshot: TerrainDiagnosticSnapshot): String = snapshot(snapshot).encode()

    private fun capabilities(capabilities: TerrainDiagnosticCapabilities): JsonValue = jsonObject(
        "capabilities" to jsonArray(capabilities.capabilities.map { jsonString(it.name) }),
        "cursorSchemaVersion" to jsonNumber(capabilities.cursorSchemaVersion),
        "maximumPageCount" to jsonNumber(capabilities.maximumPageCount),
        "rejectionSchemaVersion" to jsonNumber(capabilities.rejectionSchemaVersion),
        "schemaVersion" to jsonNumber(capabilities.schemaVersion),
    )

    private fun rejection(rejection: TerrainDiagnosticRejection): JsonValue = jsonObject(
        "actualGeneration" to jsonNullableNumber(rejection.actualGeneration),
        "category" to jsonString(rejection.category.name),
        "code" to jsonString(rejection.code.name),
        "diagnosticGeneration" to jsonNullableNumber(rejection.diagnosticGeneration),
        "expectedGeneration" to jsonNullableNumber(rejection.expectedGeneration),
        "schemaVersion" to jsonNumber(rejection.schemaVersion),
        "subject" to jsonNullableString(rejection.subject),
    )

    private fun availableSnapshot(snapshot: TerrainAvailableDiagnosticSnapshot): JsonValue = jsonObject(
        "diagnosticGeneration" to jsonNumber(snapshot.diagnosticGeneration),
        "providers" to jsonArray(snapshot.providers.map(::provider)),
        "schemaVersion" to jsonNumber(snapshot.schemaVersion),
    )

    private fun snapshot(snapshot: TerrainDiagnosticSnapshot): JsonValue = jsonObject(
        "coverage" to coverage(snapshot.coverage),
        "diagnosticGeneration" to jsonNumber(snapshot.diagnosticGeneration),
        "material" to material(snapshot.material),
        "pageRegistryGeneration" to jsonNumber(snapshot.pageRegistryGeneration),
        "pageWindow" to snapshot.pageWindow?.let(::pageWindow).orJsonNull(),
        "pipeline" to pipeline(snapshot.pipeline),
        "providers" to jsonArray(snapshot.providers.map(::provider)),
        "residency" to jsonArray(snapshot.residency.map(::residency)),
        "schemaVersion" to jsonNumber(snapshot.schemaVersion),
        "submission" to submission(snapshot.submission),
        "worldIdentity" to worldIdentity(snapshot.worldIdentity),
    )

    private fun provider(snapshot: TerrainProviderDiagnosticSnapshot): JsonValue = jsonObject(
        "descriptor" to interopDescriptor(snapshot.descriptor),
        "generation" to jsonNumber(snapshot.generation),
        "providerId" to jsonString(snapshot.providerId),
    )

    private fun interopDescriptor(descriptor: TerrainInteropDescriptor): JsonValue = jsonObject(
        "capabilities" to jsonArray(descriptor.capabilities.map { it.name }.sorted().map(::jsonString)),
        "domains" to jsonArray(descriptor.domains.map { it.name }.sorted().map(::jsonString)),
        "foreignProfileIds" to jsonArray(descriptor.foreignProfileIds.sorted().map(::jsonString)),
        "lightingSemantics" to jsonString(descriptor.lightingSemantics.name),
        "materials" to jsonArray(descriptor.materials.map { it.name }.sorted().map(::jsonString)),
        "physicalLayoutIds" to jsonArray(descriptor.physicalLayoutIds.sorted().map(::jsonString)),
        "providerId" to jsonString(descriptor.providerId),
        "semanticVertexLayoutId" to jsonString(descriptor.semanticVertexLayoutId),
        "shaderInputs" to jsonArray(descriptor.shaderInputs.sorted().map(::jsonString)),
        "supportedViews" to jsonArray(descriptor.supportedViews.sorted().map(::jsonString)),
        "tintSemantics" to jsonString(descriptor.tintSemantics.name),
        "uploadCapabilities" to
            jsonArray(descriptor.uploadCapabilities.map { it.name }.sorted().map(::jsonString)),
    )

    private fun worldIdentity(identity: TerrainWorldIdentity): JsonValue = jsonObject(
        "contentGeneration" to jsonNumber(identity.contentGeneration),
        "maximumHeightExclusive" to jsonNumber(identity.maximumHeightExclusive),
        "minimumHeight" to jsonNumber(identity.minimumHeight),
        "normalizedWorldKey" to jsonString(identity.normalizedWorldKey),
        "persistenceIdentity" to jsonNullableString(identity.persistenceIdentity),
        "sessionGeneration" to jsonNumber(identity.sessionGeneration),
        "worldEpoch" to jsonNumber(identity.worldEpoch),
    )

    private fun pipeline(snapshot: TerrainPipelineDiagnosticSnapshot): JsonValue = jsonObject(
        "declaredMaterialPasses" to jsonArray(snapshot.declaredMaterialPasses.map(::jsonString)),
        "declaredViews" to jsonArray(snapshot.declaredViews.map(::jsonString)),
        "distantLayout" to snapshot.distantLayout?.let(::physicalLayout).orJsonNull(),
        "distantProviderGeneration" to jsonNullableNumber(snapshot.distantProviderGeneration),
        "distantProviderId" to jsonNullableString(snapshot.distantProviderId),
        "generation" to jsonNumber(snapshot.generation),
        "materialGeneration" to jsonNumber(snapshot.materialGeneration),
        "mode" to jsonString(snapshot.mode.name),
        "nearLayout" to physicalLayout(snapshot.nearLayout),
        "nearProviderGeneration" to jsonNumber(snapshot.nearProviderGeneration),
        "nearProviderId" to jsonString(snapshot.nearProviderId),
        "renderGraphGeneration" to jsonNumber(snapshot.renderGraphGeneration),
        "shaderPipelineGeneration" to jsonNumber(snapshot.shaderPipelineGeneration),
    )

    private fun physicalLayout(layout: TerrainPhysicalLayout): JsonValue = jsonObject(
        "domain" to jsonString(layout.domain.name),
        "generation" to jsonNumber(layout.generation),
        "id" to jsonString(layout.id),
    )

    private fun material(snapshot: TerrainMaterialDiagnosticSnapshot): JsonValue = jsonObject(
        "animationGeneration" to jsonNumber(snapshot.animationGeneration),
        "atlasGeneration" to jsonNumber(snapshot.atlasGeneration),
        "generation" to jsonNumber(snapshot.generation),
        "modelGeneration" to jsonNumber(snapshot.modelGeneration),
        "semanticMaterialIds" to
            jsonArray(snapshot.semanticMaterialIds.map { jsonString(it.value) }),
        "tintGeneration" to jsonNumber(snapshot.tintGeneration),
    )

    private fun coverage(snapshot: TerrainCoverageDiagnosticSnapshot): JsonValue = jsonObject(
        "generation" to jsonNumber(snapshot.generation),
        "pageCount" to jsonNumber(snapshot.pageCount),
        "providerGeneration" to jsonNumber(snapshot.providerGeneration),
        "stateCounts" to jsonObject(
            *snapshot.stateCounts.entries
                .map { it.key.name to jsonNumber(it.value) }
                .toTypedArray(),
        ),
        "worldEpoch" to jsonNumber(snapshot.worldEpoch),
    )

    private fun residency(snapshot: TerrainResidencyDiagnosticSnapshot): JsonValue = jsonObject(
        "accounting" to residencyAccounting(snapshot.accounting),
        "generation" to jsonNumber(snapshot.generation),
        "scope" to jsonString(snapshot.scope.name),
    )

    private fun residencyAccounting(snapshot: TerrainResidencyCapSnapshot): JsonValue = jsonObject(
        "accountedBytes" to jsonNumber(snapshot.accountedBytes),
        "availableBytes" to jsonNumber(snapshot.availableBytes),
        "capBytes" to jsonNumber(snapshot.capBytes),
        "categories" to jsonArray(
            snapshot.accounting.map {
                jsonObject(
                    "accountedBytes" to jsonNumber(it.accountedBytes),
                    "category" to jsonString(it.category.name),
                    "pinnedBytes" to jsonNumber(it.pinnedBytes),
                    "residentBytes" to jsonNumber(it.residentBytes),
                    "retiredBytes" to jsonNumber(it.retiredBytes),
                )
            },
        ),
        "deferralCount" to jsonNumber(snapshot.deferralCount),
        "evictedBytes" to jsonNumber(snapshot.evictedBytes),
        "evictionCount" to jsonNumber(snapshot.evictionCount),
        "hardFailureCount" to jsonNumber(snapshot.hardFailureCount),
        "highWaterMarkBytes" to jsonNumber(snapshot.highWaterMarkBytes),
        "pinnedBytes" to jsonNumber(snapshot.pinnedBytes),
        "residentBytes" to jsonNumber(snapshot.residentBytes),
        "retiredBytes" to jsonNumber(snapshot.retiredBytes),
        "scope" to jsonString(snapshot.scope.name),
    )

    private fun submission(snapshot: TerrainSubmissionDiagnosticSnapshot): JsonValue = jsonObject(
        "completionGeneration" to jsonNumber(snapshot.completionGeneration),
        "deviceRuntime" to deviceRuntime(snapshot.deviceRuntime),
        "failedSubmissionCount" to jsonNumber(snapshot.failedSubmissionCount),
        "generation" to jsonNumber(snapshot.generation),
        "latestSubmittedSerial" to jsonNullableNumber(snapshot.latestSubmittedSerial?.value),
        "pendingSubmissionCount" to jsonNumber(snapshot.pendingSubmissionCount),
    )

    private fun deviceRuntime(scope: TerrainDeviceRuntimeId): JsonValue = jsonObject(
        "deviceGeneration" to jsonNumber(scope.deviceGeneration),
        "processGeneration" to jsonNumber(scope.process.generation),
    )

    private fun pageWindow(window: TerrainPageDiagnosticWindow): JsonValue = jsonObject(
        "nextCursor" to jsonNullableString(window.nextCursor?.opaqueValue),
        "pages" to jsonArray(window.pages.map(::page)),
        "query" to pageQuery(window.query),
    )

    private fun pageQuery(query: TerrainPageQuery): JsonValue = jsonObject(
        "cursor" to jsonNullableString(query.cursor?.opaqueValue),
        "maximumCount" to jsonNumber(query.maximumCount),
        "selector" to pageSelector(query.selector),
        "worldEpoch" to jsonNumber(query.worldEpoch),
    )

    private fun pageSelector(selector: TerrainPageSelector): JsonValue = when (selector) {
        is TerrainPageAreaSelector -> jsonObject(
            "domain" to jsonString(selector.domain.name),
            "maximumDetailLevel" to jsonNumber(selector.maximumDetailLevel),
            "maximumX" to jsonNumber(selector.maximumX),
            "maximumY" to jsonNumber(selector.maximumY),
            "maximumZ" to jsonNumber(selector.maximumZ),
            "minimumDetailLevel" to jsonNumber(selector.minimumDetailLevel),
            "minimumX" to jsonNumber(selector.minimumX),
            "minimumY" to jsonNumber(selector.minimumY),
            "minimumZ" to jsonNumber(selector.minimumZ),
            "type" to jsonString("AREA"),
        )

        is TerrainPagePrefixSelector -> jsonObject(
            "detailLevel" to jsonNumber(selector.detailLevel),
            "domain" to jsonString(selector.domain.name),
            "type" to jsonString("PREFIX"),
            "x" to jsonNullableNumber(selector.x),
            "y" to jsonNullableNumber(selector.y),
            "z" to jsonNullableNumber(selector.z),
        )
    }

    private fun page(snapshot: TerrainPageDiagnosticSnapshot): JsonValue = jsonObject(
        "buildIdentity" to snapshot.buildIdentity?.let(::buildIdentity).orJsonNull(),
        "coverageState" to jsonString(snapshot.coverageState.name),
        "page" to pageKey(snapshot.page),
        "providerId" to jsonNullableString(snapshot.providerId),
    )

    private fun buildIdentity(identity: TerrainBuildIdentity): JsonValue = jsonObject(
        "capturedModelRevision" to jsonNumber(identity.capturedModelRevision),
        "coverageGeneration" to jsonNumber(identity.coverageGeneration),
        "layoutGeneration" to jsonNumber(identity.layoutGeneration),
        "materialGeneration" to jsonNumber(identity.materialGeneration),
        "page" to pageKey(identity.page),
        "prioritySequence" to jsonNumber(identity.prioritySequence),
        "providerGeneration" to jsonNumber(identity.providerGeneration),
        "requestRevision" to jsonNumber(identity.requestRevision),
        "sourceDataRevision" to jsonNullableNumber(identity.sourceDataRevision),
    )

    private fun pageKey(page: TerrainPageKey): JsonValue = jsonObject(
        "detailLevel" to jsonNumber(page.detailLevel),
        "domain" to jsonString(page.domain.name),
        "worldEpoch" to jsonNumber(page.worldEpoch),
        "x" to jsonNumber(page.x),
        "y" to jsonNumber(page.y),
        "z" to jsonNumber(page.z),
    )

    private sealed interface JsonValue {
        fun appendTo(target: StringBuilder)

        fun encode(): String = buildString { appendTo(this) }
    }

    private data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
        override fun appendTo(target: StringBuilder) {
            target.append('{')
            var separator = false
            for ((name, value) in fields.toSortedMap()) {
                if (separator) target.append(',')
                separator = true
                JsonString(name).appendTo(target)
                target.append(':')
                value.appendTo(target)
            }
            target.append('}')
        }
    }

    private data class JsonArray(val values: List<JsonValue>) : JsonValue {
        override fun appendTo(target: StringBuilder) {
            target.append('[')
            values.forEachIndexed { index, value ->
                if (index > 0) target.append(',')
                value.appendTo(target)
            }
            target.append(']')
        }
    }

    private data class JsonString(val value: String) : JsonValue {
        override fun appendTo(target: StringBuilder) {
            target.append('"')
            for (character in value) {
                when (character) {
                    '"' -> target.append("\\\"")
                    '\\' -> target.append("\\\\")
                    '\b' -> target.append("\\b")
                    '\u000C' -> target.append("\\f")
                    '\n' -> target.append("\\n")
                    '\r' -> target.append("\\r")
                    '\t' -> target.append("\\t")
                    else -> {
                        if (character < ' ' || character.isSurrogate()) {
                            target.append("\\u")
                            target.append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            target.append(character)
                        }
                    }
                }
            }
            target.append('"')
        }
    }

    private data class JsonNumber(val value: Long) : JsonValue {
        override fun appendTo(target: StringBuilder) {
            target.append(value)
        }
    }

    private data object JsonNull : JsonValue {
        override fun appendTo(target: StringBuilder) {
            target.append("null")
        }
    }

    private fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue =
        JsonObject(fields.toMap())

    private fun jsonArray(values: List<JsonValue>): JsonValue = JsonArray(values)

    private fun jsonString(value: String): JsonValue = JsonString(value)

    private fun jsonNumber(value: Int): JsonValue = JsonNumber(value.toLong())

    private fun jsonNumber(value: Long): JsonValue = JsonNumber(value)

    private fun jsonNullableNumber(value: Long?): JsonValue =
        value?.let(::jsonNumber) ?: JsonNull

    private fun jsonNullableString(value: String?): JsonValue =
        value?.let(::jsonString) ?: JsonNull

    private fun JsonValue?.orJsonNull(): JsonValue = this ?: JsonNull
}
