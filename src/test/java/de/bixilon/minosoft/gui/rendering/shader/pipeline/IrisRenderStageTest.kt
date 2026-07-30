/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import kotlin.test.Test
import kotlin.test.assertEquals

class IrisRenderStageTest {
    @Test
    fun `pinned Iris stage values do not depend on local enum order`() {
        assertEquals(0, IrisRenderStage.NONE.shaderValue)
        assertEquals(8, IrisRenderStage.TERRAIN_SOLID.shaderValue)
        assertEquals(11, IrisRenderStage.ENTITIES.shaderValue)
        assertEquals(23, IrisRenderStage.HAND_TRANSLUCENT.shaderValue)
    }

    @Test
    fun `terrain materials map to pinned Iris stages`() {
        assertEquals(IrisRenderStage.TERRAIN_SOLID, IrisRenderStage.terrain(TerrainMaterialClass.OPAQUE))
        assertEquals(
            IrisRenderStage.TERRAIN_CUTOUT_MIPPED,
            IrisRenderStage.terrain(TerrainMaterialClass.CUTOUT),
        )
        assertEquals(
            IrisRenderStage.TERRAIN_TRANSLUCENT,
            IrisRenderStage.terrain(TerrainMaterialClass.TRANSLUCENT),
        )
    }

    @Test
    fun `scene semantics retain specialized hand and cloud stages`() {
        assertEquals(
            IrisRenderStage.HAND_TRANSLUCENT,
            IrisRenderStage.scene(
                PipelineSemantic.HAND,
                contract(SceneProgramFamily.HAND_WATER, SceneStateAbi.HELD_ITEM),
            ),
        )
        assertEquals(
            IrisRenderStage.CLOUDS,
            IrisRenderStage.scene(
                PipelineSemantic.SKY,
                contract(SceneProgramFamily.CLOUDS, SceneStateAbi.CLOUD),
            ),
        )
        assertEquals(
            IrisRenderStage.MOON,
            IrisRenderStage.scene(
                PipelineSemantic.SKY,
                contract(SceneProgramFamily.MOON, SceneStateAbi.PLANET),
            ),
        )
        assertEquals(
            IrisRenderStage.WORLD_BORDER,
            IrisRenderStage.scene(
                PipelineSemantic.WORLD_OVERLAY,
                contract(SceneProgramFamily.TEXTURED, SceneStateAbi.WORLD_BORDER),
            ),
        )
    }

    private fun contract(family: SceneProgramFamily, state: SceneStateAbi) =
        SceneShaderContract(family, SceneVertexAbi.POSITION_COLOR, state)
}
