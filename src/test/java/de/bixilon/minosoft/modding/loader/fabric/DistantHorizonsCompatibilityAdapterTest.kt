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
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class DistantHorizonsCompatibilityAdapterTest {
    @Test
    fun `distant passes precede native opaque terrain so the native depth wins`() {
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
            assertEquals(
                listOf(adapter.id),
                FabricWorldEvents.registrations(FabricWorldEventPhase.JOINED),
            )
            assertEquals(
                listOf(adapter.id),
                FabricWorldEvents.registrations(FabricWorldEventPhase.LEFT),
            )
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
    fun `lod tile updates only selected columns`() {
        val position = ChunkPosition(3, -5)
        val stone = ResourceLocation.of("minecraft:stone")
        val dirt = ResourceLocation.of("minecraft:dirt")
        val original = DistantLodTile.capture(position) { x, z ->
            DistantLodColumn(x + z, stone)
        }

        val updated = original.update(
            setOf(DistantLodTile.index(2, 7)),
        ) { x, z -> DistantLodColumn(100 + x + z, dirt) }

        assertEquals(DistantLodColumn(109, dirt), updated[2, 7])
        assertEquals(DistantLodColumn(3, stone), updated[1, 2])
        assertEquals(DistantLodColumn(9, stone), original[2, 7])
    }

    @Test
    fun `lod columns reject heights that would overflow renderer arithmetic`() {
        val stone = ResourceLocation.of("minecraft:stone")

        assertFailsWith<IllegalArgumentException> {
            DistantLodColumn(Int.MAX_VALUE, stone)
        }
    }

    @Test
    fun `lod store evicts least recently used tile`() {
        val store = DistantLodTileStore(maximumTiles = 2)
        val first = tile(ChunkPosition(1, 1))
        val second = tile(ChunkPosition(2, 2))
        val third = tile(ChunkPosition(3, 3))
        store.put(first)
        store.put(second)

        assertSame(first, store[first.position])
        store.put(third)

        assertEquals(2, store.size())
        assertSame(first, store[first.position])
        assertNull(store[second.position])
        assertSame(third, store[third.position])
    }

    @Test
    fun `lod mesh planner coarsens detached tiles beyond the near seam`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val water = ResourceLocation.of("minecraft:water")
        val near = DistantLodTile.capture(ChunkPosition(1, 0)) { _, _ ->
            DistantLodColumn(64, stone)
        }
        val far = DistantLodTile.capture(ChunkPosition(32, 0)) { x, z ->
            if (x < 4 && z < 4) {
                DistantLodColumn(70, water, solidY = 64, solidMaterial = stone)
            } else {
                DistantLodColumn(64, stone)
            }
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(near, far),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )

        assertEquals(17, planned.size)
        assertTrue(planned.all { it.chunk == far.position && it.size == DistantLodMeshPlanner.CELL_SIZE })
        val waterCell = planned.filter { it.x == 0 && it.z == 0 }
        assertEquals(
            setOf(DistantLodMaterial.WATER, DistantLodMaterial.STONE),
            waterCell.mapTo(linkedSetOf()) { it.material },
        )
        assertEquals(70, waterCell.single { it.material == DistantLodMaterial.WATER }.y)
        assertEquals(64, waterCell.single { it.material == DistantLodMaterial.STONE }.y)
        assertEquals(DistantLodMaterial.STONE, planned.single { it.x == 4 && it.z == 0 }.material)
    }

    @Test
    fun `lod mesh planner excludes chunks retained by native terrain`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val native = DistantLodTile.capture(ChunkPosition(32, 0)) { _, _ ->
            DistantLodColumn(64, stone)
        }
        val distant = DistantLodTile.capture(ChunkPosition(33, 0)) { _, _ ->
            DistantLodColumn(64, stone)
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(native, distant),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
            excludedChunks = setOf(native.position),
        )

        assertTrue(planned.isNotEmpty())
        assertTrue(planned.all { it.chunk == distant.position })
    }

    @Test
    fun `lod mesh planner ignores one block column spike when aggregating a cell`() {
        val sand = ResourceLocation.of("minecraft:sand")
        val stone = ResourceLocation.of("minecraft:stone")
        val tile = DistantLodTile.capture(ChunkPosition(32, 0)) { x, z ->
            if (x == 0 && z == 0) DistantLodColumn(112, stone) else DistantLodColumn(64, sand)
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(tile),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )
        val first = planned.single { it.x == 0 && it.z == 0 }

        assertEquals(64, first.y)
        assertEquals(DistantLodMaterial.SAND, first.material)
    }

    @Test
    fun `lod mesh planner fills bounded air holes from retained surface neighbors`() {
        val air = ResourceLocation.of("minecraft:air")
        val stone = ResourceLocation.of("minecraft:stone")
        val tile = DistantLodTile.capture(ChunkPosition(32, 0)) { x, z ->
            if (x in 4..7 && z in 4..7) {
                DistantLodColumn(384, air)
            } else {
                DistantLodColumn(72, stone)
            }
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(tile),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )

        assertEquals(16, planned.count { it.surface == DistantLodSurface.TERRAIN })
        assertTrue(planned.filter { it.surface == DistantLodSurface.TERRAIN }.all {
            it.material == DistantLodMaterial.STONE && it.y == 72
        })
    }

    @Test
    fun `lod mesh planner adds cliff skirts on one stable base lattice`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val relief = DistantLodTile.capture(ChunkPosition(32, 0)) { x, _ ->
            DistantLodColumn(if (x < 4) 72 else 64, stone)
        }
        val medium = tile(ChunkPosition(60, 0))
        val far = tile(ChunkPosition(120, 0))

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(relief, medium, far),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )

        val raised = planned.single { it.chunk == relief.position && it.x == 0 && it.z == 0 }
        assertTrue(raised.skirts.any {
            it.edge == DistantLodEdge.EAST && it.bottomY == 64
        })
        assertTrue(raised.skirts.none { it.edge == DistantLodEdge.NORTH })
        assertTrue(planned.filter { it.chunk == medium.position }.all {
            it.size == DistantLodMeshPlanner.CELL_SIZE
        })
        assertTrue(planned.filter { it.chunk == far.position }.all {
            it.size == DistantLodMeshPlanner.CELL_SIZE
        })
    }

    @Test
    fun `high relief far tile adaptively retains four block cells`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val mountain = DistantLodTile.capture(ChunkPosition(120, 0)) { x, _ ->
            DistantLodColumn(if (x < 8) 144 else 64, stone)
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(mountain),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )

        assertEquals(16, planned.size)
        assertTrue(planned.all { it.size == DistantLodMeshPlanner.CELL_SIZE })
        assertTrue(planned.none { it.size == DistantLodMeshPlanner.FAR_CELL_SIZE })
    }

    @Test
    fun `extreme relief tile retains exact one block columns`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val mountain = DistantLodTile.capture(ChunkPosition(120, 0)) { x, z ->
            if (x < 2 && z < 2) DistantLodColumn(280, stone) else DistantLodColumn(64, stone)
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(mountain),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )
        val extremeCell = planned.single { it.x == 0 && it.z == 0 }

        assertEquals(1, extremeCell.size)
        assertEquals(280, extremeCell.maximumY)
        assertTrue(extremeCell.y < extremeCell.maximumY)
        assertTrue(planned.none {
            it.x == 0 && it.z == 0 && it.size == DistantLodMeshPlanner.CELL_SIZE
        })
        assertTrue(planned.all { quad ->
            quad.skirts.all { quad.y - it.bottomY <= 32 }
        })
        val rendered = planned.filter { it.surface != DistantLodSurface.WATER_BED }
            .associateBy { it.x to it.z }
        rendered.forEach { (position, cell) ->
            rendered[position.first + 1 to position.second]?.let { east ->
                assertTrue(kotlin.math.abs(cell.y - east.y) <= 4)
            }
            rendered[position.first to position.second + 1]?.let { south ->
                assertTrue(kotlin.math.abs(cell.y - south.y) <= 4)
            }
        }
    }

    @Test
    fun `base relief edge merges equal stitched skirt segments per cell`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val high = DistantLodTile.capture(ChunkPosition(120, 0)) { _, _ ->
            DistantLodColumn(70, stone)
        }
        val low = DistantLodTile.capture(ChunkPosition(121, 0)) { _, _ ->
            DistantLodColumn(64, stone)
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(high, low),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )
        val east = planned.filter {
            it.chunk == high.position && it.x == 12
        }.flatMap { cell ->
            cell.skirts.filter { it.edge == DistantLodEdge.EAST }
        }

        assertEquals(4, east.size)
        assertTrue(east.all { it.offset == 0 })
        assertTrue(east.all { it.length == DistantLodMeshPlanner.CELL_SIZE })
        assertTrue(east.all { it.bottomY == 64 })
    }

    @Test
    fun `adaptive two to four transition stitches every refined edge span`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val relief = DistantLodTile.capture(ChunkPosition(120, 0)) { x, z ->
            if (x < 2 && z < 2) DistantLodColumn(88, stone) else DistantLodColumn(64, stone)
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(relief),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )
        val refined = planned.single {
            it.chunk == relief.position &&
                it.x == 0 &&
                it.z == 0
        }

        assertEquals(2, refined.size)
        assertTrue(refined.skirts.any {
            it.edge == DistantLodEdge.EAST &&
                it.length == 2 &&
                it.bottomY == 64
        })
        assertTrue(planned.any { it.size == DistantLodMeshPlanner.CELL_SIZE })
    }

    @Test
    fun `coastline terrain skirts stop at water surface and water beds never emit curtains`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val water = ResourceLocation.of("minecraft:water")
        val coast = DistantLodTile.capture(ChunkPosition(120, 0)) { x, _ ->
            if (x < 8) {
                DistantLodColumn(70, stone)
            } else {
                DistantLodColumn(64, water, solidY = 20, solidMaterial = stone)
            }
        }

        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(coast),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
        )
        val shore = planned.single {
            it.surface == DistantLodSurface.TERRAIN && it.x == 4 && it.z == 0
        }
        val beds = planned.filter { it.surface == DistantLodSurface.WATER_BED }

        assertTrue(shore.skirts.any {
            it.edge == DistantLodEdge.EAST && it.bottomY == 64
        })
        assertTrue(shore.skirts.none { it.bottomY == 20 })
        assertTrue(beds.isNotEmpty())
        assertTrue(beds.all { it.skirts.isEmpty() })
    }

    @Test
    fun `planned cells retain detached tile provenance`() {
        val position = ChunkPosition(32, 0)
        val stone = ResourceLocation.of("minecraft:stone")
        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(DistantLodTile.capture(position) { _, _ -> DistantLodColumn(64, stone) }),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
            sources = mapOf(position to DistantLodTileSource.NETWORK),
        )

        assertTrue(planned.isNotEmpty())
        assertTrue(planned.all { it.source == DistantLodTileSource.NETWORK })
    }

    @Test
    fun `lod mesh planner rejects tiles beyond configured render distance`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val planned = DistantLodMeshPlanner.plan(
            tiles = listOf(
                DistantLodTile.capture(ChunkPosition(32, 0)) { _, _ -> DistantLodColumn(64, stone) },
                DistantLodTile.capture(ChunkPosition(65, 0)) { _, _ -> DistantLodColumn(64, stone) },
            ),
            cameraChunk = ChunkPosition(),
            seamDistance = 128.0f,
            maximumDistanceChunks = 64,
        )

        assertTrue(planned.isNotEmpty())
        assertTrue(planned.all { it.chunk == ChunkPosition(32, 0) })
    }

    @Test
    fun `lod persistence round trips material palette and water bed atomically`() {
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
        val persistence = DistantLodPersistence(path, maximumTiles = 4)

        persistence.save(listOf(tile))
        val loaded = persistence.load().single()

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
        DistantLodPersistence(path, maximumTiles = 1).save(listOf(tile(ChunkPosition())))

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

        val response = DistantLodMessage.Response(
            requestId = 91,
            tiles = listOf(tile(positions.first())),
        )
        val decoded = DistantLodProtocol.decode(DistantLodProtocol.encode(response))
            as DistantLodMessage.Response
        assertEquals(91, decoded.requestId)
        assertEquals(positions.first(), decoded.tiles.single().position)
        assertEquals(DistantLodColumn(64, null), decoded.tiles.single()[0, 0])

        assertFailsWith<IllegalArgumentException> {
            DistantLodProtocol.decode(DistantLodProtocol.encode(request) + byteArrayOf(0))
        }
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
    fun `lod publication stops before the first uncovered outer tile`() {
        val camera = ChunkPosition()
        val missing = ChunkPosition(3, 0)
        val tiles = buildList {
            for (z in -6..6) {
                for (x in -6..6) {
                    val dx = x + 0.5f
                    val dz = z + 0.5f
                    val position = ChunkPosition(x, z)
                    if (dx * dx + dz * dz <= 36.0f && position != missing) {
                        add(tile(position))
                    }
                }
            }
        }

        assertEquals(
            3,
            maximumContiguousDistantCoverageRadius(
                tiles = tiles,
                cameraChunk = camera,
                seamDistanceChunks = 1.0f,
                maximumDistanceChunks = 6,
            ),
        )
        assertEquals(
            6,
            maximumContiguousDistantCoverageRadius(
                tiles = tiles,
                cameraChunk = camera,
                seamDistanceChunks = 1.0f,
                maximumDistanceChunks = 6,
                coveredChunks = setOf(missing),
            ),
        )
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
