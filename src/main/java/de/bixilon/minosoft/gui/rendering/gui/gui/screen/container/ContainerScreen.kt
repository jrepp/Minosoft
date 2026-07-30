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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.container.Container
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.atlas.AtlasArea
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.items.ContainerItemsElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.Screen
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.util.vec.vec2.Vec2Util.isGreater
import de.bixilon.minosoft.gui.rendering.util.vec.vec2.Vec2Util.isSmaller
import de.bixilon.minosoft.modding.loader.fabric.FabricContainerScreenExtensionBinding
import de.bixilon.minosoft.modding.loader.fabric.FabricContainerScreenExtensionContext
import de.bixilon.minosoft.modding.loader.fabric.FabricContainerScreenExtensions
import it.unimi.dsi.fastutil.ints.Int2ObjectMap

abstract class ContainerScreen<C : Container>(
    guiRenderer: GUIRenderer,
    val container: C,
    items: Int2ObjectMap<AtlasArea>,
) : Screen(guiRenderer), AbstractLayout<Element> {
    protected open val containerElement = ContainerItemsElement(guiRenderer, container, items).apply { parent = this@ContainerScreen }
    override var activeElement: Element? = null
    override var activeDragElement: Element? = null
    protected open val customRenderer: Boolean = false
    private var fabricExtensions: List<FabricContainerScreenExtensionBinding>? = null

    protected fun extensions(): List<FabricContainerScreenExtensionBinding> {
        fabricExtensions?.let { return it }
        return FabricContainerScreenExtensions.build(
            FabricContainerScreenExtensionContext(guiRenderer, container, containerElement.size),
        ).onEach { it.extension.element.parent = this }.also { fabricExtensions = it }
    }

    protected open fun isExtensionStandalone(extension: de.bixilon.minosoft.modding.loader.fabric.FabricContainerScreenExtension): Boolean = true

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        super.forceRender(offset, consumer, options)
        if (customRenderer) {
            return
        }
        forceRenderContainerScreen(offset, consumer, options)
    }

    protected open fun forceRenderContainerScreen(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        containerElement.render(offset, consumer, options)
        for (binding in extensions()) {
            if (!binding.active || !isExtensionStandalone(binding.extension)) continue
            binding.extension.element.render(offset + binding.extension.offset, consumer, options)
        }
    }

    override fun forceSilentApply() {
        super.forceSilentApply()
        containerElement.apply()
    }

    override fun getAt(position: Vec2f): Pair<Element, Vec2f>? {
        for (binding in extensions().asReversed()) {
            if (!binding.active || !isExtensionStandalone(binding.extension)) continue
            val extension = binding.extension
            if (position isSmaller extension.offset) continue
            val inner = position - extension.offset
            if (inner isGreater extension.element.size) continue
            return Pair(extension.element, inner)
        }
        if (position isSmaller Vec2f.EMPTY || position isGreater containerElement.size) {
            return null
        }
        return Pair(containerElement, position)
    }

    override fun onOpen() {
        guiRenderer.session.player.items.opened = container
    }

    override fun onClose() {
        var failure: Throwable? = null
        for (extension in fabricExtensions.orEmpty().asReversed()) {
            try {
                extension.close()
            } catch (throwable: Throwable) {
                failure?.addSuppressed(throwable) ?: run { failure = throwable }
            }
        }
        fabricExtensions = null
        try {
            super.onClose()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        try {
            container.close()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        failure?.let { throw it }
    }
}
