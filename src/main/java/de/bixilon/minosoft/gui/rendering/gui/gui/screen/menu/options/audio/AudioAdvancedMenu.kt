/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.audio

import de.bixilon.minosoft.config.profile.profiles.audio.AudioProfile
import de.bixilon.minosoft.data.language.IntegratedLanguage
import de.bixilon.minosoft.data.language.LanguageUtil.i18n
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.GUIBuilder
import de.bixilon.minosoft.gui.rendering.gui.gui.LayoutedGUIElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu

class AudioAdvancedMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 250.0f) {
    private val audio: AudioProfile = guiRenderer.session.profiles.audio
    private val player = guiRenderer.context.rendering.audioPlayer
    private val engineStartup: ButtonElement
    private val packetSounds: ButtonElement
    private val guiSounds: ButtonElement
    private val buttonSounds: ButtonElement

    init {
        packetSounds = ButtonElement(guiRenderer, toggleText("menu.audio.packet_sounds", audio.types.packet)) {
            audio.types.packet = !audio.types.packet
            refresh()
        }
        guiSounds = ButtonElement(guiRenderer, toggleText("menu.audio.gui_sounds", audio.gui.enabled)) {
            audio.gui.enabled = !audio.gui.enabled
            refresh()
        }
        buttonSounds = ButtonElement(guiRenderer, toggleText("menu.audio.button_sounds", audio.gui.button)) {
            audio.gui.button = !audio.gui.button
            refresh()
        }
        engineStartup = ButtonElement(guiRenderer, restartToggleText("menu.audio.engine_startup", !audio.skipLoading)) {
            audio.skipLoading = !audio.skipLoading
            refresh()
        }

        this += TextElement(guiRenderer, "menu.audio.advanced_title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += packetSounds
        this += guiSounds
        this += buttonSounds
        this += engineStartup
        this += ButtonElement(guiRenderer, "menu.audio.stop_all".i18n()) { if (player.initialized) player.stopAll() }
        this += ButtonElement(guiRenderer, "menu.audio.reset".i18n()) { reset() }
        this += ButtonElement(guiRenderer, "menu.audio.back".i18n()) { guiRenderer.gui.pop() }
    }

    override fun onOpen() {
        refresh()
        super.onOpen()
    }

    private fun reset() {
        audio.enabled = DEFAULT_ENABLED
        audio.volume.master = DEFAULT_VOLUME
        audio.types.packet = DEFAULT_PACKET_SOUNDS
        audio.gui.enabled = DEFAULT_GUI_SOUNDS
        audio.gui.button = DEFAULT_BUTTON_SOUNDS
        audio.skipLoading = DEFAULT_SKIP_LOADING
        refresh()
    }

    private fun refresh() {
        engineStartup.text = restartToggleText("menu.audio.engine_startup", !audio.skipLoading)
        packetSounds.text = toggleText("menu.audio.packet_sounds", audio.types.packet)
        guiSounds.text = toggleText("menu.audio.gui_sounds", audio.gui.enabled)
        buttonSounds.text = toggleText("menu.audio.button_sounds", audio.gui.button)
    }

    private fun toggleText(key: String, enabled: Boolean): ChatComponent {
        val state = if (enabled) "menu.audio.on" else "menu.audio.off"
        val stateText = IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(state))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(key), stateText)
    }

    private fun restartToggleText(key: String, enabled: Boolean): ChatComponent {
        val toggle = toggleText(key, enabled)
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.audio.restart_value"), toggle)
    }

    companion object : GUIBuilder<LayoutedGUIElement<AudioAdvancedMenu>> {
        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_VOLUME = 1.0f
        private const val DEFAULT_PACKET_SOUNDS = true
        private const val DEFAULT_GUI_SOUNDS = true
        private const val DEFAULT_BUTTON_SOUNDS = true
        private const val DEFAULT_SKIP_LOADING = false

        override fun build(guiRenderer: GUIRenderer): LayoutedGUIElement<AudioAdvancedMenu> {
            return LayoutedGUIElement(AudioAdvancedMenu(guiRenderer))
        }
    }
}
