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

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantRunFlag
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DistantLodDataTest {
    @Test
    fun `tile updates only selected columns`() {
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
    fun `columns reject heights that would overflow renderer arithmetic`() {
        val stone = ResourceLocation.of("minecraft:stone")

        assertFailsWith<IllegalArgumentException> {
            DistantLodColumn(Int.MAX_VALUE, stone)
        }
    }

    @Test
    fun `store evicts least recently used tile`() {
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
        assertSame(first, store.remove(first.position))
        assertNull(store[first.position])
        assertEquals(1, store.size())
    }

    @Test
    fun `top-only compatibility preserves classified fluid surface and bed`() {
        val water = ResourceLocation.of("minecraft:water")
        val stone = ResourceLocation.of("minecraft:stone")
        val resolver = DistantCompatibilityMaterialResolver { identifier ->
            when (identifier) {
                water -> DistantCompatibilityMaterial(
                    TerrainSemanticMaterialId(identifier.toString()),
                    opaque = false,
                    fluid = DistantFluidSample(
                        TerrainSemanticMaterialId(identifier.toString()),
                        level = 0,
                        classification = "water",
                    ),
                )

                stone -> DistantCompatibilityMaterial(TerrainSemanticMaterialId(identifier.toString()), opaque = true)
                else -> null
            }
        }

        val column = DistantLodColumn(70, water, solidY = 64, solidMaterial = stone)
            .toVerticalCompatibilityColumn(resolver)

        assertEquals(2, column.runs.size)
        assertEquals(64, column.runs[0].minimumY)
        assertEquals(70, column.runs[0].maximumYExclusive)
        assertEquals("water", column.runs[0].fluid?.classification)
        assertEquals(63, column.runs[1].minimumY)
        assertEquals(64, column.runs[1].maximumYExclusive)
        assertTrue(DistantRunFlag.OPAQUE in column.runs[1].flags)
        assertTrue(column.runs.all {
            DistantRunFlag.GENERATED in it.flags && DistantRunFlag.SURFACE_ONLY in it.flags && it.confidence == 25
        })
    }

    @Test
    fun `top-only compatibility is partial deterministic and does not guess fluids`() {
        val namedLikeFluid = ResourceLocation.of("example:liquid_waterish")
        val material = DistantCompatibilityMaterial(
            TerrainSemanticMaterialId(namedLikeFluid.toString()),
            opaque = false,
        )
        val resolver = DistantCompatibilityMaterialResolver { material }
        val tile = DistantLodTile.capture(ChunkPosition(-3, 9)) { x, z ->
            if (x == 0 && z == 0) DistantLodColumn(80, namedLikeFluid) else DistantLodColumn(Int.MIN_VALUE, null)
        }

        val first = tile.toTopOnlyCompatibilityPage(resolver)
        val second = tile.toTopOnlyCompatibilityPage(resolver)

        assertEquals(DistantSourceCompleteness.PARTIAL, first.completeness)
        assertEquals(first.semanticDigest, second.semanticDigest)
        assertEquals(-3L, first.pageKey(11L).x)
        assertEquals(9L, first.pageKey(11L).z)
        assertEquals(11L, first.pageKey(11L).worldEpoch)
        assertEquals(null, first[0, 0].runs.single().fluid)
        assertTrue(first[1, 0].runs.isEmpty())
    }

    @Test
    fun `top-only migration writes only missing schema-v2 pages`() {
        val stone = ResourceLocation.of("minecraft:stone")
        val resolver = DistantCompatibilityMaterialResolver {
            DistantCompatibilityMaterial(TerrainSemanticMaterialId(it.toString()), opaque = true)
        }
        val existing = tile(ChunkPosition(1, 2))
        val missing = DistantLodTile.capture(ChunkPosition(-4, 7)) { _, _ -> DistantLodColumn(64, stone) }
        val persisted = mutableListOf<de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage>()
        var revision = 30L

        val migrated = migrateTopOnlyTiles(
            listOf(existing, missing),
            existingPositions = setOf(existing.position),
            resolver = resolver,
            worldEpoch = 9,
            originY = -64,
            nextSourceRevision = { ++revision },
            persist = persisted::add,
        )

        assertEquals(migrated, persisted)
        assertEquals(1, migrated.size)
        assertEquals(-4L, migrated.single().key.x)
        assertEquals(7L, migrated.single().key.z)
        assertEquals(9L, migrated.single().key.worldEpoch)
        assertEquals(31L, migrated.single().sourceRevision)
        assertEquals(DistantSourceCompleteness.PARTIAL, migrated.single().completeness)
    }

    private fun tile(position: ChunkPosition) = DistantLodTile.capture(position) { _, _ ->
        DistantLodColumn(64, null)
    }
}
