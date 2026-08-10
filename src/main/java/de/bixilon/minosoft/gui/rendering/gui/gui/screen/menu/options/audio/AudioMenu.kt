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

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.config.profile.profiles.audio.AudioProfile
import de.bixilon.minosoft.data.language.IntegratedLanguage
import de.bixilon.minosoft.data.language.LanguageUtil.i18n
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.slider.SteppedSliderElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.GUIBuilder
import de.bixilon.minosoft.gui.rendering.gui.gui.LayoutedGUIElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu

class AudioMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 220.0f) {
    private val audio: AudioProfile = guiRenderer.session.profiles.audio
    private val player = guiRenderer.context.rendering.audioPlayer
    private val engineStatus = TextElement(guiRenderer, engineStatusText(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER))
    private val volume = SteppedSliderElement(
        guiRenderer,
        minimumStep = 0,
        maximumStep = AudioControls.VOLUME_STEP_COUNT,
        initialStep = AudioControls.volumeStep(audio.volume.master),
        label = ::volumeText,
    ) { audio.volume.master = AudioControls.volumeForStep(it) }
    private val enabled: ButtonElement

    init {
        enabled = ButtonElement(guiRenderer, toggleText("menu.audio.enabled", audio.enabled)) {
            audio.enabled = !audio.enabled
            refreshToggles()
        }

        this += TextElement(guiRenderer, "menu.audio.title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += engineStatus
        this += enabled
        this += volume
        this += ButtonElement(guiRenderer, "menu.audio.advanced".i18n()) { guiRenderer.gui.push(AudioAdvancedMenu) }
        this += ButtonElement(guiRenderer, "menu.audio.test".i18n()) { testOutput() }
        this += ButtonElement(guiRenderer, "menu.audio.back".i18n()) { guiRenderer.gui.pop() }
    }

    override fun onOpen() {
        refresh()
        super.onOpen()
    }

    private fun testOutput() {
        if (!player.initialized) return
        player.play(TEST_SOUND, null as Vec3d?, 1.0f, 1.0f)
    }

    private fun refresh() {
        refreshEngineStatus()
        refreshVolume()
        refreshToggles()
    }

    private fun refreshEngineStatus() {
        engineStatus.text = engineStatusText()
    }

    private fun refreshVolume() {
        volume.setStep(AudioControls.volumeStep(audio.volume.master), notify = false)
    }

    private fun refreshToggles() {
        enabled.text = toggleText("menu.audio.enabled", audio.enabled)
    }

    private fun engineStatusText(): ChatComponent {
        val status = AudioControls.engineStatus(audio.skipLoading, player.initialized)
        val statusText = IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(status.translationKey))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.audio.engine_status"), statusText)
    }

    private fun volumeText(step: Int): ChatComponent {
        val percent = AudioControls.volumePercent(AudioControls.volumeForStep(step))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.audio.master_volume"), "$percent%")
    }

    private fun toggleText(key: String, enabled: Boolean): ChatComponent {
        val state = if (enabled) "menu.audio.on" else "menu.audio.off"
        val stateText = IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(state))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(key), stateText)
    }

    companion object : GUIBuilder<LayoutedGUIElement<AudioMenu>> {
        private val TEST_SOUND = minecraft("entity.experience_orb.pickup")

        override fun build(guiRenderer: GUIRenderer): LayoutedGUIElement<AudioMenu> {
            return LayoutedGUIElement(AudioMenu(guiRenderer))
        }
    }
}
