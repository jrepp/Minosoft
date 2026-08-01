/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.minosoft.config.settings.SettingsApplyResult
import de.bixilon.minosoft.config.settings.SettingsSession
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import de.bixilon.minosoft.gui.rendering.graph.RenderPhase
import de.bixilon.minosoft.gui.rendering.terrain.distant.DistantTerrainRenderer
import de.bixilon.minosoft.gui.rendering.terrain.distant.DistantTerrainRendererBuilder
import de.bixilon.minosoft.gui.rendering.terrain.distant.distantViewProjection
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalColumn
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.maximumContiguousDistantRadius
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.io.IOException
import java.nio.file.Files
import java.util.HexFormat
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistantHorizonsCompatibilityAdapterTest {
    @Test
    fun `distant passes precede native opaque terrain so the native depth wins`() {
        assertEquals("minosoft:distant-terrain", DistantTerrainRenderer.OWNER.toString())
        assertTrue(RenderPhase.DISTANT_TERRAIN < RenderPhase.DISTANT_DEPTH_BEFORE_TRANSLUCENT)
        assertTrue(RenderPhase.DISTANT_DEPTH_BEFORE_TRANSLUCENT < RenderPhase.DISTANT_WATER)
        assertTrue(RenderPhase.DISTANT_WATER < RenderPhase.WORLD_OPAQUE)
    }

    @Test
    fun `built in distant route retains host lens with an independent far plane`() {
        val host = CameraUtil.perspective(
            fovY = 1.1f,
            aspect = 16.0f / 9.0f,
            near = 0.01f,
            far = 192.0f,
        )
        val actual = distantViewProjection(
            hostProjection = host,
            view = Mat4f(),
            near = 0.01f,
            renderDistanceChunks = 128,
        )

        assertTrue(kotlin.math.abs(host[0, 0] - actual[0, 0]) < 1.0e-6f)
        assertTrue(kotlin.math.abs(host[1, 1] - actual[1, 1]) < 1.0e-6f)
        assertTrue(actual[2, 2] > host[2, 2])
        assertFailsWith<ArithmeticException> {
            distantViewProjection(host, Mat4f(), 0.01f, Int.MAX_VALUE)
        }
    }

    @Test
    fun `exact artifact installs and removes owned lod ingestion hooks`() {
        val metadata = FabricMetadata(
            id = "distanthorizons",
            version = "2.4.4-b",
            name = "Distant Horizons",
            environment = "*",
            entrypoints = setOf("client", "modmenu", "server"),
            dependencies = emptyMap(),
            provides = setOf("lod"),
            mixins = 1,
            accessWidener = "distanthorizons.fabric.accesswidener",
            nestedJarPaths = List(10) { "META-INF/jars/$it.jar" },
            source = "test",
        )
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        assertEquals(DistantHorizonsCompatibilityAdapter, adapter)

        val scope = FabricRegistrationScope()
        try {
            adapter!!.activate(FabricModProbe(metadata, emptySet(), adapter), scope)
            assertEquals(listOf(adapter.id), FabricChunkEvents.registrations())
            assertEquals(listOf(adapter.id), FabricBlockMutationEvents.registrations())
            assertEquals(listOf(adapter.id), FabricRendererRegistry.registrations().map { it.owner })
            assertTrue(FabricRendererRegistry.snapshot().single() is DistantTerrainRendererBuilder)
            assertEquals(listOf(adapter.id), FabricWorldEvents.registrations(FabricWorldEventPhase.JOINED))
            assertEquals(listOf(adapter.id), FabricWorldEvents.registrations(FabricWorldEventPhase.LEFT))
            assertEquals(
                listOf(adapter.id),
                FabricClientPayloadChannels.registrations().getValue(DistantLodProtocol.CHANNEL),
            )
            assertEquals(listOf(adapter.id), FabricSettings.registrations().map { it.owner })
        } finally {
            scope.close()
        }

        assertTrue(FabricChunkEvents.registrations().isEmpty())
        assertTrue(FabricBlockMutationEvents.registrations().isEmpty())
        assertTrue(FabricRendererRegistry.registrations().isEmpty())
        assertTrue(FabricWorldEvents.registrations(FabricWorldEventPhase.JOINED).isEmpty())
        assertTrue(FabricWorldEvents.registrations(FabricWorldEventPhase.LEFT).isEmpty())
        assertTrue(FabricClientPayloadChannels.registrations()[DistantLodProtocol.CHANNEL].isNullOrEmpty())
        assertTrue(FabricSettings.registrations().isEmpty())
    }

    @Test
    fun `lod persistence reads checked schema-v1 material and water-bed fixture`() {
        val root = createTempDirectory("minosoft-dh-lod-")
        val path = root.resolve("world.lod.gz")
        val stone = ResourceLocation.of("minecraft:stone")
        val water = ResourceLocation.of("minecraft:water")
        val tile = DistantLodTile.capture(ChunkPosition(-17, 42)) { x, z ->
            if (x == 3 && z == 5) {
                DistantLodColumn(72, water, 64, stone)
            } else {
                DistantLodColumn(67, stone)
            }
        }
        Files.write(path, legacyPersistenceFixture())
        val loaded = DistantLodPersistence(path, maximumTiles = 4).load().single()

        assertEquals(tile.position, loaded.position)
        assertEquals(DistantLodColumn(72, water, 64, stone), loaded[3, 5])
        assertEquals(DistantLodColumn(67, stone), loaded[0, 0])
        assertTrue(Files.isRegularFile(path))
        assertTrue(Files.list(root).use { entries -> entries.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `lod persistence bounds decompressed input`() {
        val root = createTempDirectory("minosoft-dh-lod-bounded-")
        val path = root.resolve("world.lod.gz")
        Files.write(path, legacyPersistenceFixture())

        assertFailsWith<IOException> {
            DistantLodPersistence(
                path,
                maximumTiles = 1,
                maximumUncompressedBytes = 8L,
            ).load()
        }
    }

    @Test
    fun `lod protocol round trips requests responses and rejects trailing bytes`() {
        val positions = listOf(ChunkPosition(-2, 7), ChunkPosition(11, -13))
        val request = DistantLodMessage.Request(91, positions)
        assertEquals(request, DistantLodProtocol.decode(DistantLodProtocol.encode(request)))

        val response = DistantLodMessage.Response(91, listOf(tile(positions.first())))
        val decoded = DistantLodProtocol.decode(DistantLodProtocol.encode(response)) as DistantLodMessage.Response
        assertEquals(91, decoded.requestId)
        assertEquals(positions.first(), decoded.tiles.single().position)
        assertEquals(DistantLodColumn(64, null), decoded.tiles.single()[0, 0])

        assertFailsWith<IllegalArgumentException> {
            DistantLodProtocol.decode(DistantLodProtocol.encode(request) + byteArrayOf(0))
        }
        val v2 = DistantTerrainMessageV2.Cancel(
            DistantProtocolWorld(3, 4, "minecraft:overworld"),
            requestId = 92,
        )
        assertEquals(v2, DistantLodProtocol.decodeNegotiated(DistantTerrainProtocolV2.encode(v2)))
    }

    @Test
    fun `unexplored spiral enumerates only the requested rings without a queue`() {
        val spiral = DistantChunkSpiral()
        spiral.reset(ChunkPosition(10, -4), innerRadius = 1, outerRadius = 2)
        val positions = buildList {
            while (true) add(spiral.next() ?: break)
        }

        assertEquals(16, positions.size)
        assertEquals(16, positions.toSet().size)
        assertTrue(positions.all {
            maxOf(kotlin.math.abs(it.x - 10), kotlin.math.abs(it.z + 4)) == 2
        })
    }

    @Test
    fun `lod tile capacity bounds a contiguous square radius`() {
        assertEquals(0, maximumContiguousDistantRadius(1))
        assertEquals(31, maximumContiguousDistantRadius(4_096))
        assertEquals(63, maximumContiguousDistantRadius(16_384))
        assertTrue((63 * 2 + 1) * (63 * 2 + 1) <= 16_384)
        assertTrue((64 * 2 + 1) * (64 * 2 + 1) > 16_384)
    }

    @Test
    fun `late network page is rejected after the active world epoch advances`() {
        val tile = tile(ChunkPosition(-3, 5))
        val page = DistantVerticalPage(
            TerrainPageKey(TerrainDomain.DISTANT, 0, -3, 0, 5, 7),
            width = 1,
            originY = 0,
            sourceRevision = 1,
            completeness = DistantSourceCompleteness.PARTIAL,
            columns = listOf(DistantVerticalColumn(emptyList())),
        )

        assertTrue(isCurrentDistantPublication(tile, page, worldEpoch = 7))
        assertFalse(isCurrentDistantPublication(tile, page, worldEpoch = 8))
        assertFailsWith<IllegalArgumentException> {
            isCurrentDistantPublication(tile(ChunkPosition(-2, 5)), page, worldEpoch = 7)
        }
    }

    @Test
    fun `owned settings apply bounded render generation storage and network values`() {
        val options = DistantHorizonsOptions.inMemory()
        val session = SettingsSession(options.schema())
        val renderDistance = session.schema.entries.single { it.id == "render_distance_chunks" }
        val generationBudget = session.schema.entries.single { it.id == "generation_budget_per_tick" }
        val networkTiles = session.schema.entries.single { it.id == "network_request_tiles" }

        @Suppress("UNCHECKED_CAST")
        session.set(renderDistance as de.bixilon.minosoft.config.settings.ConfigEntry<Int>, 192)
        @Suppress("UNCHECKED_CAST")
        session.set(generationBudget as de.bixilon.minosoft.config.settings.ConfigEntry<Int>, 4)
        @Suppress("UNCHECKED_CAST")
        session.set(networkTiles as de.bixilon.minosoft.config.settings.ConfigEntry<Int>, 32)

        assertTrue(session.apply() is SettingsApplyResult.Applied)
        assertEquals(192, options.renderDistanceChunks)
        assertEquals(4, options.generationBudgetPerTick)
        assertEquals(32, options.networkRequestTiles)
    }

    @Test
    fun `only pinned source-native Iris break is accepted`() {
        val distantHorizons = metadata("distanthorizons", "2.4.4-b")
        val iris = metadata("iris", "1.7.2+mc1.20.4")
        val declaredBreak = FabricDependencyIssue(
            owner = distantHorizons,
            relation = FabricDependencyRelation.BREAKS,
            dependency = FabricDependency("iris", listOf("<=1.7.4")),
            providers = listOf(iris),
        )

        assertTrue(DistantHorizonsCompatibilityAdapter.acceptsDependencyIssue(declaredBreak))
        assertFalse(
            DistantHorizonsCompatibilityAdapter.acceptsDependencyIssue(
                declaredBreak.copy(providers = listOf(metadata("iris", "1.7.4+mc1.20.4"))),
            ),
        )
        assertFalse(
            DistantHorizonsCompatibilityAdapter.acceptsDependencyIssue(
                declaredBreak.copy(relation = FabricDependencyRelation.CONFLICTS),
            ),
        )
    }

    private fun legacyPersistenceFixture(): ByteArray = HexFormat.of().parseHex(
        checkNotNull(javaClass.getResource("distant-lod-v1.hex")).readText().trim(),
    )

    private fun tile(position: ChunkPosition) = DistantLodTile.capture(position) { _, _ ->
        DistantLodColumn(64, null)
    }

    private fun metadata(id: String, version: String) = FabricMetadata(
        id = id,
        version = version,
        name = id,
        environment = "*",
        entrypoints = emptySet(),
        dependencies = emptyMap(),
        provides = emptySet(),
        mixins = 0,
        accessWidener = null,
        nestedJarPaths = emptyList(),
        source = "test",
    )
}
