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

package de.bixilon.minosoft.gui.rendering.gui.elements.map

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.gui.popper.text.TextPopper
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.GuiClipRect
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.ClippedGuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer

data class MapMarker<T : Any>(
    val id: T,
    val position: MapPoint,
    val label: String,
    val color: RGBAColor = RGBAColor(255, 80, 80),
)

data class MapCanvasRenderContext(
    val offset: Vec2f,
    val size: Vec2f,
    val viewport: MapViewportState,
    val bounds: MapBounds,
)

fun interface MapCanvasRenderer {
    fun render(context: MapCanvasRenderContext, consumer: GuiVertexConsumer, options: GUIVertexOptions?)
}

class MapCanvasElement<T : Any>(
    guiRenderer: GUIRenderer,
    canvasSize: Vec2f,
    val viewport: MapViewportState = MapViewportState(),
    markers: List<MapMarker<T>> = emptyList(),
    private val canvasRenderer: MapCanvasRenderer,
    private val onSelect: (MapMarker<T>) -> Unit = {},
    private val onEdit: (MapMarker<T>) -> Unit = {},
) : Element(guiRenderer) {
    private val background = ColorElement(guiRenderer, canvasSize, RGBAColor(15, 18, 20, 255))
    private var markers = markers.toList()
    private var dragging = false
    private var lastAbsolute: Vec2f? = null
    private var hovered: MapMarker<T>? = null
    private var popper: TextPopper? = null

    init {
        require(this.markers.size <= MAX_MARKERS) { "A map canvas may contain at most $MAX_MARKERS markers." }
        size = canvasSize
    }

    fun setMarkers(markers: List<MapMarker<T>>) {
        require(markers.size <= MAX_MARKERS) { "A map canvas may contain at most $MAX_MARKERS markers." }
        this.markers = markers.toList()
        clearHover()
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        background.size = size
        background.render(offset, consumer, options)
        val clipped = ClippedGuiVertexConsumer(consumer, GuiClipRect(offset, offset + size))
        canvasRenderer.render(MapCanvasRenderContext(offset, size, viewport, viewport.bounds(size)), clipped, options)
        for (marker in markers) {
            val position = viewport.worldToScreen(marker.position, size)
            ColorElement(guiRenderer, Vec2f(MARKER_SIZE), marker.color).render(
                offset + position - Vec2f(MARKER_SIZE / 2.0f),
                clipped,
                options,
            )
        }
    }

    override fun forceSilentApply() {
        background.size = size
        cacheUpToDate = false
    }

    override fun onMouseMove(position: Vec2f, absolute: Vec2f): Boolean {
        if (dragging) {
            lastAbsolute?.let { viewport.panPixels(absolute - it) }
            lastAbsolute = absolute
            clearHover()
            cacheUpToDate = false
            return true
        }
        val marker = markerAt(position)
        if (marker != hovered) {
            clearHover()
            hovered = marker
            marker?.let {
                popper = TextPopper(guiRenderer, absolute, it.label).apply { show() }
            }
        }
        return true
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (button == MouseButtons.MIDDLE || button == MouseButtons.LEFT && markerAt(position) == null) {
            dragging = action == MouseActions.PRESS
            lastAbsolute = null
            return true
        }
        if (action != MouseActions.PRESS) return true
        val marker = markerAt(position) ?: return true
        when (button) {
            MouseButtons.LEFT -> onSelect(marker)
            MouseButtons.RIGHT -> onEdit(marker)
            else -> Unit
        }
        return true
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        val factor = when {
            scrollOffset.y > 0.0f -> ZOOM_STEP
            scrollOffset.y < 0.0f -> 1.0 / ZOOM_STEP
            else -> return true
        }
        viewport.zoomAt(factor, position, size)
        clearHover()
        cacheUpToDate = false
        return true
    }

    override fun onMouseLeave(): Boolean {
        dragging = false
        lastAbsolute = null
        clearHover()
        return true
    }

    override fun onClose() {
        clearHover()
    }

    private fun markerAt(position: Vec2f): MapMarker<T>? {
        return markers.asReversed().firstOrNull { marker ->
            val screen = viewport.worldToScreen(marker.position, size)
            kotlin.math.abs(screen.x - position.x) <= MARKER_HIT_SIZE &&
                kotlin.math.abs(screen.y - position.y) <= MARKER_HIT_SIZE
        }
    }

    private fun clearHover() {
        popper?.hide()
        popper = null
        hovered = null
    }

    private companion object {
        const val MAX_MARKERS = 100_000
        const val MARKER_SIZE = 5.0f
        const val MARKER_HIT_SIZE = 5.0f
        const val ZOOM_STEP = 1.25
    }
}
