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
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.draw

import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKey
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EntityDrawOrderTest {
    @Test
    fun `render state ordering resolves hash collisions`() {
        val first = EntityRenderStateKey("FB", "vertex", "state", "material", "mesh")
        val second = EntityRenderStateKey("Ea", "vertex", "state", "material", "mesh")

        assertNotEquals(first, second)
        assertNotEquals(0, first.compareTo(second))
    }

    @Test
    fun `distance remains ahead of the stable tie breaker`() {
        val near = FakeDrawable(distance2 = 1.0, stableOrder = 20)
        val far = FakeDrawable(distance2 = 4.0, stableOrder = 10)

        assertTrue(compareFeatureDrawables(near, far, EntityLayer.EntitySortOrders.NEAREST_FIRST) < 0)
        assertTrue(compareFeatureDrawables(near, far, EntityLayer.EntitySortOrders.FURTHEST_FIRST) > 0)
    }

    @Test
    fun `equal distance drawables use stable entity order`() {
        val later = FakeDrawable(distance2 = 4.0, stableOrder = 20)
        val earlier = FakeDrawable(distance2 = 4.0, stableOrder = 10)

        assertTrue(compareFeatureDrawables(later, earlier, EntityLayer.EntitySortOrders.NEAREST_FIRST) > 0)
        assertTrue(compareFeatureDrawables(later, earlier, EntityLayer.EntitySortOrders.FURTHEST_FIRST) > 0)
        assertEquals(compareFeatureDrawables(earlier, earlier, EntityLayer.EntitySortOrders.NEAREST_FIRST), 0)
    }

    @Test
    fun `opaque queues group immutable state before distance`() {
        val stateA = EntityRenderStateKey("a", "v", "s", "m", "mesh")
        val stateB = EntityRenderStateKey("b", "v", "s", "m", "mesh")
        val farA = FakeDrawable(distance2 = 100.0, stableOrder = 1, renderStateKey = stateA)
        val nearB = FakeDrawable(distance2 = 1.0, stableOrder = 2, renderStateKey = stateB)

        assertTrue(compareFeatureDrawables(farA, nearB, EntityLayer.EntitySortOrders.NEAREST_FIRST) < 0)
    }

    @Test
    fun `translucent queues preserve distance before state grouping`() {
        val stateA = EntityRenderStateKey("a", "v", "s", "m", "mesh")
        val stateB = EntityRenderStateKey("b", "v", "s", "m", "mesh")
        val nearA = FakeDrawable(distance2 = 1.0, stableOrder = 1, renderStateKey = stateA)
        val farB = FakeDrawable(distance2 = 100.0, stableOrder = 2, renderStateKey = stateB)

        assertTrue(compareFeatureDrawables(farB, nearA, EntityLayer.EntitySortOrders.FURTHEST_FIRST) < 0)
    }

    private data class FakeDrawable(
        override val distance2: Double,
        override val stableOrder: Long,
        override val renderStateKey: EntityRenderStateKey = EntityRenderStateKey.TEST,
    ) : FeatureDrawable {
        override fun draw() = Unit
    }
}
