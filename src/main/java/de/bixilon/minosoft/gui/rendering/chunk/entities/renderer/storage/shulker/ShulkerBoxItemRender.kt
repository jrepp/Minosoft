/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.storage.shulker

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.Colors
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil
import de.bixilon.minosoft.gui.rendering.models.block.element.face.FaceUV
import de.bixilon.minosoft.gui.rendering.models.item.ItemRender
import de.bixilon.minosoft.gui.rendering.models.util.CuboidUtil
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture

/**
 * Resource-pack-aware compatibility rendering for the `builtin/entity`
 * shulker-box item family. The world block keeps using the skeletal model.
 */
class ShulkerBoxItemRender(
    private val texture: Texture,
) : ItemRender {
    override val particle: Texture = texture

    override fun render(gui: GUIRenderer, offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?, size: Vec2f, stack: ItemStack, tints: RGBArray?) {
        addGuiPart(consumer, offset, size, BODY_START, BODY_END, BODY_UV_START, BODY_UV_END, options)
        addGuiPart(consumer, offset, size, LID_START, LID_END, LID_UV_START, LID_UV_END, options)
    }

    private fun addGuiPart(consumer: GuiVertexConsumer, offset: Vec2f, size: Vec2f, start: Vec2f, end: Vec2f, uvStart: Vec2f, uvEnd: Vec2f, options: GUIVertexOptions?) {
        consumer.addQuad(offset + size * start, offset + size * end, texture, uvStart, uvEnd, ChatColors.WHITE, options)
    }

    override fun render(offset: Vec3f, consumer: BlockVertexConsumer, stack: ItemStack, tints: RGBArray?) {
        addWorldPart(consumer, offset, WORLD_BODY_FROM, WORLD_BODY_TO, BODY_UV_START, BODY_UV_END)
        addWorldPart(consumer, offset, WORLD_LID_FROM, WORLD_LID_TO, LID_UV_START, LID_UV_END)
    }

    private fun addWorldPart(consumer: BlockVertexConsumer, offset: Vec3f, from: Vec3f, to: Vec3f, uvStart: Vec2f, uvEnd: Vec2f) {
        val uv = FaceUV(
            texture.transformUV(uvStart),
            texture.transformUV(uvEnd),
        ).toArray(Directions.NORTH, 0).pack()
        consumer.addQuad(offset, CuboidUtil.positions(Directions.NORTH, from, to), uv, texture, 0xFF, Colors.WHITE_RGB, AmbientOcclusionUtil.EMPTY)
    }

    companion object {
        private const val TEXTURE_SIZE = 64.0f

        internal val BODY_UV_START = Vec2f(48.0f, 44.0f) / TEXTURE_SIZE
        internal val BODY_UV_END = Vec2f(64.0f, 52.0f) / TEXTURE_SIZE
        internal val LID_UV_START = Vec2f(48.0f, 16.0f) / TEXTURE_SIZE
        internal val LID_UV_END = Vec2f(64.0f, 28.0f) / TEXTURE_SIZE

        private val BODY_START = Vec2f(0.10f, 0.45f)
        private val BODY_END = Vec2f(0.90f, 0.88f)
        private val LID_START = Vec2f(0.075f, 0.16f)
        private val LID_END = Vec2f(0.925f, 0.52f)

        private val WORLD_BODY_FROM = Vec3f(0.15f, 0.0f, 0.50f)
        private val WORLD_BODY_TO = Vec3f(0.85f, 0.43f, 0.50f)
        private val WORLD_LID_FROM = Vec3f(0.125f, 0.43f, 0.50f)
        private val WORLD_LID_TO = Vec3f(0.875f, 0.78f, 0.50f)
    }
}
