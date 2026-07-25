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

data class MapPoint(val x: Double, val z: Double)

data class MapBounds(
    val minimumX: Double,
    val minimumZ: Double,
    val maximumX: Double,
    val maximumZ: Double,
)

class MapViewportState(
    center: MapPoint = MapPoint(0.0, 0.0),
    zoom: Double = 1.0,
) {
    var center: MapPoint = center
        private set
    var zoom: Double = zoom
        private set

    init {
        require(center.x.isFinite() && center.z.isFinite()) { "Map center must be finite." }
        require(zoom.isFinite() && zoom in MIN_ZOOM..MAX_ZOOM) { "Map zoom must be in $MIN_ZOOM..$MAX_ZOOM." }
    }

    fun panPixels(delta: Vec2f) {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return
        center = MapPoint(center.x - delta.x / zoom, center.z - delta.y / zoom)
    }

    fun worldToScreen(point: MapPoint, viewport: Vec2f): Vec2f {
        return Vec2f(
            (viewport.x / 2.0 + (point.x - center.x) * zoom).toFloat(),
            (viewport.y / 2.0 + (point.z - center.z) * zoom).toFloat(),
        )
    }

    fun screenToWorld(position: Vec2f, viewport: Vec2f): MapPoint {
        return MapPoint(
            center.x + (position.x - viewport.x / 2.0) / zoom,
            center.z + (position.y - viewport.y / 2.0) / zoom,
        )
    }

    fun zoomAt(factor: Double, anchor: Vec2f, viewport: Vec2f): Double {
        if (!factor.isFinite() || factor <= 0.0) return zoom
        val worldAnchor = screenToWorld(anchor, viewport)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val after = screenToWorld(anchor, viewport)
        center = MapPoint(
            center.x + worldAnchor.x - after.x,
            center.z + worldAnchor.z - after.z,
        )
        return zoom
    }

    fun bounds(viewport: Vec2f): MapBounds {
        val minimum = screenToWorld(Vec2f.EMPTY, viewport)
        val maximum = screenToWorld(viewport, viewport)
        return MapBounds(minimum.x, minimum.z, maximum.x, maximum.z)
    }

    companion object {
        const val MIN_ZOOM = 1.0 / 64.0
        const val MAX_ZOOM = 64.0
    }
}
