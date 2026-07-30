/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.outline

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferManager
import de.bixilon.minosoft.gui.rendering.input.key.manager.InputManager
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.texture.TextureModes
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["entities", "rendering"])
class EntityOutlineRendererTest {

    fun `dummy renderer owns mask composite and cleanup lifecycle`() {
        val context = EntityRendererTestUtil.createContext()
        context::thread.forceSet(Thread.currentThread())
        context::input.forceSet(InputManager(context))
        context::framebuffer.forceSet(FramebufferManager(context))
        context.framebuffer.main.size = Vec2i(32, 24)
        val mainFramebuffer = context.system.createFramebuffer(
            Vec2i(32, 24),
            1.0f,
            texture = TextureModes.NEAREST,
        ).also { it.init() }
        context.framebuffer.main.framebuffer = mainFramebuffer
        val outline = EntityOutlineRenderer(context)
        val feature = TestFeature()

        try {
            outline.init()
            outline.postInit()
            outline += EntityOutlineRenderer.Command(feature, ChatColors.LIGHT_PURPLE)
            outline.prepare()
            outline.draw()

            assertEquals(feature.prepares, 1)
            assertEquals(feature.draws, 1)
            assertEquals(feature.color, ChatColors.LIGHT_PURPLE)
        } finally {
            outline.unload()
            mainFramebuffer.delete()
            context.framebuffer.main.mesh.drop()
            context.framebuffer.gui.mesh.drop()
        }
    }

    private class TestFeature : EntityOutlineFeature, FeatureDrawable {
        var prepares = 0
        var draws = 0
        var color: RGBAColor? = null

        override val layer = EntityLayer.Opaque
        override val sort = 0
        override val distance2 = 0.0

        override fun prepare() {
            prepares++
        }

        override fun draw() = Unit

        override fun drawOutline(color: RGBAColor) {
            draws++
            this.color = color
        }
    }
}
