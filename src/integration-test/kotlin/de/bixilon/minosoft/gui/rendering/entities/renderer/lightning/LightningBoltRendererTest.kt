/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.lightning

import de.bixilon.minosoft.data.entities.entities.LightningBolt
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["entity_renderer", "rendering"])
class LightningBoltRendererTest {
    fun `lightning owns retained geometry and an exact Iris scene contract`() {
        val entities = EntityRendererTestUtil.create()
        val renderer = entities.create(LightningBolt)

        assertTrue(renderer is LightningBoltRenderer)
        assertEquals(1, renderer.features.count { it is LightningBoltFeature })
        assertEquals(SceneProgramFamily.LIGHTNING, entities.context.shaders.lightningShader.sceneContract.family)
        assertEquals(SceneVertexAbi.POSITION_COLOR, entities.context.shaders.lightningShader.sceneContract.vertexAbi)
        assertEquals(SceneStateAbi.LIGHTNING, entities.context.shaders.lightningShader.sceneContract.stateAbi)
    }
}
