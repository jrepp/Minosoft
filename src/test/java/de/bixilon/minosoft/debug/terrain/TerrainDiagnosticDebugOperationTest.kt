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
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainPageCursorCodec
import de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainDualBuildFixture
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `production page request parser bounds selectors and validates opaque cursors`() {
        val body = areaQuery(worldEpoch = 7L, maximumCount = 2)
        val accepted = assertIs<TerrainDiagnosticRequestResolution.Accepted>(
            TerrainDiagnosticDebugOperation.resolveRequest(body, diagnosticGeneration = 11L, worldEpoch = 7L),
        )
        val query = assertNotNull(accepted.query)
        assertNull(query.cursor)
        assertEquals(2, query.maximumCount)

        val lastPage = de.bixilon.minosoft.terrain.model.identity.TerrainPageKey(
            de.bixilon.minosoft.terrain.model.identity.TerrainDomain.NEAR,
            0,
            -1L,
            0L,
            3L,
            7L,
        )
        body.path("pageQuery").let {
            (it as com.fasterxml.jackson.databind.node.ObjectNode).put(
                "cursor",
                TerrainPageCursorCodec.issue(11L, query, lastPage).opaqueValue,
            )
        }
        val continued = assertIs<TerrainDiagnosticRequestResolution.Accepted>(
            TerrainDiagnosticDebugOperation.resolveRequest(body, diagnosticGeneration = 11L, worldEpoch = 7L),
        )
        assertEquals(body.path("pageQuery").path("cursor").textValue(), continued.query?.cursor?.opaqueValue)

        val wrongWorld = assertIs<TerrainDiagnosticRequestResolution.Rejected>(
            TerrainDiagnosticDebugOperation.resolveRequest(
                areaQuery(worldEpoch = 6L, maximumCount = 2),
                diagnosticGeneration = 11L,
                worldEpoch = 7L,
            ),
        )
        assertEquals(TerrainDiagnosticRejectionCode.WRONG_WORLD_EPOCH, wrongWorld.rejection.code)

        val oversized = assertIs<TerrainDiagnosticRequestResolution.Rejected>(
            TerrainDiagnosticDebugOperation.resolveRequest(
                areaQuery(worldEpoch = 7L, maximumCount = 1_025),
                diagnosticGeneration = 11L,
                worldEpoch = 7L,
            ),
        )
        assertEquals(TerrainDiagnosticRejectionCode.INVALID_MAXIMUM_COUNT, oversized.rejection.code)
    }

    @Test
    fun `page detail parser requires an exact current-world identity`() {
        val accepted = assertIs<TerrainDiagnosticRequestResolution.Accepted>(
            TerrainDiagnosticDebugOperation.resolvePageRequest(
                pageRequest(worldEpoch = 7L),
                diagnosticGeneration = 12L,
                worldEpoch = 7L,
            ),
        )
        val query = assertNotNull(accepted.query)
        assertEquals(1, query.maximumCount)
        assertEquals(de.bixilon.minosoft.terrain.model.identity.TerrainDomain.DISTANT, query.selector.domain)

        val wrongWorld = assertIs<TerrainDiagnosticRequestResolution.Rejected>(
            TerrainDiagnosticDebugOperation.resolvePageRequest(
                pageRequest(worldEpoch = 6L),
                diagnosticGeneration = 12L,
                worldEpoch = 7L,
            ),
        )
        assertEquals(TerrainDiagnosticRejectionCode.WRONG_WORLD_EPOCH, wrongWorld.rejection.code)

        val extra = pageRequest(worldEpoch = 7L).put("unexpected", true)
        val malformed = assertIs<TerrainDiagnosticRequestResolution.Rejected>(
            TerrainDiagnosticDebugOperation.resolvePageRequest(extra, 12L, 7L),
        )
        assertEquals(TerrainDiagnosticRejectionCode.INVALID_SELECTOR, malformed.rejection.code)
    }

    @Test
    fun `flush idle parser and named conditions are bounded`() {
        val body = DebugJson.MAPPER.createObjectNode().apply {
            put("condition", "RETIREMENT")
            put("timeoutMs", 2_000L)
        }
        val request = assertNotNull(TerrainDiagnosticDebugOperation.parseFlushIdleRequest(body))
        assertEquals(TerrainFlushIdleCondition.RETIREMENT, request.condition)
        assertEquals(2_000L, request.timeoutMillis)

        val buildsBusy = TerrainIdleState(1, 0, 0, 0, 0, 0, 0, 0, 0L)
        assertFalse(buildsBusy.matches(TerrainFlushIdleCondition.BUILDS))
        assertTrue(buildsBusy.matches(TerrainFlushIdleCondition.UPLOADS))
        assertFalse(buildsBusy.matches(TerrainFlushIdleCondition.ALL))
        val idle = TerrainIdleState(0, 0, 0, 0, 0, 0, 0, 0, 0L)
        TerrainFlushIdleCondition.entries.forEach { assertTrue(idle.matches(it)) }
        val activeDrawFence = TerrainIdleState(0, 0, 0, 0, 0, 128, 1, 0, 0L)
        assertTrue(activeDrawFence.matches(TerrainFlushIdleCondition.UPLOADS))
        assertTrue(activeDrawFence.matches(TerrainFlushIdleCondition.RETIREMENT))
        assertFalse(activeDrawFence.matches(TerrainFlushIdleCondition.ALL))
        assertTrue(activeDrawFence.requiresGpuDrain(TerrainFlushIdleCondition.ALL))
        assertFalse(activeDrawFence.requiresGpuDrain(TerrainFlushIdleCondition.BUILDS))
        assertFalse(buildsBusy.requiresGpuDrain(TerrainFlushIdleCondition.ALL))
        assertFalse(idle.requiresGpuDrain(TerrainFlushIdleCondition.ALL))

        assertNull(TerrainDiagnosticDebugOperation.parseFlushIdleRequest(body.deepCopy().put("timeoutMs", 0L)))
        assertNull(TerrainDiagnosticDebugOperation.parseFlushIdleRequest(body.deepCopy().put("condition", "UNKNOWN")))
        assertNull(TerrainDiagnosticDebugOperation.parseFlushIdleRequest(body.deepCopy().put("extra", true)))
    }

    @Test
    fun `compare parser selects only checked deterministic fixtures`() {
        val valid = DebugJson.MAPPER.createObjectNode().put("fixture", "near-solid-quad")
        assertEquals(
            TerrainDualBuildFixture.NEAR_SOLID_QUAD,
            TerrainDiagnosticDebugOperation.parseCompareFixture(valid),
        )
        assertNull(
            TerrainDiagnosticDebugOperation.parseCompareFixture(
                DebugJson.MAPPER.createObjectNode().put("fixture", "unknown"),
            ),
        )
        assertNull(TerrainDiagnosticDebugOperation.parseCompareFixture(valid.deepCopy().put("extra", true)))
    }

    @Test
    fun `fault parser requires bounded exact action payloads`() {
        val arm = DebugJson.MAPPER.createObjectNode().apply {
            put("action", "ARM")
            put("fault", "reject-next-near-upload")
        }
        val armRequest = assertNotNull(TerrainDiagnosticDebugOperation.parseFaultRequest(arm))
        assertEquals(TerrainFaultAction.ARM, armRequest.action)
        assertEquals(
            de.bixilon.minosoft.terrain.runtime.diagnostic.TerrainAcceptanceFault.REJECT_NEXT_NEAR_UPLOAD,
            armRequest.fault,
        )

        val restore = DebugJson.MAPPER.createObjectNode().apply {
            put("action", "RESTORE")
            put("restorationToken", "terrain-fault-1")
        }
        assertEquals(
            "terrain-fault-1",
            assertNotNull(TerrainDiagnosticDebugOperation.parseFaultRequest(restore)).restorationToken,
        )
        assertEquals(
            TerrainFaultAction.STATUS,
            assertNotNull(
                TerrainDiagnosticDebugOperation.parseFaultRequest(
                    DebugJson.MAPPER.createObjectNode().put("action", "STATUS"),
                ),
            ).action,
        )
        assertNull(TerrainDiagnosticDebugOperation.parseFaultRequest(arm.deepCopy().put("extra", true)))
        assertNull(
            TerrainDiagnosticDebugOperation.parseFaultRequest(
                restore.deepCopy().put("restorationToken", "x".repeat(129)),
            ),
        )
    }

    private fun areaQuery(worldEpoch: Long, maximumCount: Int) =
        DebugJson.MAPPER.createObjectNode().apply {
            putObject("pageQuery").apply {
                put("worldEpoch", worldEpoch)
                put("maximumCount", maximumCount)
                putObject("selector").apply {
                    put("type", "AREA")
                    put("domain", "NEAR")
                    put("minimumDetailLevel", 0)
                    put("maximumDetailLevel", 0)
                    put("minimumX", -4L)
                    put("maximumX", 4L)
                    put("minimumY", 0L)
                    put("maximumY", 0L)
                    put("minimumZ", -4L)
                    put("maximumZ", 4L)
                }
            }
        }

    private fun pageRequest(worldEpoch: Long) = DebugJson.MAPPER.createObjectNode().apply {
        put("worldEpoch", worldEpoch)
        putObject("page").apply {
            put("domain", "DISTANT")
            put("detailLevel", 2)
            put("x", -4L)
            put("y", 0L)
            put("z", 7L)
        }
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
