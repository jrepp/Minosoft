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

package de.bixilon.minosoft.data.container.actions

import de.bixilon.minosoft.data.container.ContainerTestUtil.createContainer
import de.bixilon.minosoft.data.container.ContainerTestUtil.createFurnace
import de.bixilon.minosoft.data.container.ContainerUtil.slotsOf
import de.bixilon.minosoft.data.container.StackableTest2
import de.bixilon.minosoft.data.container.StackableTest3
import de.bixilon.minosoft.data.container.actions.types.DistributeContainerAction
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.protocol.network.session.play.PacketTestUtil.assertNoPacket
import de.bixilon.minosoft.protocol.network.session.play.PacketTestUtil.assertPacket
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.protocol.packets.c2s.play.container.ContainerClickC2SP
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["container"], dependsOnGroups = ["block", "item", "item_stack"])
class DistributeContainerActionTest {

    fun testRightDragProtocolAndPrediction() {
        val session = createSession()
        val container = createContainer(session)
        val original = ItemStack(StackableTest2, count = 4)
        container.floating = original

        container.execute(DistributeContainerAction.startRight())
        container.execute(DistributeContainerAction.addRight(0))
        container.execute(DistributeContainerAction.addRight(1))
        container.execute(DistributeContainerAction.endRight(listOf(0, 1)))

        assertEquals(container.items.slots, slotsOf(
            0 to ItemStack(StackableTest2),
            1 to ItemStack(StackableTest2),
        ))
        assertEquals(container.floating, ItemStack(StackableTest2, count = 2))

        session.assertPacket(ContainerClickC2SP(9, 0, -999, 5, 4, 0, slotsOf(), original))
        session.assertPacket(ContainerClickC2SP(9, 0, 0, 5, 5, 1, slotsOf(), original))
        session.assertPacket(ContainerClickC2SP(9, 0, 1, 5, 5, 2, slotsOf(), original))
        session.assertPacket(ContainerClickC2SP(
            9,
            0,
            -999,
            5,
            6,
            3,
            slotsOf(
                0 to ItemStack(StackableTest2),
                1 to ItemStack(StackableTest2),
            ),
            ItemStack(StackableTest2, count = 2),
        ))
        session.assertNoPacket()
    }

    fun testDuplicateAndExcessSlotsAreSafe() {
        val session = createSession()
        val container = createContainer(session)
        container.floating = ItemStack(StackableTest2, count = 2)

        container.execute(DistributeContainerAction.endRight(listOf(0, 0, 1, 2)))

        assertEquals(container.items.slots, slotsOf(
            0 to ItemStack(StackableTest2),
            1 to ItemStack(StackableTest2),
        ))
        assertEquals(container.floating, null)
    }

    fun testEligibilitySkipsIncompatibleAndFullStacks() {
        val session = createSession()
        val container = createContainer(session)
        container.floating = ItemStack(StackableTest2, count = 3)
        container.items[1] = ItemStack(StackableTest3)
        container.items[2] = ItemStack(StackableTest2, count = 64)

        assertTrue(DistributeContainerAction.canDistribute(container, 0))
        assertFalse(DistributeContainerAction.canDistribute(container, 1))
        assertFalse(DistributeContainerAction.canDistribute(container, 2))

        container.execute(DistributeContainerAction.endRight(listOf(0, 1, 2)))

        assertEquals(container.items.slots, slotsOf(
            0 to ItemStack(StackableTest2),
            1 to ItemStack(StackableTest3),
            2 to ItemStack(StackableTest2, count = 64),
        ))
        assertEquals(container.floating, ItemStack(StackableTest2, count = 2))
    }

    fun testEligibilityRejectsOutputSlots() {
        val session = createSession()
        val container = createFurnace(session)
        container.floating = ItemStack(StackableTest2, count = 2)

        assertFalse(DistributeContainerAction.canDistribute(container, 2))

        container.execute(DistributeContainerAction.endRight(listOf(2)))

        assertEquals(container.items.slots, slotsOf())
        assertEquals(container.floating, ItemStack(StackableTest2, count = 2))
    }
}
