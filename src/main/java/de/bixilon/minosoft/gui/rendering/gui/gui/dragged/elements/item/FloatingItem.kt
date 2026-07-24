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

package de.bixilon.minosoft.gui.rendering.gui.gui.dragged.elements.item

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.container.Container
import de.bixilon.minosoft.data.container.actions.types.DistributeContainerAction
import de.bixilon.minosoft.data.container.actions.types.DropFloatingContainerAction
import de.bixilon.minosoft.data.container.actions.types.SimpleContainerAction
import de.bixilon.minosoft.data.container.actions.types.SlotCounts
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.items.ItemElement
import de.bixilon.minosoft.gui.rendering.gui.elements.items.RawItemElement
import de.bixilon.minosoft.gui.rendering.gui.gui.dragged.Dragged
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer

class FloatingItem(
    guiRenderer: GUIRenderer,
    val stack: ItemStack,
    val container: Container? = null,
    size: Vec2f = RawItemElement.DEFAULT_SIZE,
) : Dragged(guiRenderer) {
    private val itemElement = RawItemElement(guiRenderer, size, stack, this)
    private val rightDrag = RightDragDistribution()

    init {
        forceSilentApply()
        _size = size
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        itemElement.render(offset, consumer, options)
    }

    override fun forceSilentApply() {
    }

    override fun onDragMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int, target: Element?) {
        if (button == MouseButtons.RIGHT && action == MouseActions.RELEASE && rightDrag.active) {
            finishRightDrag()
            return
        }
        if (action != MouseActions.PRESS || button != MouseButtons.LEFT && button != MouseButtons.RIGHT) {
            return
        }
        if (target == null) {
            container?.execute(DropFloatingContainerAction(if (button == MouseButtons.LEFT) SlotCounts.ALL else SlotCounts.PART))
            guiRenderer.dragged.element = null
            return
        }
        if (button == MouseButtons.RIGHT && target is ItemElement) {
            if (!startRightDrag(target)) {
                target.itemsElement.container.execute(SimpleContainerAction(target.slotId, SlotCounts.PART))
            }
        }
    }

    override fun onDragMove(position: Vec2f, target: Element?) {
        if (!rightDrag.active || target !is ItemElement) {
            return
        }
        val container = container ?: return
        if (target.itemsElement.container !== container) {
            return
        }
        if (!DistributeContainerAction.canDistribute(container, target.slotId) || !rightDrag.add(target.slotId)) {
            return
        }
        container.execute(DistributeContainerAction.addRight(target.slotId))
    }

    override fun onDragEnd(position: Vec2f, target: Element?) {
        rightDrag.cancel()
    }

    private fun startRightDrag(target: ItemElement): Boolean {
        val container = container ?: return false
        if (target.itemsElement.container !== container || !DistributeContainerAction.canDistribute(container, target.slotId)) {
            return false
        }

        rightDrag.start(target.slotId)
        container.execute(DistributeContainerAction.startRight())
        container.execute(DistributeContainerAction.addRight(target.slotId))
        return true
    }

    private fun finishRightDrag() {
        val slots = rightDrag.finish()
        container?.execute(DistributeContainerAction.endRight(slots))
    }
}
