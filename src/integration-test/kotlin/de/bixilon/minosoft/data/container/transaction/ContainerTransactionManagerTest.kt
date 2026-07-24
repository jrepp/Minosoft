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

package de.bixilon.minosoft.data.container.transaction

import de.bixilon.minosoft.data.container.ContainerTestUtil.createContainer
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["container"], dependsOnGroups = ["block", "item", "item_stack"])
class ContainerTransactionManagerTest {

    fun testEvictsOldestTransactionAfterCapacity() {
        val container = createContainer(createSession())
        var latestId = -1

        repeat(ContainerTransactionManager.MAX_TRANSACTIONS + 1) {
            latestId = ContainerTransaction(container).commit().id
        }

        assertEquals(latestId, ContainerTransactionManager.MAX_TRANSACTIONS)
        container.transactions.revert(latestId)
        container.transactions.revert(Int.MIN_VALUE)
    }
}
