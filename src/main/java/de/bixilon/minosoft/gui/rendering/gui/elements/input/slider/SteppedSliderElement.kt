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

package de.bixilon.minosoft.gui.rendering.gui.elements.input.slider

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.atlas.Atlas.Companion.get
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments.Companion.getOffset
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.AtlasImageElement
import de.bixilon.minosoft.gui.rendering.gui.elements.primitive.ColorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.dragged.Dragged
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.CursorShapes
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import kotlin.math.max

class SteppedSliderElement(
    guiRenderer: GUIRenderer,
    val minimumStep: Int,
    val maximumStep: Int,
    initialStep: Int,
    private val label: (step: Int) -> Any,
    private val onChange: (step: Int) -> Unit,
) : Element(guiRenderer) {
    private val atlas = guiRenderer.atlas[minecraft("elements/button")]
    private val normalAtlas = atlas["normal"]
    private val hoveredAtlas = atlas["hovered"]
    private val textElement: TextElement
    private val drag = SliderDrag()
    private val background = AtlasImageElement(guiRenderer, normalAtlas ?: guiRenderer.context.textures.whiteTexture)
    private val track = ColorElement(guiRenderer, Vec2f.EMPTY, TRACK_COLOR)
    private val fill = ColorElement(guiRenderer, Vec2f.EMPTY, FILL_COLOR)
    private val handle = ColorElement(guiRenderer, Vec2f(HANDLE_WIDTH, HANDLE_HEIGHT), HANDLE_COLOR)

    private var hovered = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            forceApply()
        }

    var step: Int = SteppedSliderSteps.clamp(initialStep, minimumStep, maximumStep)
        private set

    override val canFocus: Boolean
        get() = true

    init {
        textElement = TextElement(guiRenderer, label(step), background = null, parent = this)
        size = Vec2f(textElement.size.x + TEXT_PADDING * 2.0f, HEIGHT)
    }

    fun setStep(step: Int, notify: Boolean = true) {
        val clamped = SteppedSliderSteps.clamp(step, minimumStep, maximumStep)
        if (this.step == clamped) {
            return
        }

        this.step = clamped
        textElement.text = label(clamped)
        if (notify) {
            onChange(clamped)
        }
        forceApply()
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        val size = size
        val backgroundTexture = (if (hovered) hoveredAtlas else normalAtlas) ?: guiRenderer.context.textures.whiteTexture
        background.texturePart = backgroundTexture
        background.size = size
        background.render(offset, consumer, options)

        val trackWidth = max(0.0f, size.x - TRACK_MARGIN * 2.0f)
        val trackOffset = offset + Vec2f(TRACK_MARGIN, size.y - TRACK_BOTTOM - TRACK_HEIGHT)
        track.size = Vec2f(trackWidth, TRACK_HEIGHT)
        track.render(trackOffset, consumer, options)

        val handleOffset = SteppedSliderSteps.handleOffset(step, size.x, minimumStep, maximumStep, HANDLE_WIDTH)
        val handleCenter = handleOffset + HANDLE_WIDTH / 2.0f
        val fillWidth = (handleCenter - TRACK_MARGIN).coerceIn(0.0f, trackWidth)
        if (fillWidth > 0.0f) {
            fill.size = Vec2f(fillWidth, TRACK_HEIGHT)
            fill.render(trackOffset, consumer, options)
        }
        handle.render(
            offset + Vec2f(handleOffset, size.y - TRACK_BOTTOM - HANDLE_HEIGHT + 1.0f),
            consumer,
            options,
        )

        val textSize = textElement.size
        val labelAreaHeight = size.y - TRACK_AREA_HEIGHT
        textElement.render(
            offset + Vec2f(
                HorizontalAlignments.CENTER.getOffset(size.x, textSize.x),
                max(0.0f, (labelAreaHeight - textSize.y) / 2.0f),
            ),
            consumer,
            options,
        )
    }

    override fun forceSilentApply() {
        textElement.silentApply()
        cacheUpToDate = false
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (button != MouseButtons.LEFT || action != MouseActions.PRESS) {
            return true
        }

        setFromPosition(position.x)
        drag.originX = guiRenderer.currentMousePosition.x - position.x
        drag.show()
        return true
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (!hovered || type == KeyChangeTypes.RELEASE) {
            return true
        }

        when (key) {
            KeyCodes.KEY_LEFT -> setStep(step - 1)
            KeyCodes.KEY_RIGHT -> setStep(step + 1)
            KeyCodes.KEY_HOME -> setStep(minimumStep)
            KeyCodes.KEY_END -> setStep(maximumStep)
            else -> return true
        }
        return true
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        when {
            scrollOffset.y > 0.0f -> setStep(step + 1)
            scrollOffset.y < 0.0f -> setStep(step - 1)
        }
        return true
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        hovered = true
        context.window.cursorShape = CursorShapes.HAND
        return true
    }

    override fun onMouseLeave(): Boolean {
        hovered = false
        context.window.resetCursor()
        return true
    }

    override fun onChildChange(child: Element) {
        cacheUpToDate = false
        parent?.onChildChange(this)
    }

    private fun setFromPosition(position: Float) {
        setStep(SteppedSliderSteps.fromPosition(position, size.x, minimumStep, maximumStep, HANDLE_WIDTH))
    }

    private inner class SliderDrag : Dragged(guiRenderer) {
        var originX = 0.0f

        init {
            size = Vec2f.EMPTY
        }

        override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) = Unit

        override fun forceSilentApply() {
            cacheUpToDate = false
        }

        override fun onDragMove(position: Vec2f, target: Element?) {
            setFromPosition(position.x - originX)
        }

        override fun onDragMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int, target: Element?) {
            if (button != MouseButtons.LEFT || action != MouseActions.RELEASE) {
                return
            }
            setFromPosition(position.x - originX)
            close()
        }
    }

    private companion object {
        const val HEIGHT = 24.0f
        const val TEXT_PADDING = 4.0f
        const val TRACK_MARGIN = 5.0f
        const val TRACK_HEIGHT = 2.0f
        const val TRACK_BOTTOM = 3.0f
        const val TRACK_AREA_HEIGHT = 7.0f
        const val HANDLE_WIDTH = 6.0f
        const val HANDLE_HEIGHT = 6.0f

        val TRACK_COLOR = RGBAColor(25, 25, 25, 230)
        val FILL_COLOR = RGBAColor(180, 180, 180, 255)
        val HANDLE_COLOR = RGBAColor(255, 255, 255, 255)
    }
}
