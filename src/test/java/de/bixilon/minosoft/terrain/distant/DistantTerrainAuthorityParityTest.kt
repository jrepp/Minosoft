/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantTintSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalSampler
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVoxelSample
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2
import de.bixilon.minosoft.terrain.distant.store.DistantDirectoryTerrainStore
import de.bixilon.minosoft.terrain.distant.store.DistantTerrainStoreIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DistantTerrainAuthorityParityTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `native local server network and persisted pages preserve normalized semantics`() {
        val key = TerrainPageKey(TerrainDomain.DISTANT, 0, -7, 0, 13, 22)
        val native = captureFixture(key)
        val local = captureFixture(key)
        val server = captureFixture(key)

        val store = DistantDirectoryTerrainStore(
            temporary.resolve("pages"),
            DistantTerrainStoreIdentity("fixture-world", "minecraft:overworld"),
            maximumPages = 4,
        )
        store.write(native)
        val persisted = store.load(key.worldEpoch).single()

        val world = DistantProtocolWorld(5, key.worldEpoch, "minecraft:overworld")
        val network = DistantTerrainProtocolV2.decode(
            DistantTerrainProtocolV2.encode(DistantTerrainMessageV2.Response(world, 8, listOf(server))),
        ) as DistantTerrainMessageV2.Response

        assertSemanticParity(listOf(native, local, server, persisted, network.pages.single()))
    }

    private fun captureFixture(key: TerrainPageKey): DistantVerticalPage = DistantVerticalSampler.capture(
        key,
        width = 1,
        minimumY = -2,
        maximumYExclusive = 4,
        sourceRevision = 17,
        completeness = DistantSourceCompleteness.COMPLETE,
    ) { _, y, _ ->
        when (y) {
            3 -> DistantVoxelSample(
                material = TerrainSemanticMaterialId("fixture:waterlogged_lamp"),
                fluid = DistantFluidSample(TerrainSemanticMaterialId("fixture:brine"), 5, "fixture:brine"),
                blockLight = 14,
                skyLight = 9,
                tint = DistantTintSample(0x336699, "fixture:marsh", 4),
                opaque = false,
                emissive = true,
                generated = true,
                confidence = 88,
            )
            1 -> DistantVoxelSample(
                material = TerrainSemanticMaterialId("fixture:stone"),
                blockLight = 2,
                skyLight = 6,
                opaque = true,
                confidence = 100,
            )
            else -> DistantVoxelSample(null, blockLight = 1, skyLight = 3)
        }
    }

    private fun assertSemanticParity(pages: List<DistantVerticalPage>) {
        val expected = pages.first()
        for (page in pages.drop(1)) {
            assertEquals(expected.semanticDigest, page.semanticDigest)
            assertEquals(expected.columns.map { it.digest }, page.columns.map { it.digest })
            assertEquals(expected.completeness, page.completeness)
        }
    }
}
