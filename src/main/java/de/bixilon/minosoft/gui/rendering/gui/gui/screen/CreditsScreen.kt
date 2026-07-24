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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.registries.misc.event.world.handler.win.WinGameEvent
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments.Companion.getOffset
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.protocol.packets.c2s.play.entity.player.ClientActionC2SP
import kotlin.math.min

class CreditsScreen(
    guiRenderer: GUIRenderer,
) : Screen(guiRenderer) {
    private data class RenderedLine(
        val source: CreditsLine,
        val element: TextElement?,
        var y: Float = 0.0f,
    )

    private val scroll = CreditsScrollState()
    private val lines = CreditsContent.load(guiRenderer.session.assets).map { line ->
        RenderedLine(
            source = line,
            element = if (line.style == CreditsLineStyle.SPACER) null else TextElement(
                guiRenderer,
                styledText(line),
                background = null,
                properties = properties(line.style),
                parent = this,
            ),
        )
    }
    private var contentHeight = 0.0f
    private var closeRequested = false

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        super.forceRender(offset, consumer, options)

        val viewport = size
        val contentWidth = min(CONTENT_WIDTH, viewport.x - HORIZONTAL_MARGIN * 2.0f).coerceAtLeast(1.0f)
        val left = offset.x + (viewport.x - contentWidth) / 2.0f
        val top = offset.y + viewport.y - scroll.pixels

        for (line in lines) {
            val element = line.element ?: continue
            val y = top + line.y
            if (y + element.size.y < offset.y || y > offset.y + viewport.y) {
                continue
            }
            val x = if (line.source.style == CreditsLineStyle.TEXT) {
                left
            } else {
                offset.x + HorizontalAlignments.CENTER.getOffset(viewport, element.size).x
            }
            element.render(Vec2f(x, y), consumer, options)
        }
    }

    override fun forceSilentApply() {
        super.forceSilentApply()
        val contentWidth = min(CONTENT_WIDTH, size.x - HORIZONTAL_MARGIN * 2.0f).coerceAtLeast(1.0f)
        var y = 0.0f
        for (line in lines) {
            line.y = y
            val element = line.element
            if (element == null) {
                y += SPACER_HEIGHT
                continue
            }
            element.prefMaxSize = Vec2f(contentWidth, -1.0f)
            y += element.size.y + spacingAfter(line.source.style)
        }
        contentHeight = y
        cacheUpToDate = false
    }

    override fun tick() {
        if (closeRequested) {
            return
        }
        scroll.advance(
            spaceDown = guiRenderer.isKeyDown(KeyCodes.KEY_SPACE),
            controlDown = guiRenderer.isKeyDown(KeyCodes.KEY_LEFT_CONTROL, KeyCodes.KEY_RIGHT_CONTROL),
        )
        cacheUpToDate = false
        if (scroll.complete(size.y, contentHeight)) {
            closeRequested = true
            guiRenderer.gui.pop()
        }
    }

    override fun onClose() {
        super.onClose()
        guiRenderer.session.connection.send(ClientActionC2SP(ClientActionC2SP.ClientActions.PERFORM_RESPAWN))
    }

    companion object {

        fun register(guiRenderer: GUIRenderer) {
            guiRenderer.session.events.listen<WinGameEvent> {
                if (!it.showCredits) {
                    return@listen
                }
                guiRenderer.gui.push(CreditsScreen(guiRenderer))
            }
        }

        private fun styledText(line: CreditsLine): String {
            return when (line.style) {
                CreditsLineStyle.SECTION -> "§e${line.text}"
                CreditsLineStyle.TITLE -> "§7${line.text}"
                CreditsLineStyle.NAME -> "§f${line.text}"
                else -> line.text
            }
        }

        private fun properties(style: CreditsLineStyle): TextRenderProperties {
            val scale = if (style == CreditsLineStyle.SECTION) 1.5f else 1.0f
            return TextRenderProperties(
                alignment = if (style == CreditsLineStyle.TEXT) HorizontalAlignments.LEFT else HorizontalAlignments.CENTER,
                scale = scale,
            )
        }

        private fun spacingAfter(style: CreditsLineStyle): Float {
            return when (style) {
                CreditsLineStyle.SECTION -> 6.0f
                CreditsLineStyle.TITLE -> 2.0f
                CreditsLineStyle.TEXT -> 4.0f
                else -> 1.0f
            }
        }

        private const val CONTENT_WIDTH = 274.0f
        private const val HORIZONTAL_MARGIN = 20.0f
        private const val SPACER_HEIGHT = 12.0f
    }
}
