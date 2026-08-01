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
import com.fasterxml.jackson.databind.node.ObjectNode
import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationHandler
import de.bixilon.minosoft.debug.DebugOperationRegistry
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAcceptanceFault
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCanonicalJson
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapability
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticCapabilities
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticOperations
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejection
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticRejectionCode
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticSchema
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDiagnosticSnapshot
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDualBuildFixture
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageAreaSelector
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageCursor
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPagePrefixSelector
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageQuery
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageSelector

internal sealed interface TerrainDiagnosticRequestResolution {
    data class Accepted(val query: TerrainPageQuery?) : TerrainDiagnosticRequestResolution

    data class Rejected(val rejection: TerrainDiagnosticRejection) : TerrainDiagnosticRequestResolution
}

internal enum class TerrainFlushIdleCondition {
    BUILDS,
    UPLOADS,
    RETIREMENT,
    ALL,
}

internal data class TerrainFlushIdleRequest(
    val condition: TerrainFlushIdleCondition,
    val timeoutMillis: Long,
)

internal enum class TerrainFaultAction {
    ARM,
    RESTORE,
    STATUS,
}

internal data class TerrainFaultRequest(
    val action: TerrainFaultAction,
    val fault: TerrainAcceptanceFault? = null,
    val restorationToken: String? = null,
)

