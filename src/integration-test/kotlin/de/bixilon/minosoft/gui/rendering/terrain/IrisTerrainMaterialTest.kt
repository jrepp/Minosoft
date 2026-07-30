/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.minosoft.data.registries.blocks.state.TestBlockStates
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisBlockIdRule
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisIdMaps
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisLegacyBlockIds
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["chunk_renderer"])
class IrisTerrainMaterialTest {
    fun `built in terrain layout exposes the complete Iris material payload`() {
        val layout = BuiltInTerrainVertexLayout.VALUE

        assertEquals(layout.strideBytes, 84)
        assertEquals(
            layout.attributes.map { it.semantic }.toSet(),
            setOf(
                VertexSemantic.POSITION,
                VertexSemantic.TEXTURE_COORDINATE,
                VertexSemantic.TEXTURE_LAYER,
                VertexSemantic.PACKED_LIGHT_COLOR,
                VertexSemantic.BLOCK_ID,
                VertexSemantic.MID_TEXTURE_COORDINATE,
                VertexSemantic.TANGENT,
                VertexSemantic.NORMAL,
                VertexSemantic.MID_BLOCK,
            ),
        )
    }

    fun `quad derives shared mid texture normal and tangent`() {
        val quad = IrisTerrainQuad.calculate(
            floatArrayOf(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
            ),
            UnpackedUVArray(
                floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f,
                    0.0f, 1.0f,
                ),
            ).pack(),
        )

        assertEquals(quad.midTexture.x, 0.5f, 0.001f)
        assertEquals(quad.midTexture.y, 0.5f, 0.001f)
        assertEquals(quad.normal.x, 0.0f, 0.001f)
        assertEquals(quad.normal.y, 0.0f, 0.001f)
        assertEquals(quad.normal.z, 1.0f, 0.001f)
        assertEquals(quad.tangent.x, 1.0f, 0.001f)
        assertEquals(quad.tangent.y, 0.0f, 0.001f)
        assertEquals(quad.tangent.z, 0.0f, 0.001f)
        assertEquals(quad.tangent.w, 1.0f, 0.001f)
    }

    fun `resolver snapshots mapped block identity render type center and luminance`() {
        val state = TestBlockStates.MODEL1
        val resolver = IrisTerrainMaterialResolver(
            generation = "a".repeat(64),
            maps = IrisIdMaps(
                blocks = listOf(IrisBlockIdRule(41, state.block.identifier)),
            ),
        ) { _, _ -> false }

        val block = resolver.resolve(state, 1.5f, 2.5f, 3.5f, fluid = false)
        val fluid = resolver.resolve(state, 1.5f, 2.5f, 3.5f, fluid = true)

        assertEquals(block.blockId, 41)
        assertEquals(block.renderType, IrisTerrainMaterial.BLOCK_RENDER_TYPE)
        assertEquals(block.centerX, 1.5f)
        assertEquals(block.centerY, 2.5f)
        assertEquals(block.centerZ, 3.5f)
        assertEquals(block.lightValue, state.luminance)
        assertEquals(fluid.renderType, IrisTerrainMaterial.FLUID_RENDER_TYPE)
        assertTrue(resolver.generation!!.all { it == 'a' })
    }

    fun `pinned Iris legacy defaults resolve real registry block states`() {
        val resolver = IrisTerrainMaterialResolver(
            generation = "b".repeat(64),
            maps = IrisIdMaps(blocks = IrisLegacyBlockIds.RULES),
        ) { _, _ -> false }
        fun block(path: String) =
            IT.REGISTRIES.block[ResourceLocation("minecraft", path)]!!.states.default

        assertEquals(resolver.resolve(block("stone"), 0.0f, 0.0f, 0.0f, fluid = false).blockId, 1)
        assertEquals(resolver.resolve(block("red_wool"), 0.0f, 0.0f, 0.0f, fluid = false).blockId, 35)
        assertEquals(resolver.resolve(block("emerald_block"), 0.0f, 0.0f, 0.0f, fluid = false).blockId, -123)
        assertEquals(resolver.resolve(block("lily_pad"), 0.0f, 0.0f, 0.0f, fluid = false).blockId, 111)
    }
}
