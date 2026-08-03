/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.text

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4Operations
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.entities.entities.display.DisplayTextStyle
import de.bixilon.minosoft.data.entities.entities.display.DisplayTextStyleInterpolator
import de.bixilon.minosoft.data.entities.entities.display.TextDisplayAlignment
import de.bixilon.minosoft.data.entities.entities.display.TextDisplayEntity
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.RenderConstants
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.font.renderer.component.ChatComponentRenderer
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderInfo
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.data.text.formatting.color.Colors
import kotlin.time.Duration

class TextDisplayFeature(
    renderer: EntityRenderer<TextDisplayEntity>,
) : MeshedFeature<Mesh>(renderer, EntityRenderStateKeys.TEXT_DISPLAY), EntityOutlineFeature {
    private val displayEntity = renderer.entity
    private var renderKey: RenderKey? = null
    private var info: TextRenderInfo? = null
    private val matrix = MMat4f()
    private val style = DisplayTextStyleInterpolator(
        DisplayTextStyle(displayEntity.textOpacity.toInt(), displayEntity.background),
    )

    override val layer get() = EntityLayer.Translucent

    override fun update(delta: Duration) {
        super.update(delta)
        style.target(
            DisplayTextStyle(displayEntity.textOpacity.toInt(), displayEntity.background),
            displayEntity.interpolationStartDeltaTicks,
            displayEntity.interpolationDurationTicks,
        )
        val key = RenderKey.of(displayEntity, style.advance(delta))
        if (key != renderKey) {
            renderKey = key
            rebuild(key)
        }
        updateMatrix()
    }

    private fun rebuild(key: RenderKey) {
        if (key.text.length == 0) {
            mesh = null
            info = null
            return
        }
        val properties = TextRenderProperties(
            alignment = key.alignment.horizontal,
            shadow = key.shadow,
            allowNewLine = true,
        )
        val builder = OpacityTextMeshBuilder(renderer.renderer.context, key.opacity)
        val background = if (key.defaultBackground) RenderConstants.TEXT_BACKGROUND_COLOR else argb(key.background)
        val maxSize = Vec2f(key.lineWidth.coerceIn(1, MAX_LINE_WIDTH).toFloat(), Float.MAX_VALUE)
        val text = key.text.copy().also { if (it.length > MAX_TEXT_LENGTH) it.cut(MAX_TEXT_LENGTH) }
        info = ChatComponentRenderer.render3d(renderer.renderer.context, properties, maxSize, builder, text, background)
        mesh = builder.bake()
    }

    private fun updateMatrix() {
        val size = info?.size ?: return
        matrix.clearAssign()
        matrix.translateAssign(
            -size.x * BillboardTextMeshBuilder.SCALE / 2.0f,
            -size.y * BillboardTextMeshBuilder.SCALE / 2.0f,
            0.0f,
        )
        Mat4Operations.times(renderer.matrix.unsafe, matrix.unsafe, matrix)
    }

    override fun draw(mesh: Mesh) {
        val system = renderer.renderer.context.system
        try {
            system.reset(
                blending = true,
                sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                sourceAlpha = BlendingFunctions.SOURCE_ALPHA,
                destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                depth = if (displayEntity.seeThrough) DepthFunctions.ALWAYS else DepthFunctions.LESS_OR_EQUAL,
                depthMask = !displayEntity.seeThrough,
                faceCulling = false,
            )
            val shader = renderer.renderer.features.text.shader
            shader.use()
            shader.matrix = matrix.unsafe
            shader.tint = renderer.light.value
            super.draw(mesh)
        } finally {
            system.reset()
        }
    }

    override fun drawOutline(color: RGBAColor) {
        val mesh = this.mesh ?: return
        val system = renderer.renderer.context.system
        val shader = renderer.renderer.features.text.shader
        try {
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
            shader.outlineColor = color
            shader.use()
            shader.matrix = matrix.unsafe
            shader.tint = renderer.light.value
            super.draw(mesh)
        } finally {
            shader.outlineColor = Colors.TRANSPARENT
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
        }
    }

    private class OpacityTextMeshBuilder(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        private val opacity: Int,
    ) : BillboardTextMeshBuilder(context) {
        override fun addChar(
            start: Vec2f,
            end: Vec2f,
            texture: de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture,
            uvStart: Vec2f,
            uvEnd: Vec2f,
            italic: Boolean,
            tint: RGBAColor,
            options: de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions?,
        ) {
            super.addChar(start, end, texture, uvStart, uvEnd, italic, tint.with(alpha = tint.alpha * opacity / 255), options)
        }
    }

    private data class RenderKey(
        val text: de.bixilon.minosoft.data.text.ChatComponent,
        val lineWidth: Int,
        val background: Int,
        val opacity: Int,
        val shadow: Boolean,
        val defaultBackground: Boolean,
        val alignment: TextDisplayAlignment,
    ) {
        companion object {
            fun of(entity: TextDisplayEntity, style: DisplayTextStyle) = RenderKey(
                entity.text,
                entity.lineWidth,
                style.background,
                style.opacity and 0xFF,
                entity.shadow,
                entity.defaultBackground,
                entity.alignment,
            )
        }
    }

    private companion object {
        const val MAX_LINE_WIDTH = 32_768
        const val MAX_TEXT_LENGTH = 4_096

        fun argb(value: Int) = RGBAColor(
            red = value ushr 16 and 0xFF,
            green = value ushr 8 and 0xFF,
            blue = value and 0xFF,
            alpha = value ushr 24 and 0xFF,
        )

        val TextDisplayAlignment.horizontal: HorizontalAlignments
            get() = when (this) {
                TextDisplayAlignment.LEFT -> HorizontalAlignments.LEFT
                TextDisplayAlignment.CENTER -> HorizontalAlignments.CENTER
                TextDisplayAlignment.RIGHT -> HorizontalAlignments.RIGHT
            }
    }
}
