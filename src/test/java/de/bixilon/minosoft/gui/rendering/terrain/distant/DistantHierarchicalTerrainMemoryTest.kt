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

class DistantHierarchicalTerrainMemoryTest {
    @Test
    fun `derived cpu eviction preserves bases requirements and bounded newest warm pages`() {
        val base = page(detail = 0, x = 0)
        val required = page(detail = 2, x = 1)
        val old = page(detail = 1, x = 2)
        val warm = page(detail = 1, x = 3)

        assertEquals(
            listOf(old),
            distantDerivedCpuEvictions(
                pagesInInsertionOrder = listOf(base, required, old, warm),
                required = setOf(required),
                maximumWarmPages = 1,
            ),
        )
    }

    @Test
    fun `artifact eviction preserves unavailable pages demanded by an atomic selection transition`() {
        val active = page(detail = 1, x = 1)
        val pendingReplacement = page(detail = 2, x = 2)
        val stale = page(detail = 1, x = 3)

        assertEquals(
            listOf(stale),
            distantCpuArtifactEvictions(
                artifactsInInsertionOrder = listOf(active, pendingReplacement, stale),
                retained = setOf(active),
                demanded = setOf(pendingReplacement),
            ),
        )
    }

    private fun page(detail: Int, x: Long) = TerrainPageKey(
        domain = TerrainDomain.DISTANT,
        detailLevel = detail,
        x = x,
        y = 0L,
        z = 0L,
        worldEpoch = 1L,
    )
}
