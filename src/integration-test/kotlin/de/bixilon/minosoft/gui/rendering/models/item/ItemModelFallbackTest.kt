/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.gui.test.GuiRenderTestUtil
import de.bixilon.minosoft.gui.rendering.models.ModelTestUtil.prepareDummyTextures
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement
import de.bixilon.minosoft.gui.rendering.models.block.element.face.ModelFace
import de.bixilon.minosoft.gui.rendering.models.block.state.baked.BakedModel
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["models", "items"])
class ItemModelFallbackTest {

    fun `particle-only model remains unsupported without explicit fallback`() {
        val textures = GuiRenderTestUtil.create().context.textures
        val model = ItemModel(textures = mapOf("particle" to "minecraft:block/oak_planks"))

        assertNull(model.load(textures))
    }

    fun `particle-only block item gets a visible compatibility model`() {
        val textures = GuiRenderTestUtil.create().context.textures
        val model = ItemModel(textures = mapOf("particle" to "minecraft:block/oak_planks"))
        val render = model.load(textures, useParticleFallback = true)

        assertNotNull(render)
        assertEquals(render!!.particle, render.bake().particle)
    }

    fun `element item model bakes as retained 3d geometry`() {
        val textures = GuiRenderTestUtil.create().context.textures
        val model = ItemModel(
            textures = mapOf("body" to minecraft("block/oak_planks").texture()),
            elements = listOf(
                ModelElement(
                    from = Vec3f.EMPTY,
                    to = Vec3f(1.0f),
                    faces = mapOf(Directions.NORTH to ModelFace("#body", null, 0)),
                ),
            ),
        )

        val prototype = model.load(textures)
        textures.prepareDummyTextures()
        val render = prototype?.bake()

        assertTrue(render is BakedModel)
    }
}
