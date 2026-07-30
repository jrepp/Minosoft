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

package de.bixilon.minosoft.local.generator

import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotSame
import org.testng.annotations.Test

@Test(groups = ["chunk"])
class DebugGeneratorTest {

    fun `terrain canary spans water lighting detail and biome seam`() {
        val session = createSession()
        val generator = DebugGenerator(session)
        session.registries.biome.add(
            null,
            Biome(minecraft("plains"), temperature = 0.8f, downfall = 0.4f, waterColor = RGBColor(0x3F76E4)),
        )
        session.registries.biome.add(
            null,
            Biome(minecraft("swamp"), temperature = 0.8f, downfall = 0.9f, waterColor = RGBColor(0x617B64)),
        )
        val swamp = ChunkBuilder(session.world, ChunkPosition(6, 6)).also(generator::generate)
        val plains = ChunkBuilder(session.world, ChunkPosition(7, 6)).also(generator::generate)

        assertEquals(swamp[12, 8, 3]?.block?.identifier?.toString(), "minecraft:water")
        assertEquals(plains[3, 8, 3]?.block?.identifier?.toString(), "minecraft:water")
        assertEquals(swamp[5, 9, 11]?.block?.identifier?.toString(), "minecraft:stone_brick_stairs")
        assertEquals(swamp[7, 9, 11]?.block?.identifier?.toString(), "minecraft:torch")
        assertEquals(swamp[9, 8, 11]?.block?.identifier?.toString(), "minecraft:farmland")

        val swampBiomes = swamp.toData().biomeSource
        val plainsBiomes = plains.toData().biomeSource
        assertNotSame(swampBiomes, plainsBiomes)
        assertEquals(swampBiomes?.get(InChunkPosition(0, 8, 0))?.identifier?.toString(), "minecraft:swamp")
        assertEquals(plainsBiomes?.get(InChunkPosition(0, 8, 0))?.identifier?.toString(), "minecraft:plains")

        val fallbackSession = createSession()
        val fallback = ChunkBuilder(fallbackSession.world, ChunkPosition(6, 6))
            .also(DebugGenerator(fallbackSession)::generate)
            .toData()
            .biomeSource
        assertEquals(fallback?.get(InChunkPosition(0, 8, 0))?.identifier?.toString(), "minecraft:swamp")
    }
}
