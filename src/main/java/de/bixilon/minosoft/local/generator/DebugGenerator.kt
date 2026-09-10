/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.local.generator

import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.biome.source.DummyBiomeSource
import de.bixilon.minosoft.data.world.biome.source.XZBiomeArray
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import kotlin.math.sqrt

class DebugGenerator(val session: PlaySession) : ChunkGenerator {
    private val plains get() = session.registries.biome[minecraft("plains")] ?: FALLBACK_PLAINS
    private val swamp get() = session.registries.biome[minecraft("swamp")] ?: FALLBACK_SWAMP
    private val size = (sqrt(session.registries.blockState.size.toFloat())).toInt() + 1
    private val arenaMaximum = size * BLOCK_SPACING + ARENA_MARGIN


    override fun generate(builder: ChunkBuilder) {
        if (generateTerrainCanary(builder)) return

        builder.biomes = DummyBiomeSource(plains)
        generateArenaFloor(builder)
        if (builder.position.x < 0 || builder.position.z < 0) return

        val xOffset = builder.position.x * ChunkSize.SECTION_WIDTH_X
        val zOffset = builder.position.z * ChunkSize.SECTION_WIDTH_Z

        for (x in 0 until ChunkSize.SECTION_WIDTH_X step 2) {
            val actuallyX = (xOffset + x) / 2
            if (actuallyX > size) continue

            for (z in 0 until ChunkSize.SECTION_WIDTH_Z step 2) {
                val actuallyZ = (zOffset + z) / 2
                if (actuallyZ > size) continue

                val id = actuallyX * size + actuallyZ
                val state = session.registries.blockState.getOrNull(id) ?: continue
                builder[x, 8, z] = state
            }
        }
    }

    /**
     * Gives the block-state catalog a bounded, continuous walking surface.
     * One negative chunk is retained as a spawn/safety apron, while chunks
     * beyond the generated catalog remain empty so accidental traversal does
     * not turn the debug world into an unbounded terrain generator.
     */
    private fun generateArenaFloor(builder: ChunkBuilder) {
        val stone = session.registries.block[minecraft("stone")]?.states?.default ?: return
        val chunkX = builder.position.x * ChunkSize.SECTION_WIDTH_X
        val chunkZ = builder.position.z * ChunkSize.SECTION_WIDTH_Z

        for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
            if (chunkX + x !in ARENA_MINIMUM..arenaMaximum) continue
            for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
                if (chunkZ + z !in ARENA_MINIMUM..arenaMaximum) continue
                builder[x, ARENA_FLOOR_Y, z] = stone
            }
        }
    }

    /**
     * A bounded visual-fidelity pad in a reserved row of the debug world.
     *
     * The chunk seam at x=112 is also a biome seam. The grass and shallow
     * water crossing it make tint interpolation visible, while the compact
     * stair/farmland/torch group exercises partial-face AO and block light.
     */
    private fun generateTerrainCanary(builder: ChunkBuilder): Boolean {
        if (builder.position.z != CANARY_CHUNK_Z || builder.position.x !in CANARY_CHUNK_X) return false

        val biome = if (builder.position.x == CANARY_CHUNK_X.first) swamp else plains
        builder.biomes = XZBiomeArray(Array(ChunkSize.SECTION_WIDTH_X * ChunkSize.SECTION_WIDTH_Z) { biome })
        val stone = session.registries.block[minecraft("stone")]?.states?.default
        val grass = session.registries.block[minecraft("grass_block")]?.states?.default
        val water = session.registries.block[minecraft("water")]?.states?.default
        val stairs = session.registries.block[minecraft("stone_brick_stairs")]?.states?.default
        val farmland = session.registries.block[minecraft("farmland")]?.states?.default
        val torch = session.registries.block[minecraft("torch")]?.states?.default

        val chunkX = builder.position.x * ChunkSize.SECTION_WIDTH_X
        for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
            val worldX = chunkX + x
            for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
                builder[x, CANARY_FLOOR_Y, z] = stone
                builder[x, CANARY_SURFACE_Y, z] = grass
                if (worldX in CANARY_WATER_X && z in CANARY_WATER_Z) {
                    builder[x, CANARY_SURFACE_Y, z] = water
                }
            }
        }

        fun set(worldX: Int, y: Int, z: Int, state: BlockState?) {
            if (worldX !in chunkX until chunkX + ChunkSize.SECTION_WIDTH_X) return
            builder[worldX - chunkX, y, z] = state
        }

        set(100, CANARY_DETAIL_Y, 11, stone)
        set(101, CANARY_DETAIL_Y, 11, stairs)
        set(101, CANARY_DETAIL_Y, 12, stone)
        set(103, CANARY_DETAIL_Y, 11, torch)
        set(105, CANARY_SURFACE_Y, 11, farmland)
        return true
    }

    companion object {
        private const val BLOCK_SPACING = 2
        private const val ARENA_MINIMUM = -ChunkSize.SECTION_WIDTH_X
        private const val ARENA_MARGIN = ChunkSize.SECTION_WIDTH_X - 1
        private const val ARENA_FLOOR_Y = 7
        private val CANARY_CHUNK_X = 6..7
        private const val CANARY_CHUNK_Z = 6
        private const val CANARY_FLOOR_Y = 7
        private const val CANARY_SURFACE_Y = 8
        private const val CANARY_DETAIL_Y = 9
        private val CANARY_WATER_X = 108..115
        private val CANARY_WATER_Z = 3..8
        private val FALLBACK_PLAINS = Biome(
            minecraft("plains"),
            temperature = 0.8f,
            downfall = 0.4f,
            waterColor = RGBColor(0x3F76E4),
        )
        private val FALLBACK_SWAMP = Biome(
            minecraft("swamp"),
            temperature = 0.8f,
            downfall = 0.9f,
            waterColor = RGBColor(0x617B64),
        )
    }
}
