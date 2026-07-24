/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.storage.shulker

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.container.TestItem1
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.DummyGuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.test.GuiRenderTestUtil
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTexture
import org.testng.Assert.assertEquals
import org.testng.Assert.assertSame
import org.testng.annotations.Test

@Test(groups = ["models", "gui"])
class ShulkerBoxItemRenderTest {

    fun `shulker item uses its entity texture for body and lid`() {
        val texture = DummyTexture()
        val render = ShulkerBoxItemRender(texture)
        val consumer = RecordingConsumer()

        render.render(
            GuiRenderTestUtil.create(),
            Vec2f(10.0f, 20.0f),
            consumer,
            null,
            Vec2f(16.0f),
            ItemStack(TestItem1),
            null,
        )

        assertEquals(consumer.quads.size, 2)
        assertSame(consumer.quads[0].texture, texture)
        assertEquals(consumer.quads[0].uvStart, Vec2f(48.0f, 44.0f) / 64.0f)
        assertEquals(consumer.quads[0].uvEnd, Vec2f(64.0f, 52.0f) / 64.0f)
        assertEquals(consumer.quads[1].uvStart, Vec2f(48.0f, 16.0f) / 64.0f)
        assertEquals(consumer.quads[1].uvEnd, Vec2f(64.0f, 28.0f) / 64.0f)
    }

    private class RecordingConsumer : DummyGuiVertexConsumer() {
        val quads = mutableListOf<Quad>()

        override fun addQuad(startX: Float, startY: Float, endX: Float, endY: Float, texture: ShaderTexture, uvStartX: Float, uvStartY: Float, uvEndX: Float, uvEndY: Float, tint: RGBAColor, options: GUIVertexOptions?) {
            quads += Quad(texture, Vec2f(uvStartX, uvStartY), Vec2f(uvEndX, uvEndY))
        }
    }

    private data class Quad(
        val texture: ShaderTexture,
        val uvStart: Vec2f,
        val uvEnd: Vec2f,
    )
}
