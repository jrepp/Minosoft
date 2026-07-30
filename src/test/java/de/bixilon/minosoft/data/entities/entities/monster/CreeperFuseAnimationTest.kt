/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.data.entities.entities.monster

import kotlin.test.Test
import kotlin.test.assertEquals

class CreeperFuseAnimationTest {
    @Test
    fun `fuse interpolation crosses the first vanilla white blink`() {
        val animation = CreeperFuseAnimation()
        repeat(13) { animation.tick(1) }

        animation.tick(1)

        assertEquals(0.0f, animation.whiteOverlayProgress(0.0f))
        assertEquals(0.5f, animation.whiteOverlayProgress(1.0f))
    }

    @Test
    fun `white overlay follows vanilla ten-step blink cadence`() {
        assertEquals(
            20.0f / 28.0f,
            CreeperFuseAnimation.whiteOverlayProgress(20, 20, 0.5f),
        )
        assertEquals(
            0.0f,
            CreeperFuseAnimation.whiteOverlayProgress(17, 17, 0.5f),
        )
        assertEquals(
            27.0f / 28.0f,
            CreeperFuseAnimation.whiteOverlayProgress(27, 27, 0.5f),
        )
    }

    @Test
    fun `fuse counters clamp while disarming and overdriving`() {
        val animation = CreeperFuseAnimation()
        animation.tick(-1)
        assertEquals(0.0f, animation.whiteOverlayProgress(1.0f))

        repeat(100) { animation.tick(1) }
        animation.tick(-1)
        assertEquals(0.0f, animation.whiteOverlayProgress(1.0f))
        animation.tick(-1)
        assertEquals(0.0f, animation.whiteOverlayProgress(1.0f))
        animation.tick(-1)
        assertEquals(27.0f / 28.0f, animation.whiteOverlayProgress(1.0f))
    }

    @Test
    fun `invalid partial ticks clamp to the render interval`() {
        assertEquals(
            CreeperFuseAnimation.whiteOverlayProgress(13, 14, 0.0f),
            CreeperFuseAnimation.whiteOverlayProgress(13, 14, -1.0f),
        )
        assertEquals(
            CreeperFuseAnimation.whiteOverlayProgress(13, 14, 1.0f),
            CreeperFuseAnimation.whiteOverlayProgress(13, 14, 2.0f),
        )
    }
}
