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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.settings

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.CycleConfigControl
import de.bixilon.minosoft.config.settings.SettingsSession
import de.bixilon.minosoft.config.settings.SteppedConfigControl
import de.bixilon.minosoft.config.settings.TextConfigControl
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.input.checkbox.SwitchElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.cycle.CycleSelectorElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.slider.SteppedSliderElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.elements.input.TextInputElement
import de.bixilon.minosoft.gui.rendering.gui.gui.popper.text.TextPopper
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseActions
import de.bixilon.minosoft.gui.rendering.gui.input.mouse.MouseButtons
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes

internal class ConfigEntryElement<T : Any>(
    guiRenderer: GUIRenderer,
    val entry: ConfigEntry<T>,
    private val session: SettingsSession,
    private val adapter: SettingControlAdapter<T>,
) : Element(guiRenderer) {
    private val title = TextElement(guiRenderer, titleText(enabled = true), background = null, parent = this)
    private val error = TextElement(guiRenderer, "", background = null, parent = this)
    private var controlY = 0.0f
    private var errorText: String? = null
    private var enabled = true
    private var descriptionPopper: TextPopper? = null

    override val canFocus: Boolean get() = enabled && adapter.element.canFocus

    init {
        adapter.element.parent = this
        forceSilentApply()
    }

    fun refresh(validationError: String?) {
        enabled = session.enabled(entry)
        errorText = validationError
        title.text = titleText(enabled)
        error.text = validationError?.let { "§c$it" } ?: ""
        adapter.disabled = !enabled
        adapter.sync(session[entry])
        forceApply()
    }

    override fun forceSilentApply() {
        title.silentApply()
        error.silentApply()
        adapter.element.silentApply()

        val width = prefMaxSize.x.takeIf { it > 0.0f } ?: maxSize.x
        when (val control = adapter.element) {
            is CycleSelectorElement<*> -> {
                val size = Vec2f(width, control.size.y)
                if (control.size != size) control.size = size
            }
            is SteppedSliderElement -> {
                val size = Vec2f(width, control.size.y)
                if (control.size != size) control.size = size
            }
            is TextInputElement -> {
                val size = Vec2f(width, TEXT_INPUT_HEIGHT)
                if (control.prefMaxSize != size) control.prefMaxSize = size
            }
        }
        controlY = title.size.y + TITLE_SPACING
        val controlHeight = maxOf(adapter.element.size.y, if (adapter.element is TextInputElement) TEXT_INPUT_HEIGHT else 0.0f)
        val errorHeight = if (errorText == null) 0.0f else ERROR_SPACING + error.size.y
        _prefSize = Vec2f(maxOf(title.prefSize.x, adapter.element.prefSize.x, error.prefSize.x), controlY + controlHeight + errorHeight)
        _size = Vec2f(width, controlY + controlHeight + errorHeight)
        cacheUpToDate = false
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        title.render(offset, consumer, options)
        adapter.element.render(offset + Vec2f(0.0f, controlY), consumer, options)
        if (errorText != null) {
            error.render(offset + Vec2f(0.0f, size.y - error.size.y), consumer, options)
        }
    }

    override fun onMouseEnter(position: Vec2f, absolute: Vec2f): Boolean {
        entry.description?.let { description ->
            descriptionPopper?.hide()
            descriptionPopper = TextPopper(guiRenderer, absolute, description).apply { show() }
        }
        if (enabled) adapter.element.onMouseEnter(controlPosition(position), absolute)
        return true
    }

    override fun onMouseMove(position: Vec2f, absolute: Vec2f): Boolean {
        if (enabled) adapter.element.onMouseMove(controlPosition(position), absolute)
        return true
    }

    override fun onMouseLeave(): Boolean {
        descriptionPopper?.hide()
        descriptionPopper = null
        adapter.element.onMouseLeave()
        return true
    }

    override fun onMouseAction(position: Vec2f, button: MouseButtons, action: MouseActions, count: Int): Boolean {
        if (!enabled || position.y < controlY) return true
        return adapter.element.onMouseAction(controlPosition(position), button, action, count)
    }

    override fun onScroll(position: Vec2f, scrollOffset: Vec2f): Boolean {
        if (!enabled || position.y < controlY) return false
        return adapter.element.onScroll(controlPosition(position), scrollOffset)
    }

    override fun onKey(key: KeyCodes, type: KeyChangeTypes): Boolean {
        if (!enabled) return true
        return adapter.element.onKey(key, type)
    }

    override fun onCharPress(char: Int): Boolean {
        if (!enabled) return true
        return adapter.element.onCharPress(char)
    }

    override fun tick() {
        adapter.element.tick()
    }

    override fun onOpen() = adapter.element.onOpen()
    override fun onHide() = adapter.element.onHide()
    override fun onClose() {
        descriptionPopper?.hide()
        descriptionPopper = null
        adapter.element.onClose()
    }

    override fun onChildChange(child: Element) {
        forceApply()
    }

    private fun controlPosition(position: Vec2f): Vec2f {
        return Vec2f(position.x, maxOf(0.0f, position.y - controlY))
    }

    private fun titleText(enabled: Boolean): String {
        val color = if (enabled) "§f" else "§7"
        val marker = if (entry.restartRequired) " §e[restart]" else ""
        return "$color${entry.label}$marker"
    }

    private companion object {
        const val TITLE_SPACING = 2.0f
        const val ERROR_SPACING = 2.0f
        const val TEXT_INPUT_HEIGHT = 14.0f
    }
}

