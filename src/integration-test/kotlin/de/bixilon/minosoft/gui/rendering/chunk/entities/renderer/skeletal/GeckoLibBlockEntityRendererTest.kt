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

package de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.skeletal

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.data.entities.block.container.storage.ChestBlockEntity
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedGeckoLibRenderLayer
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["skeletal", "block_entity_rendering"])
class GeckoLibBlockEntityRendererTest {
    fun `only non opaque Gecko layers opt into the single Iris block entity shadow submission`() {
        val opaque = renderer(GeckoLibRenderLayerBlend.OPAQUE)
        val translucent = renderer(GeckoLibRenderLayerBlend.TRANSLUCENT)
        val additive = renderer(GeckoLibRenderLayerBlend.ADDITIVE)
        try {
            assertFalse(opaque.hasTranslucentPass)
            assertFalse(opaque.castsTranslucentShadow)
            assertTrue(translucent.hasTranslucentPass)
            assertTrue(translucent.castsTranslucentShadow)
            assertTrue(additive.hasTranslucentPass)
            assertTrue(additive.castsTranslucentShadow)
        } finally {
            opaque.drop()
            translucent.drop()
            additive.drop()
        }
    }

    private fun renderer(blend: GeckoLibRenderLayerBlend): GeckoLibBlockEntityRenderer {
        val mesh = Mesh::class.java.allocate()
        val model = BakedSkeletalModel(
            mesh = mesh,
            transform = BakedSkeletalTransform(0, Vec3f.EMPTY, emptyMap()),
            transformCount = 1,
            animations = emptyMap(),
            geckoRenderLayers = mapOf(
                "acceptance" to BakedGeckoLibRenderLayer(
                    registrationId = 1L,
                    blend = blend,
                    fullBright = false,
                    mesh = mesh,
                ),
            ),
        )
        return GeckoLibBlockEntityRenderer(
            entity = ChestBlockEntity::class.java.allocate(),
            context = RenderContext::class.java.allocate(),
            contentModel = model,
        )
    }
}
