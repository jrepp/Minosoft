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

package de.bixilon.minosoft.gui.rendering.gui.elements.banner

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.text.TextComponent
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer

enum class StatusBannerKind(
    val background: RGBAColor,
    val foreground: RGBAColor,
) {
    INFO(RGBAColor(35, 70, 120, 230), RGBAColor(220, 235, 255)),
    WARNING(RGBAColor(120, 85, 20, 230), RGBAColor(255, 235, 180)),
    ERROR(RGBAColor(120, 30, 30, 230), RGBAColor(255, 215, 215)),
}

class StatusBannerElement(
    guiRenderer: GUIRenderer,
    message: String,
    kind: StatusBannerKind = StatusBannerKind.INFO,
) : Element(guiRenderer) {
    private val background = ColorElement(guiRenderer, Vec2f.EMPTY, kind.background)
    private val text = TextElement(guiRenderer, TextComponent(message).color(kind.foreground), background = null, parent = this)

    var message: String = message
        set(value) {
            if (field == value) return
            field = value
            updateText()
        }
    var kind: StatusBannerKind = kind
        set(value) {
            if (field == value) return
            field = value
            updateText()
        }

    init {
        forceSilentApply()
    }

    override fun forceSilentApply() {
        text.silentApply()
        val width = prefMaxSize.x.takeIf { it > 0.0f } ?: text.size.x + PADDING * 2
        text.prefMaxSize = Vec2f(maxOf(0.0f, width - PADDING * 2), -1.0f)
        val next = Vec2f(width, text.size.y + PADDING * 2)
        _prefSize = next
        _size = next
        background.size = next
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        background.render(offset, consumer, options)
        text.render(offset + PADDING, consumer, options)
    }

    override fun onChildChange(child: Element) {
        forceApply()
    }

    private fun updateText() {
        background.color = kind.background
        text._chatComponent = TextComponent(message).color(kind.foreground)
        forceApply()
    }

    private companion object {
        const val PADDING = 4.0f
    }
}
