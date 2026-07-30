/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.beacon

import de.bixilon.minosoft.data.entities.block.BeaconBlockEntity
import de.bixilon.minosoft.data.registries.blocks.state.TestBlockStates
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["block_entity_renderer", "rendering"])
class BeaconBeamRendererTest {
    fun `active beacon exposes a translucent beam producer and exact Iris contract`() {
        val context = EntityRendererTestUtil.createContext()
        val entity = BeaconBlockEntity(
            context.session,
            BlockPosition(1, 2, 3),
            TestBlockStates.OPAQUE1,
        )
        entity.updateNBT(mapOf("Levels" to 3))
        val renderer = entity.createRenderer(context)
        val contract = context.shaders.beaconBeamShader.sceneContract

        assertEquals(3, entity.levels)
        assertEquals(BeaconBeamRenderer::class, renderer::class)
        assertTrue(renderer.hasTranslucentPass)
        assertEquals(SceneProgramFamily.BEACON_BEAM, contract.family)
        assertEquals(SceneVertexAbi.POSITION_TEXTURE, contract.vertexAbi)
        assertEquals(SceneStateAbi.BEACON_BEAM, contract.stateAbi)
    }

    fun `beacon level input is bounded`() {
        val context = EntityRendererTestUtil.createContext()
        val entity = BeaconBlockEntity(
            context.session,
            BlockPosition.EMPTY,
            TestBlockStates.OPAQUE1,
        )

        entity.updateNBT(mapOf("Levels" to 99))
        assertEquals(4, entity.levels)
        entity.updateNBT(mapOf("levels" to -4))
        assertEquals(0, entity.levels)
    }
}
