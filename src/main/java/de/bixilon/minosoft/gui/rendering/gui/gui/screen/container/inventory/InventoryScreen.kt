/*
 * Minosoft
 * Copyright (C) 2020-2023 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.abilities.Gamemodes
import de.bixilon.minosoft.data.container.types.PlayerInventory
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.atlas.Atlas.Companion.get
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.BackgroundedContainerScreen
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes

open class InventoryScreen(
    guiRenderer: GUIRenderer,
    container: PlayerInventory,
) : BackgroundedContainerScreen<PlayerInventory>(
    guiRenderer,
    container,
    guiRenderer.atlas[ATLAS]["container"],
) {
    private val skinArea = atlasElement?.areas?.get("skin")
    private val skin = skinArea?.let { PlayerSkinElement(guiRenderer, it.size).apply { parent = this@InventoryScreen } }
    private val creativeExtensionTabs = extensions().mapNotNull { binding ->
        binding.extension.creativeTabLabel?.let { label ->
            CreativeInventoryExternalCatalogTab(label, binding.extension.element)
        }
    }
    private val creativeBodySize = CreativeInventoryLayout.bodySize(
        guiRenderer.scaledSize,
        containerBackground.size,
    )
    private val creativeCatalog = CreativeInventoryCatalogElement(
        guiRenderer,
        container,
        guiRenderer.session.registries.item
            .distinctBy { it.identifier }
            .filterNot { it.identifier.namespace == "minecraft" && it.identifier.path == "air" }
            .sortedBy { it.identifier.toString() },
        creativeExtensionTabs,
        creativeBodySize,
    ).apply { parent = this@InventoryScreen }

    private val creative: Boolean get() = guiRenderer.session.player.gamemode == Gamemodes.CREATIVE

    override fun isExtensionStandalone(extension: de.bixilon.minosoft.modding.loader.fabric.FabricContainerScreenExtension): Boolean {
        return !creative || extension.creativeTabLabel == null
    }

    override fun containerOffset(screenSize: Vec2f, backgroundSize: Vec2f): Vec2f {
        if (!creative) return super.containerOffset(screenSize, backgroundSize)
        return CreativeInventoryLayout.containerOffset(screenSize, backgroundSize, creativeCatalog.size)
    }

    override fun forceRenderContainerScreen(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        if (creative) {
            creativeCatalog.render(offset + CATALOG_OFFSET, consumer, options)
        }
        skinArea?.let { area -> skin?.render(offset + area.start, consumer, options) }
        super.forceRenderContainerScreen(offset, consumer, options)
    }

    override fun getContainerAt(position: Vec2f): Pair<Element, Vec2f>? {
        if (!creative) return null

        val inner = position - CATALOG_OFFSET
        if (inner.x < 0.0f || inner.y < 0.0f || inner.x >= creativeCatalog.size.x || inner.y >= creativeCatalog.size.y) {
            return null
        }
        return Pair(creativeCatalog, inner)
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (action == MouseActions.PRESS && getAt(position)?.first !== creativeCatalog) {
            creativeCatalog.clearSearchFocus()
        }
        return super.onMouseAction(position, button, action, count)
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        val searchFocused = creative && creativeCatalog.isSearchFocused
        if (shouldClose(key, type, searchFocused)) {
            guiRenderer.gui.popOrPause()
            return true
        }
        if (shouldRouteToCreativeCatalog(key, type, creative, searchFocused)) {
            return creativeCatalog.onKey(key, type)
        }
        return super.onKey(key, type)
    }

    override fun onCharPress(char: Int): Boolean {
        if (CreativeCatalogInput.capturesCharacters(creative)) return creativeCatalog.onCharPress(char)
        return super.onCharPress(char)
    }

    override fun onClose() {
        var failure: Throwable? = null
        val cleanup = listOf<() -> Unit>(
            creativeCatalog::onClose,
            { skin?.onClose() },
            { super.onClose() },
        )
        for (action in cleanup) {
            try {
                action()
            } catch (throwable: Throwable) {
                failure?.addSuppressed(throwable) ?: run { failure = throwable }
            }
        }
        failure?.let { throw it }
    }

    companion object {
        val ATLAS = minecraft("container/inventory")

        internal fun shouldClose(key: KeyCodes, type: KeyChangeTypes, searchFocused: Boolean): Boolean {
            return key == KeyCodes.KEY_E && type == KeyChangeTypes.PRESS && !searchFocused
        }

        internal fun shouldRouteToCreativeCatalog(
            key: KeyCodes,
            type: KeyChangeTypes,
            creative: Boolean,
            searchFocused: Boolean,
        ): Boolean {
            return creative && (searchFocused || key == KeyCodes.KEY_TAB && type == KeyChangeTypes.PRESS)
        }
    }

    private val CATALOG_OFFSET: Vec2f
        get() = Vec2f(
            -creativeCatalog.size.x - CreativeInventoryLayout.INVENTORY_GAP,
            -CreativeInventoryCatalogElement.TAB_HEIGHT,
        )
}