internal data class TerrainIdleState(
    val queuedBuilds: Int,
    val outstandingBuilds: Int,
    val activeBuilds: Int,
    val completionDepth: Int,
    val pendingUploads: Int,
    val pendingSubmissions: Int,
    val pendingFences: Int,
    val retiredPages: Int,
    val retiredBytes: Long,
) {
    init {
        require(
            listOf(
                queuedBuilds,
                outstandingBuilds,
                activeBuilds,
                completionDepth,
                pendingUploads,
                pendingSubmissions,
                pendingFences,
                retiredPages,
            ).all { it >= 0 } && retiredBytes >= 0L,
        ) { "Terrain idle state counters must not be negative" }
    }

    fun matches(condition: TerrainFlushIdleCondition): Boolean = when (condition) {
        TerrainFlushIdleCondition.BUILDS ->
            queuedBuilds == 0 && outstandingBuilds == 0 && activeBuilds == 0 && completionDepth == 0
        TerrainFlushIdleCondition.UPLOADS ->
            pendingUploads == 0
        TerrainFlushIdleCondition.RETIREMENT ->
            retiredPages == 0 && retiredBytes == 0L
        TerrainFlushIdleCondition.ALL ->
            matches(TerrainFlushIdleCondition.BUILDS) &&
                matches(TerrainFlushIdleCondition.UPLOADS) &&
                matches(TerrainFlushIdleCondition.RETIREMENT)
    }
}

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

    internal fun execute(snapshot: TerrainDiagnosticSnapshot): DebugOperationResult = canonicalResult(
        TerrainDiagnosticCanonicalJson.encode(TerrainDiagnosticAssembler.assemble(snapshot)),
    )

    internal fun operationResult(
        operation: String,
        endpointGeneration: Int,
        snapshot: TerrainDiagnosticSnapshot,
    ): DebugOperationResult {
        require(operation in TerrainDiagnosticOperations.schemaVersions) {
            "Unsupported terrain diagnostic operation: $operation"
        }
        require(endpointGeneration >= 0) { "Terrain diagnostic endpoint generation must not be negative" }
        val canonicalSnapshot = DebugJson.MAPPER.readTree(TerrainDiagnosticCanonicalJson.encode(snapshot))
        val result = operationEnvelope(operation, endpointGeneration, snapshot).apply {
            when (operation) {
                TerrainDiagnosticOperations.SUMMARY -> set<JsonNode>("snapshot", canonicalSnapshot)
                TerrainDiagnosticOperations.PAGES -> set<JsonNode>("pageWindow", canonicalSnapshot.path("pageWindow"))
                TerrainDiagnosticOperations.COVERAGE -> {
                    set<JsonNode>("coverage", canonicalSnapshot.path("coverage"))
                    set<JsonNode>("publication", canonicalSnapshot.path("publication"))
                }
                TerrainDiagnosticOperations.PAGE -> {
                    val pages = canonicalSnapshot.path("pageWindow").path("pages")
                    put("found", pages.isArray && !pages.isEmpty)
                    set<JsonNode>("page", pages.firstOrNull() ?: DebugJson.MAPPER.nullNode())
                }
                else -> throw IllegalArgumentException("Operation requires a specialized runtime result: $operation")
            }
        }
        return DebugOperationResult.json(result)
    }

    internal fun specializedOperationResult(
        operation: String,
        endpointGeneration: Int,
        snapshot: TerrainDiagnosticSnapshot,
        populate: ObjectNode.() -> Unit,
    ): DebugOperationResult = DebugOperationResult.json(
        operationEnvelope(operation, endpointGeneration, snapshot).apply(populate),
    )

    private fun operationEnvelope(
        operation: String,
        endpointGeneration: Int,
        snapshot: TerrainDiagnosticSnapshot,
    ): ObjectNode {
        require(operation in TerrainDiagnosticOperations.schemaVersions) {
            "Unsupported terrain diagnostic operation: $operation"
        }
        require(endpointGeneration >= 0) { "Terrain diagnostic endpoint generation must not be negative" }
        return DebugJson.MAPPER.createObjectNode().apply {
            put("schemaVersion", TerrainDiagnosticSchema.VERSION)
            put("operation", operation)
            put("endpointGeneration", endpointGeneration)
            put("frame", snapshot.diagnosticGeneration)
            put("worldEpoch", snapshot.worldIdentity.worldEpoch)
            put("providerGeneration", snapshot.pipeline.nearProviderGeneration)
            put("distantProviderGeneration", snapshot.pipeline.distantProviderGeneration)
            put("shaderGeneration", snapshot.pipeline.shaderPipelineGeneration)
            put("layoutGeneration", snapshot.pipeline.nearLayout.generation)
            put("distantLayoutGeneration", snapshot.pipeline.distantLayout?.generation)
            set<JsonNode>(
                "capabilities",
                DebugJson.MAPPER.readTree(
                    TerrainDiagnosticCanonicalJson.encode(TerrainDiagnosticCapabilities.current()),
                ),
            )
        }
    }

    internal fun resolveRequest(
        body: JsonNode,
        diagnosticGeneration: Long,
        worldEpoch: Long,
    ): TerrainDiagnosticRequestResolution {
        if (!body.isObject) return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "request",
            diagnosticGeneration,
        )
        if (body.isEmpty) return TerrainDiagnosticRequestResolution.Accepted(null)
        if (body.size() != 1 || !body.has("pageQuery")) return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "request",
            diagnosticGeneration,
        )
        val queryNode = body.path("pageQuery")
        if (
            !queryNode.isObject ||
            !hasOnly(queryNode, "worldEpoch", "maximumCount", "cursor", "selector") ||
            !hasEvery(queryNode, "worldEpoch", "maximumCount", "selector")
        ) {
            return rejected(TerrainDiagnosticRejectionCode.INVALID_SELECTOR, "pageQuery", diagnosticGeneration)
        }
        val requestedEpoch = queryNode.path("worldEpoch").exactLong() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "pageQuery.worldEpoch",
            diagnosticGeneration,
        )
        if (requestedEpoch < 0L) return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "pageQuery.worldEpoch",
            diagnosticGeneration,
        )
        if (requestedEpoch != worldEpoch) {
            return TerrainDiagnosticRequestResolution.Rejected(
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.WRONG_WORLD_EPOCH,
                    subject = "pageQuery.worldEpoch",
                    diagnosticGeneration = diagnosticGeneration,
                    expectedGeneration = worldEpoch,
                    actualGeneration = requestedEpoch,
                ),
            )
        }
        val maximumCount = queryNode.path("maximumCount").exactInt() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_MAXIMUM_COUNT,
            "pageQuery.maximumCount",
            diagnosticGeneration,
        )
        if (maximumCount !in 1..TerrainDiagnosticSchema.MAXIMUM_PAGE_COUNT) {
            return rejected(
                TerrainDiagnosticRejectionCode.INVALID_MAXIMUM_COUNT,
                "pageQuery.maximumCount",
                diagnosticGeneration,
            )
        }
        val selector = parseSelector(queryNode.path("selector")) ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "pageQuery.selector",
            diagnosticGeneration,
        )
        val cursorNode = queryNode.get("cursor")
        val cursor = when {
            cursorNode == null || cursorNode.isNull -> null
            !cursorNode.isTextual -> return rejected(
                TerrainDiagnosticRejectionCode.MALFORMED_CURSOR,
                "pageQuery.cursor",
                diagnosticGeneration,
            )
            else -> runCatching { TerrainPageCursor(cursorNode.textValue()) }.getOrElse {
                return rejected(
                    TerrainDiagnosticRejectionCode.MALFORMED_CURSOR,
                    "pageQuery.cursor",
                    diagnosticGeneration,
                )
            }
        }
        val query = TerrainPageQuery(requestedEpoch, selector, maximumCount, cursor)
        return TerrainDiagnosticRequestResolution.Accepted(query)
    }

    internal fun resolvePageRequest(
        body: JsonNode,
        diagnosticGeneration: Long,
        worldEpoch: Long,
    ): TerrainDiagnosticRequestResolution {
        if (!body.isObject || !hasExactly(body, "worldEpoch", "page")) return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "request",
            diagnosticGeneration,
        )
        val requestedEpoch = body.path("worldEpoch").exactLong() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "worldEpoch",
            diagnosticGeneration,
        )
        if (requestedEpoch != worldEpoch) {
            return TerrainDiagnosticRequestResolution.Rejected(
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.WRONG_WORLD_EPOCH,
                    subject = "worldEpoch",
                    diagnosticGeneration = diagnosticGeneration,
                    expectedGeneration = worldEpoch,
                    actualGeneration = requestedEpoch,
                ),
            )
        }
        val page = body.path("page")
        if (!page.isObject || !hasExactly(page, "domain", "detailLevel", "x", "y", "z")) return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "page",
            diagnosticGeneration,
        )
        val domain = page.path("domain").takeIf(JsonNode::isTextual)?.textValue()?.let {
            runCatching { TerrainDomain.valueOf(it) }.getOrNull()
        } ?: return rejected(TerrainDiagnosticRejectionCode.INVALID_SELECTOR, "page.domain", diagnosticGeneration)
        val detailLevel = page.path("detailLevel").exactInt() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "page.detailLevel",
            diagnosticGeneration,
        )
        val x = page.path("x").exactLong() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "page.x",
            diagnosticGeneration,
        )
        val y = page.path("y").exactLong() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "page.y",
            diagnosticGeneration,
        )
        val z = page.path("z").exactLong() ?: return rejected(
            TerrainDiagnosticRejectionCode.INVALID_SELECTOR,
            "page.z",
            diagnosticGeneration,
        )
        val selector = runCatching {
            TerrainPageAreaSelector(domain, detailLevel, detailLevel, x, x, y, y, z, z)
        }.getOrElse {
            return rejected(TerrainDiagnosticRejectionCode.INVALID_SELECTOR, "page", diagnosticGeneration)
        }
        return TerrainDiagnosticRequestResolution.Accepted(
            TerrainPageQuery(requestedEpoch, selector, maximumCount = 1),
        )
    }

    internal fun parseFlushIdleRequest(body: JsonNode): TerrainFlushIdleRequest? {
        if (!body.isObject || !hasExactly(body, "condition", "timeoutMs")) return null
        val condition = body.path("condition").takeIf(JsonNode::isTextual)?.textValue()?.let {
            runCatching { TerrainFlushIdleCondition.valueOf(it) }.getOrNull()
        } ?: return null
        val timeoutMillis = body.path("timeoutMs").exactLong() ?: return null
        if (timeoutMillis !in 1L..30_000L) return null
        return TerrainFlushIdleRequest(condition, timeoutMillis)
    }

    internal fun parseCompareFixture(body: JsonNode): TerrainDualBuildFixture? {
        if (!body.isObject || !hasExactly(body, "fixture")) return null
        val fixture = body.path("fixture").takeIf(JsonNode::isTextual)?.textValue() ?: return null
        return TerrainDualBuildFixture.fromWireName(fixture)
    }

    internal fun parseFaultRequest(body: JsonNode): TerrainFaultRequest? {
        if (!body.isObject) return null
        val action = body.path("action").takeIf(JsonNode::isTextual)?.textValue()?.let {
            runCatching { TerrainFaultAction.valueOf(it) }.getOrNull()
        } ?: return null
        return when (action) {
            TerrainFaultAction.ARM -> {
                if (!hasExactly(body, "action", "fault")) return null
                val fault = body.path("fault").takeIf(JsonNode::isTextual)?.textValue()?.let {
                    TerrainAcceptanceFault.fromWireName(it)
                } ?: return null
                TerrainFaultRequest(action = action, fault = fault)
            }
            TerrainFaultAction.RESTORE -> {
                if (!hasExactly(body, "action", "restorationToken")) return null
                val token = body.path("restorationToken").takeIf(JsonNode::isTextual)?.textValue()
                    ?.takeIf { it.length in 1..128 }
                    ?: return null
                TerrainFaultRequest(action = action, restorationToken = token)
            }
            TerrainFaultAction.STATUS -> {
                if (!hasExactly(body, "action")) return null
                TerrainFaultRequest(action = action)
            }
        }
    }

    internal fun rejectionResult(rejection: TerrainDiagnosticRejection): DebugOperationResult =
        canonicalResult(TerrainDiagnosticCanonicalJson.encode(rejection))

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

    private fun parseSelector(node: JsonNode): TerrainPageSelector? {
        if (!node.isObject) return null
        val type = node.path("type").takeIf(JsonNode::isTextual)?.textValue() ?: return null
        val domain = node.path("domain").takeIf(JsonNode::isTextual)?.textValue()?.let {
            runCatching { TerrainDomain.valueOf(it) }.getOrNull()
        } ?: return null
        return when (type) {
            "AREA" -> {
                if (!hasExactly(
                        node,
                        "type", "domain", "minimumDetailLevel", "maximumDetailLevel",
                        "minimumX", "maximumX", "minimumY", "maximumY", "minimumZ", "maximumZ",
                    )
                ) return null
                runCatching {
                    TerrainPageAreaSelector(
                        domain = domain,
                        minimumDetailLevel = node.path("minimumDetailLevel").exactInt() ?: return null,
                        maximumDetailLevel = node.path("maximumDetailLevel").exactInt() ?: return null,
                        minimumX = node.path("minimumX").exactLong() ?: return null,
                        maximumX = node.path("maximumX").exactLong() ?: return null,
                        minimumY = node.path("minimumY").exactLong() ?: return null,
                        maximumY = node.path("maximumY").exactLong() ?: return null,
                        minimumZ = node.path("minimumZ").exactLong() ?: return null,
                        maximumZ = node.path("maximumZ").exactLong() ?: return null,
                    )
                }.getOrNull()
            }

            "PREFIX" -> {
                if (!hasOnly(node, "type", "domain", "detailLevel", "x", "y", "z")) return null
                val detailLevel = node.path("detailLevel").exactInt() ?: return null
                fun optionalLong(name: String): Long? {
                    val value = node.get(name) ?: return null
                    if (value.isNull) return null
                    return value.exactLong() ?: throw IllegalArgumentException()
                }
                runCatching {
                    TerrainPagePrefixSelector(
                        domain = domain,
                        detailLevel = detailLevel,
                        z = optionalLong("z"),
                        y = optionalLong("y"),
                        x = optionalLong("x"),
                    )
                }.getOrNull()
            }

            else -> null
        }
    }

    private fun rejected(
        code: TerrainDiagnosticRejectionCode,
        subject: String,
        diagnosticGeneration: Long,
    ) = TerrainDiagnosticRequestResolution.Rejected(
        TerrainDiagnosticRejection(code = code, subject = subject, diagnosticGeneration = diagnosticGeneration),
    )

    private fun hasOnly(node: JsonNode, vararg allowed: String): Boolean =
        node.fieldNames().asSequence().all(allowed.toSet()::contains)

    private fun hasEvery(node: JsonNode, vararg required: String): Boolean = required.all(node::has)

    private fun hasExactly(node: JsonNode, vararg expected: String): Boolean =
        node.fieldNames().asSequence().toSet() == expected.toSet()

    private fun JsonNode.exactLong(): Long? =
        takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

    private fun JsonNode.exactInt(): Int? =
        takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
}
