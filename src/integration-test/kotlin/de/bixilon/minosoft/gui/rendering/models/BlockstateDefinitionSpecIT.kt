/*
 * Minosoft
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

package de.bixilon.minosoft.gui.rendering.models

import de.bixilon.minosoft.assets.util.InputStreamUtil.readJsonObject
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperties
import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperty
import de.bixilon.minosoft.data.registries.blocks.settings.BlockSettings
import de.bixilon.minosoft.data.registries.blocks.types.Block
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.models.ModelTestUtil.createAssets
import de.bixilon.minosoft.gui.rendering.models.block.state.DirectBlockModel
import de.bixilon.minosoft.gui.rendering.models.block.state.apply.SingleBlockStateApply
import de.bixilon.minosoft.gui.rendering.models.block.state.apply.WeightedBlockStateApply
import de.bixilon.minosoft.gui.rendering.models.block.state.builder.BuilderApply
import de.bixilon.minosoft.gui.rendering.models.block.state.builder.BuilderBlockModel
import de.bixilon.minosoft.gui.rendering.models.block.state.variant.PropertyVariantBlockModel
import de.bixilon.minosoft.gui.rendering.models.block.state.variant.SingleVariantBlockModel
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/** Executable coverage for common vanilla Java Edition blockstate-definition forms. */
@Test(groups = ["models"])
class BlockstateDefinitionSpecIT {

    fun `variants support empty partial and weighted model definitions`() {
        val single = deserialize("""{"variants":{"":{"model":"minecraft:block/spec_a"}}}""")
        assertTrue(single is SingleVariantBlockModel)

        val model = deserialize("""
            {
              "variants": {
                "facing=north,lit=true": {
                  "model": "minecraft:block/spec_a",
                  "x": 90,
                  "y": 180,
                  "uvlock": true
                },
                "facing=south": [
                  {"model": "minecraft:block/spec_b"},
                  {"model": "minecraft:block/spec_c", "weight": 3}
                ]
              }
            }
        """.trimIndent()) as PropertyVariantBlockModel

        val rotated = model.choose(mapOf(BlockProperties.FACING to Directions.NORTH, BlockProperties.LIT to true)) as SingleBlockStateApply
        assertEquals(rotated.x, 1)
        assertEquals(rotated.y, 2)
        assertTrue(rotated.uvLock)

        val weighted = model.choose(mapOf(BlockProperties.FACING to Directions.SOUTH, BlockProperties.LIT to false)) as WeightedBlockStateApply
        assertEquals(weighted.models.map { it.weight }, listOf(1, 3))
        assertEquals(weighted.models.size, 2)
        assertEquals(model.choose(mapOf(BlockProperties.FACING to Directions.EAST, BlockProperties.LIT to true)), null)
    }

    fun `multipart supports unconditional direct pipe OR AND and weighted apply`() {
        val model = deserialize("""
            {
              "multipart": [
                {"apply": {"model": "minecraft:block/spec_a"}},
                {
                  "when": {"facing": "north|south", "lit": "true"},
                  "apply": [
                    {"model": "minecraft:block/spec_b", "weight": 2},
                    {"model": "minecraft:block/spec_c", "weight": 1}
                  ]
                },
                {
                  "when": {"OR": [{"facing": "east"}, {"facing": "west"}]},
                  "apply": {"model": "minecraft:block/spec_d", "y": 270}
                },
                {
                  "when": {"AND": [{"facing": "north"}, {"lit": "false"}]},
                  "apply": {"model": "minecraft:block/spec_e", "uvlock": true}
                }
              ]
            }
        """.trimIndent()) as BuilderBlockModel

        val northLit = properties(Directions.NORTH, true)
        val northDark = properties(Directions.NORTH, false)
        val eastLit = properties(Directions.EAST, true)
        assertEquals((model.choose(northLit) as BuilderApply).applies.size, 2)
        assertEquals((model.choose(northDark) as BuilderApply).applies.size, 2)
        assertEquals((model.choose(eastLit) as BuilderApply).applies.size, 2)

        assertTrue(model.parts[1].condition.matches(northLit))
        assertFalse(model.parts[1].condition.matches(northDark))
        assertTrue(model.parts[2].condition.matches(eastLit))
        assertTrue(model.parts[3].condition.matches(northDark))
        assertFalse(model.parts[3].condition.matches(northLit))

        val weighted = model.parts[1].apply as WeightedBlockStateApply
        assertEquals(weighted.models.map { it.weight }, listOf(2, 1))
        assertEquals((model.parts[2].apply as SingleBlockStateApply).y, 3)
        assertTrue((model.parts[3].apply as SingleBlockStateApply).uvLock)
    }

    private fun properties(facing: Directions, lit: Boolean): Map<BlockProperty<*>, Any> = mapOf(
        BlockProperties.FACING to facing,
        BlockProperties.LIT to lit,
    )

    private fun deserialize(json: String): DirectBlockModel {
        val loader = ModelTestUtil.createLoader()
        loader.createAssets(MODELS)
        val data = json.byteInputStream().readJsonObject()
        return requireNotNull(DirectBlockModel.deserialize(loader.block, BLOCK, data))
    }

    companion object {
        private val BLOCK = object : Block(minecraft("blockstate_definition_spec"), BlockSettings(IT.VERSION)) {
            override val hardness = 1.0f
        }
        private val MODELS = ('a'..'e').associate { suffix ->
            "block/spec_$suffix" to """{"textures":{"particle":"minecraft:block/stone"},"elements":[]}"""
        }
    }
}
