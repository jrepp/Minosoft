/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.world.border

import de.bixilon.kmath.vec.vec2.d.Vec2d
import de.bixilon.minosoft.data.world.border.area.StaticBorderArea
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorldBorderTest {

    @Test
    fun `distance follows translated border center`() {
        val border = WorldBorder()
        border.center = Vec2d(100.0, -50.0)
        border.area = StaticBorderArea(8.0)

        assertEquals(8.0, border.getDistanceTo(100.0, -50.0))
        assertEquals(1.0, border.getDistanceTo(107.0, -50.0))
        assertEquals(2.0, border.getDistanceTo(100.0, -56.0))
        assertEquals(-1.0, border.getDistanceTo(109.0, -50.0))
    }

    @Test
    fun `distance honors absolute world limits`() {
        val border = WorldBorder()
        border.center = Vec2d(WorldBorder.MAX_RADIUS, 0.0)
        border.area = StaticBorderArea(8.0)

        assertEquals(0.0, border.getDistanceTo(WorldBorder.MAX_RADIUS, 0.0))
        assertEquals(-1.0, border.getDistanceTo(WorldBorder.MAX_RADIUS + 1.0, 0.0))
    }
}
