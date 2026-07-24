/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.container

import de.bixilon.minosoft.data.container.sections.RangeSection
import de.bixilon.minosoft.data.container.types.PlayerInventory
import de.bixilon.minosoft.data.registries.containers.ContainerType
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.kutil.observer.map.MapObserver.Companion.observeMap

abstract class InventorySynchronizedContainer(
    session: PlaySession,
    type: ContainerType,
    title: ChatComponent? = null,
    protected var synchronizedSlots: RangeSection,
    protected var inventorySlots: RangeSection = RangeSection(PlayerInventory.MAIN_SLOTS_START, PlayerInventory.MAIN_SLOTS),
    id: Int,
) : Container(session, type, title, id) {
    private val inventory = session.player.items.inventory

    init {
        check(synchronizedSlots.count == inventorySlots.count) { "Synchronized inventory slots must have the same size!" }
        items::slots.observeMap(this) { changes ->
            for ((slotId, _) in changes.removes) {
                synchronizedInventorySlot(slotId)?.let { inventory.items -= it }
            }
            for ((slotId, stack) in changes.adds) {
                synchronizedInventorySlot(slotId)?.let { inventory.items[it] = stack }
            }
        }
    }

    private fun synchronizedInventorySlot(slotId: Int): Int? {
        if (slotId !in synchronizedSlots) return null
        return slotId - synchronizedSlots.first + inventorySlots.first
    }
}
