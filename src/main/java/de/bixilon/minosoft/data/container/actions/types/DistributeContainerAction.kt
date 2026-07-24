/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.data.container.actions.types

import de.bixilon.minosoft.data.container.Container
import de.bixilon.minosoft.data.container.actions.ContainerAction
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.container.transaction.ContainerTransaction
import de.bixilon.minosoft.data.registries.item.stack.StackableItem
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.packets.c2s.play.container.ContainerClickC2SP

class DistributeContainerAction private constructor(
    private val phase: Phase,
    private val slot: Int = OUTSIDE_SLOT,
    private val slots: List<Int> = emptyList(),
) : ContainerAction {

    override fun execute(session: PlaySession, container: Container, transaction: ContainerTransaction) {
        val nextFloating = if (phase == Phase.END_RIGHT) {
            distribute(container, transaction)
        } else {
            transaction.floating
        }
        val (id, changes) = transaction.commit()
        session.connection += ContainerClickC2SP(
            containerId = container.id,
            revision = container.serverRevision,
            slot = if (phase == Phase.ADD_RIGHT) slot else OUTSIDE_SLOT,
            mode = QUICK_CRAFT_MODE,
            button = phase.button,
            actionId = id,
            changes = changes,
            item = nextFloating,
        )
    }

    private fun distribute(container: Container, transaction: ContainerTransaction): ItemStack? {
        val floating = transaction.floating ?: return null
        var remaining = floating.count

        for (slot in slots.distinct()) {
            if (remaining <= 0) {
                break
            }
            if (container.getSlotType(slot) == null) {
                continue
            }
            val target = transaction[slot]
            if (!canDistribute(container, slot, floating, target)) {
                continue
            }

            transaction[slot] = target?.with(count = target.count + 1) ?: floating.copy(count = 1)
            remaining--
        }

        return floating.with(count = remaining).also { transaction.floating = it }
    }

    private enum class Phase(val button: Int) {
        START_RIGHT(4),
        ADD_RIGHT(5),
        END_RIGHT(6),
    }

    companion object {
        private const val QUICK_CRAFT_MODE = 5
        private const val OUTSIDE_SLOT = -999

        fun startRight(): DistributeContainerAction = DistributeContainerAction(Phase.START_RIGHT)

        fun addRight(slot: Int): DistributeContainerAction = DistributeContainerAction(Phase.ADD_RIGHT, slot)

        fun endRight(slots: Collection<Int>): DistributeContainerAction = DistributeContainerAction(Phase.END_RIGHT, slots = slots.toList())

        fun canDistribute(container: Container, slot: Int): Boolean {
            val floating = container.floating ?: return false
            if (container.getSlotType(slot) == null) return false
            return canDistribute(container, slot, floating, container.items[slot])
        }

        private fun canDistribute(container: Container, slot: Int, floating: ItemStack, target: ItemStack?): Boolean {
            if (container.getSlotType(slot)?.canPut(container, slot, floating) != true) {
                return false
            }
            if (target == null) {
                return true
            }
            if (!floating.matches(target)) {
                return false
            }

            val maximum = if (target.item is StackableItem) target.item.maxStackSize else 1
            return target.count < maximum
        }
    }
}
