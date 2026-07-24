/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.data.container.types

import de.bixilon.minosoft.data.container.TestItem1
import de.bixilon.minosoft.data.container.TestItem2
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

@Test(groups = ["container"])
class InventorySynchronizedContainerTest {
    private val first = ItemStack(TestItem1)
    private val second = ItemStack(TestItem2)

    fun `crafting hotbar changes survive container close`() {
        val session = createSession()
        val type = IT.REGISTRIES.containerType[CraftingContainer]!!
        val crafting = CraftingContainer(session, type, null, 7)
        session.player.items.containers[crafting.id] = crafting

        val craftingHotbarSlot = CraftingContainer.CRAFTING_SLOTS + 1 + PlayerInventory.PASSIVE_SLOTS
        crafting.items[craftingHotbarSlot] = first
        crafting.items[craftingHotbarSlot] = second
        crafting.close(force = true)

        assertEquals(session.player.items.inventory.items[PlayerInventory.HOTBAR_OFFSET], second)
    }

    fun `crafting inventory removals reconcile without touching crafting grid`() {
        val session = createSession()
        val type = IT.REGISTRIES.containerType[CraftingContainer]!!
        val crafting = CraftingContainer(session, type, null, 7)
        val firstInventorySlot = CraftingContainer.CRAFTING_SLOTS + 1

        crafting.items[firstInventorySlot] = first
        assertEquals(session.player.items.inventory.items[PlayerInventory.MAIN_SLOTS_START], first)

        crafting.items -= firstInventorySlot
        assertNull(session.player.items.inventory.items[PlayerInventory.MAIN_SLOTS_START])

        crafting.items[1] = second
        assertNull(session.player.items.inventory.items[1])
    }
}
