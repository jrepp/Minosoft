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

package de.bixilon.minosoft.gui.rendering.gui.elements.gauge

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.gui.popper.text.TextPopper
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer

enum class GaugeDirection {
    LEFT_TO_RIGHT,
    BOTTOM_TO_TOP,
}

class GaugeElement(
    guiRenderer: GUIRenderer,
    gaugeSize: Vec2f,
    value: Long = 0L,
    maximum: Long = 1L,
    val direction: GaugeDirection = GaugeDirection.LEFT_TO_RIGHT,
    emptyColor: RGBAColor = RGBAColor(35, 35, 40, 255),
    fullColor: RGBAColor = RGBAColor(70, 180, 230, 255),
    private val tooltip: (Long, Long) -> Any = { current, max -> "$current / $max" },
) : Element(guiRenderer) {
    private val empty = ColorElement(guiRenderer, gaugeSize, emptyColor)
    private val full = ColorElement(guiRenderer, Vec2f.EMPTY, fullColor)
    private var popper: TextPopper? = null

    var value: Long = value
        set(value) {
            field = value.coerceAtLeast(0L)
            forceApply()
        }
    var maximum: Long = maximum
        set(value) {
            require(value > 0L) { "Gauge maximum must be positive." }
            field = value
            forceApply()
        }

    init {
        require(maximum > 0L) { "Gauge maximum must be positive." }
        _size = gaugeSize
        this.value = value
        forceSilentApply()
    }

    override fun forceSilentApply() {
        empty.size = size
        val progress = (value.toDouble() / maximum).coerceIn(0.0, 1.0).toFloat()
        full.size = when (direction) {
            GaugeDirection.LEFT_TO_RIGHT -> Vec2f(size.x * progress, size.y)
            GaugeDirection.BOTTOM_TO_TOP -> Vec2f(size.x, size.y * progress)
        }
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        empty.render(offset, consumer, options)
        val fullOffset = when (direction) {
            GaugeDirection.LEFT_TO_RIGHT -> offset
            GaugeDirection.BOTTOM_TO_TOP -> offset + Vec2f(0.0f, size.y - full.size.y)
        }
        full.render(fullOffset, consumer, options)
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        popper = TextPopper(guiRenderer, absolute, tooltip(value, maximum)).apply { show() }
        return true
    }

    override fun onMouseLeave(): Boolean {
        popper?.hide()
        popper = null
        return true
    }

    override fun onClose() {
        popper?.hide()
        popper = null
    }
}
