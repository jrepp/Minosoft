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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.debug

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.data.language.IntegratedLanguage
import de.bixilon.minosoft.data.language.LanguageUtil.i18n
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.debug.DebugRenderingControls
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.spacer.SpacerElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.GUIBuilder
import de.bixilon.minosoft.gui.rendering.gui.gui.LayoutedGUIElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu
import de.bixilon.minosoft.gui.rendering.gui.hud.elements.other.debug.DebugHUDElement
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes

class DebugRenderingMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 180.0f) {
    private val worldFramebuffer = guiRenderer.context.framebuffer.main
    private val debugHud = guiRenderer.hud[DebugHUDElement]
    private val wireframe: ButtonElement
    private val debugHudToggle: ButtonElement

    init {
        wireframe = ButtonElement(guiRenderer, toggleText("menu.debug.rendering.wireframe", isWireframe())) {
            worldFramebuffer.polygonMode = DebugRenderingControls.toggleWireframe(worldFramebuffer.polygonMode)
            refresh()
        }
        debugHudToggle = ButtonElement(guiRenderer, toggleText("menu.debug.rendering.hud", isDebugHudEnabled())) {
            debugHud?.enabled = !isDebugHudEnabled()
            refresh()
        }

        this += TextElement(guiRenderer, "menu.debug.rendering.title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += SpacerElement(guiRenderer, Vec2f(0, 10))
        this += wireframe
        this += debugHudToggle
        this += ButtonElement(guiRenderer, "menu.debug.rendering.reset".i18n()) { reset() }
        this += ButtonElement(guiRenderer, "menu.debug.rendering.back".i18n()) { guiRenderer.gui.pop() }
    }

    override fun onOpen() {
        refresh()
        super.onOpen()
    }

    private fun reset() {
        worldFramebuffer.polygonMode = PolygonModes.FILL
        debugHud?.enabled = false
        refresh()
    }

    private fun refresh() {
        wireframe.text = toggleText("menu.debug.rendering.wireframe", isWireframe())
        debugHudToggle.text = toggleText("menu.debug.rendering.hud", isDebugHudEnabled())
    }

    private fun isWireframe(): Boolean {
        return DebugRenderingControls.isWireframe(worldFramebuffer.polygonMode)
    }

    private fun isDebugHudEnabled(): Boolean {
        return debugHud?.enabled == true
    }

    private fun toggleText(key: String, enabled: Boolean): ChatComponent {
        val state = if (enabled) "menu.debug.rendering.on" else "menu.debug.rendering.off"
        val stateText = IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(state))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(key), stateText)
    }

    companion object : GUIBuilder<LayoutedGUIElement<DebugRenderingMenu>> {
        override fun build(guiRenderer: GUIRenderer): LayoutedGUIElement<DebugRenderingMenu> {
            return LayoutedGUIElement(DebugRenderingMenu(guiRenderer))
        }
    }
}