internal sealed class SettingControlAdapter<T : Any>(
    protected val session: SettingsSession,
    protected val entry: ConfigEntry<T>,
    protected val changed: () -> Unit,
) {
    abstract val element: Element
    abstract var disabled: Boolean
    protected var syncing = false

    abstract fun sync(value: T)

    protected fun stage(value: T) {
        if (syncing) return
        session.set(entry, value)
        changed()
    }

    companion object {
        fun create(
            guiRenderer: GUIRenderer,
            session: SettingsSession,
            entry: ConfigEntry<*>,
            changed: () -> Unit,
        ): SettingControlAdapter<*> {
            return when (entry.control) {
                BooleanConfigControl -> {
                    @Suppress("UNCHECKED_CAST")
                    BooleanSettingControl(guiRenderer, session, entry as ConfigEntry<Boolean>, changed)
                }
                is TextConfigControl -> {
                    @Suppress("UNCHECKED_CAST")
                    TextSettingControl(guiRenderer, session, entry as ConfigEntry<String>, changed)
                }
                is SteppedConfigControl<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    SteppedSettingControl(guiRenderer, session, entry as ConfigEntry<Any>, changed)
                }
                is CycleConfigControl<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    CycleSettingControl(guiRenderer, session, entry as ConfigEntry<Any>, changed)
                }
            }
        }
    }
}

private class BooleanSettingControl(
    guiRenderer: GUIRenderer,
    session: SettingsSession,
    entry: ConfigEntry<Boolean>,
    changed: () -> Unit,
) : SettingControlAdapter<Boolean>(session, entry, changed) {
    override val element = SwitchElement(guiRenderer, "", session[entry], parent = null) { stage(it) }
    override var disabled: Boolean
        get() = element.disabled
        set(value) {
            element.disabled = value
        }

    override fun sync(value: Boolean) {
        syncing = true
        try {
            element.state = value
        } finally {
            syncing = false
        }
    }
}

private class TextSettingControl(
    guiRenderer: GUIRenderer,
    session: SettingsSession,
    entry: ConfigEntry<String>,
    changed: () -> Unit,
) : SettingControlAdapter<String>(session, entry, changed) {
    private val control = entry.control as TextConfigControl
    override val element = TextInputElement(
        guiRenderer,
        value = session[entry],
        maxLength = control.maxLength,
    )

    init {
        element.onChangeCallback = { stage(element.value) }
    }
    override var disabled: Boolean
        get() = !element.editable
        set(value) {
            element.editable = !value
        }

    override fun sync(value: String) {
        syncing = true
        try {
            element.value = value
        } finally {
            syncing = false
        }
    }
}

private class SteppedSettingControl<T : Any>(
    guiRenderer: GUIRenderer,
    session: SettingsSession,
    entry: ConfigEntry<T>,
    changed: () -> Unit,
) : SettingControlAdapter<T>(session, entry, changed) {
    private val control = entry.control as SteppedConfigControl<T>
    override val element = SteppedSliderElement(
        guiRenderer,
        minimumStep = 0,
        maximumStep = control.values.lastIndex,
        initialStep = control.values.indexOf(session[entry]),
        label = { control.label(control.values[it]) },
    ) { stage(control.values[it]) }
    override var disabled: Boolean
        get() = element.disabled
        set(value) {
            element.disabled = value
        }

    override fun sync(value: T) {
        element.setStep(control.values.indexOf(value), notify = false)
    }
}

private class CycleSettingControl<T : Any>(
    guiRenderer: GUIRenderer,
    session: SettingsSession,
    entry: ConfigEntry<T>,
    changed: () -> Unit,
) : SettingControlAdapter<T>(session, entry, changed) {
    private val control = entry.control as CycleConfigControl<T>
    override val element = CycleSelectorElement(
        guiRenderer,
        options = control.options,
        initialValue = session[entry].takeIf { it in control.options } ?: entry.defaultValue,
        label = control.label,
    ) { stage(it) }
    override var disabled: Boolean
        get() = element.disabled
        set(value) {
            element.disabled = value
        }

    override fun sync(value: T) {
        element.setValue(value.takeIf { it in control.options } ?: entry.defaultValue, notify = false)
    }
}

internal object ConfigEntryElements {
    fun create(
        guiRenderer: GUIRenderer,
        session: SettingsSession,
        entry: ConfigEntry<*>,
        changed: () -> Unit,
    ): ConfigEntryElement<*> {
        @Suppress("UNCHECKED_CAST")
        entry as ConfigEntry<Any>
        @Suppress("UNCHECKED_CAST")
        val adapter = SettingControlAdapter.create(guiRenderer, session, entry, changed) as SettingControlAdapter<Any>
        return ConfigEntryElement(guiRenderer, entry, session, adapter)
    }
}
