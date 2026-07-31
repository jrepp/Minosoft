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

package de.bixilon.minosoft.gui.rendering.terrain.distant

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistantTerrainBuildPriorityTest {
    @Test
    fun `base pages nearest the seam are admitted before the center and far edge`() {
        val priority = DistantTerrainBuildPriority(0, 0, 10.0f)
        val seam = key(9, 0)
        val center = key(0, 0)
        val far = key(40, 0)

        assertEquals(listOf(seam, center, far), listOf(far, center, seam).sortedWith(priority.comparator))
        assertTrue(priority.isSeamCritical(seam, 2.0))
        assertFalse(priority.isSeamCritical(far, 2.0))
    }

    @Test
    fun `ready derived work retains priority over base coverage`() {
        val priority = DistantTerrainBuildPriority(0, 0, 10.0f)
        val base = key(9, 0)
        val parent = key(4, 0, detail = 1)

        assertEquals(listOf(parent, base), listOf(base, parent).sortedWith(priority.comparator))
    }

    @Test
    fun `camera movement deterministically changes the useful seam edge`() {
        val pages = listOf(key(-10, 0), key(30, 0))

        assertEquals(key(-10, 0), pages.sortedWith(DistantTerrainBuildPriority(0, 0, 10.0f).comparator).first())
        assertEquals(key(30, 0), pages.sortedWith(DistantTerrainBuildPriority(20, 0, 10.0f).comparator).first())
    }

    private fun key(x: Long, z: Long, detail: Int = 0) = TerrainPageKey(
        TerrainDomain.DISTANT,
        detail,
        x,
        0L,
        z,
        7L,
    )
}
