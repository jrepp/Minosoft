/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.entities.entities.player.properties.textures.metadata.SkinModel
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureListener
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureState
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.skin.PlayerSkin
import kotlin.math.floor

internal data class PlayerSkinQuad(
    val start: Vec2f,
    val end: Vec2f,
    val uvStart: Vec2f,
    val uvEnd: Vec2f,
)

internal object PlayerSkinLayout {
    private const val TEXTURE_SIZE = 64.0f

    fun build(area: Vec2f, model: SkinModel): List<PlayerSkinQuad> {
        val armWidth = if (model == SkinModel.SLIM) 3.0f else 4.0f
        val bodyWidth = 8.0f + armWidth * 2.0f
        val scale = maxOf(1.0f, floor(minOf(area.x / bodyWidth, area.y / 32.0f)))
        val origin = Vec2f((area.x - bodyWidth * scale) / 2.0f, (area.y - 32.0f * scale) / 2.0f)
        val center = origin.x + bodyWidth * scale / 2.0f
        val headX = center - 4.0f * scale
        val torsoX = center - 4.0f * scale
        val leftArmX = torsoX - armWidth * scale
        val rightArmX = torsoX + 8.0f * scale
        val leftLegX = center - 4.0f * scale
        val rightLegX = center
        val headY = origin.y
        val torsoY = origin.y + 8.0f * scale
        val legsY = origin.y + 20.0f * scale

        fun quad(x: Float, y: Float, width: Float, height: Float, u: Float, v: Float, uvWidth: Float = width, uvHeight: Float = height) =
            PlayerSkinQuad(
                start = Vec2f(x, y),
                end = Vec2f(x + width * scale, y + height * scale),
                uvStart = Vec2f(u / TEXTURE_SIZE, v / TEXTURE_SIZE),
                uvEnd = Vec2f((u + uvWidth) / TEXTURE_SIZE, (v + uvHeight) / TEXTURE_SIZE),
            )

        return listOf(
            // Base skin.
            quad(headX, headY, 8.0f, 8.0f, 8.0f, 8.0f),
            quad(torsoX, torsoY, 8.0f, 12.0f, 20.0f, 20.0f),
            quad(leftArmX, torsoY, armWidth, 12.0f, 44.0f, 20.0f),
            quad(rightArmX, torsoY, armWidth, 12.0f, 36.0f, 52.0f),
            quad(leftLegX, legsY, 4.0f, 12.0f, 4.0f, 20.0f),
            quad(rightLegX, legsY, 4.0f, 12.0f, 20.0f, 52.0f),
            // Transparent outer skin layers.
            quad(headX, headY, 8.0f, 8.0f, 40.0f, 8.0f),
            quad(torsoX, torsoY, 8.0f, 12.0f, 20.0f, 36.0f),
            quad(leftArmX, torsoY, armWidth, 12.0f, 44.0f, 36.0f),
            quad(rightArmX, torsoY, armWidth, 12.0f, 52.0f, 52.0f),
            quad(leftLegX, legsY, 4.0f, 12.0f, 4.0f, 36.0f),
            quad(rightLegX, legsY, 4.0f, 12.0f, 4.0f, 52.0f),
        )
    }
}

internal class PlayerSkinElement(
    guiRenderer: GUIRenderer,
    size: Vec2f,
) : Element(guiRenderer, 12), DynamicTextureListener {
    private val skin: PlayerSkin? = context.textures.skins.getSkin(guiRenderer.session.player, fetch = false, async = true)
    private val quads = PlayerSkinLayout.build(size, skin?.model ?: SkinModel.WIDE)

    init {
        this._size = size
        skin?.texture?.addListener(this)
        skin?.default?.takeIf { it !== skin.texture }?.addListener(this)
    }

    private fun availableTexture(): ShaderTexture {
        val primary = skin?.texture
        if (primary?.state == DynamicTextureState.LOADED) return primary
        val fallback = skin?.default
        if (fallback?.state == DynamicTextureState.LOADED) return fallback
        return context.textures.debugTexture
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        val texture = availableTexture()
        for (quad in quads) {
            consumer.addQuad(offset + quad.start, offset + quad.end, texture, quad.uvStart, quad.uvEnd, ChatColors.WHITE, options)
        }
    }

    override fun forceSilentApply() = Unit

    override fun onDynamicTextureChange(texture: DynamicTexture): Boolean {
        cacheUpToDate = false
        return false
    }

    override fun onClose() {
        skin?.texture?.removeListener(this)
        skin?.default?.takeIf { it !== skin.texture }?.removeListener(this)
    }
}
