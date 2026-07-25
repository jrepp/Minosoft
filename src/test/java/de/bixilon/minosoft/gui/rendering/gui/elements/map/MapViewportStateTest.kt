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
import kotlin.test.Test
import kotlin.test.assertEquals

class MapViewportStateTest {
    @Test
    fun `world and screen conversion round trip`() {
        val viewport = MapViewportState(MapPoint(100.0, -20.0), zoom = 2.0)
        val size = Vec2f(300.0f, 200.0f)
        val world = MapPoint(115.0, 5.0)

        assertEquals(world, viewport.screenToWorld(viewport.worldToScreen(world, size), size))
    }

    @Test
    fun `zoom preserves world coordinate below cursor`() {
        val viewport = MapViewportState(MapPoint(10.0, 20.0), zoom = 1.0)
        val size = Vec2f(200.0f, 100.0f)
        val cursor = Vec2f(30.0f, 80.0f)
        val before = viewport.screenToWorld(cursor, size)

        viewport.zoomAt(4.0, cursor, size)

        assertEquals(before, viewport.screenToWorld(cursor, size))
    }

    @Test
    fun `panning moves center opposite screen delta`() {
        val viewport = MapViewportState(zoom = 2.0)
        viewport.panPixels(Vec2f(20.0f, -10.0f))
        assertEquals(MapPoint(-10.0, 5.0), viewport.center)
    }
}
