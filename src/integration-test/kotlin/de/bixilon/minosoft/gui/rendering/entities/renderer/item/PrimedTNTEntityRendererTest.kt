/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.item

import de.bixilon.minosoft.data.entities.entities.item.PrimedTNT
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import de.bixilon.minosoft.gui.rendering.entities.feature.block.flashing.FlashingBlockFeature
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["entity_renderer", "rendering"])
class PrimedTNTEntityRendererTest {
    fun `primed TNT owns flashing block geometry and the exact Iris scene contract`() {
        val entities = EntityRendererTestUtil.create()
        val renderer = entities.create(PrimedTNT)

        assertTrue(renderer is PrimedTNTEntityRenderer)
        assertEquals(1, renderer.features.count { it is FlashingBlockFeature })
        assertEquals(SceneProgramFamily.BLOCK, entities.features.block.flashing.sceneContract.family)
        assertEquals(SceneVertexAbi.BLOCK_FEATURE, entities.features.block.flashing.sceneContract.vertexAbi)
        assertEquals(SceneStateAbi.FLASHING_BLOCK, entities.features.block.flashing.sceneContract.stateAbi)
    }

    fun `primed TNT gravity moves the retained producer downward`() {
        val entities = EntityRendererTestUtil.create()
        val entity = entities.createEntity(PrimedTNT)
        val initialY = entity.physics.position.y

        entity.preTick()
        entity.tick()

        assertTrue(entity.physics.velocity.y < 0.0)
        assertTrue(entity.physics.position.y < initialY)
    }
}
