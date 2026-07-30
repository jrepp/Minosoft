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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.options.lighting

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.profile.profiles.rendering.light.LightC
import de.bixilon.minosoft.config.profile.profiles.rendering.light.LightC.Companion.DEFAULT_GAMMA
import de.bixilon.minosoft.data.language.IntegratedLanguage
import de.bixilon.minosoft.data.language.LanguageUtil.i18n
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.gui.rendering.font.renderer.element.TextRenderProperties
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.HorizontalAlignments
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.slider.SteppedSliderElement
import de.bixilon.minosoft.gui.rendering.gui.elements.spacer.SpacerElement
import de.bixilon.minosoft.gui.rendering.gui.elements.text.TextElement
import de.bixilon.minosoft.gui.rendering.gui.gui.GUIBuilder
import de.bixilon.minosoft.gui.rendering.gui.gui.LayoutedGUIElement
import de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.Menu

class LightingMenu(guiRenderer: GUIRenderer) : Menu(guiRenderer, preferredElementWidth = 180.0f) {
    private val light: LightC = guiRenderer.session.profiles.rendering.light
    private val brightness = SteppedSliderElement(
        guiRenderer,
        minimumStep = 0,
        maximumStep = LightingControls.GAMMA_STEP_COUNT,
        initialStep = LightingControls.gammaStep(light.gamma),
        label = ::brightnessText,
    ) { light.gamma = LightingControls.gammaForStep(it) }
    private val playerLight = SteppedSliderElement(
        guiRenderer,
        minimumStep = 0,
        maximumStep = LightingControls.PLAYER_LIGHT_MAXIMUM_STEP,
        initialStep = LightingControls.playerLightStep(light.playerLightIntensity),
        label = ::playerLightText,
    ) { light.playerLightIntensity = LightingControls.playerLightForStep(it) }
    private lateinit var fullbright: ButtonElement
    private lateinit var ambientOcclusion: ButtonElement

    init {
        fullbright = ButtonElement(guiRenderer, toggleText("menu.lighting.fullbright", light.fullbright)) {
            light.fullbright = !light.fullbright
            refreshToggles()
        }
        ambientOcclusion = ButtonElement(guiRenderer, toggleText("menu.lighting.ambient_occlusion", light.ambientOcclusion)) {
            light.ambientOcclusion = !light.ambientOcclusion
            refreshToggles()
        }

        this += TextElement(guiRenderer, "menu.lighting.title".i18n(), background = null, properties = TextRenderProperties(HorizontalAlignments.CENTER, scale = 2.0f))
        this += SpacerElement(guiRenderer, Vec2f(0, 10))
        this += brightness
        this += playerLight
        this += fullbright
        this += ambientOcclusion
        this += ButtonElement(guiRenderer, "menu.lighting.reset".i18n()) { reset() }
        this += ButtonElement(guiRenderer, "menu.lighting.back".i18n()) { guiRenderer.gui.pop() }
    }

    override fun onOpen() {
        refresh()
        super.onOpen()
    }

    private fun reset() {
        light.gamma = DEFAULT_GAMMA
        light.playerLightIntensity = DEFAULT_PLAYER_LIGHT_INTENSITY
        light.fullbright = DEFAULT_FULLBRIGHT
        light.ambientOcclusion = DEFAULT_AMBIENT_OCCLUSION
        refresh()
    }

    private fun refresh() {
        brightness.setStep(LightingControls.gammaStep(light.gamma), notify = false)
        playerLight.setStep(LightingControls.playerLightStep(light.playerLightIntensity), notify = false)
        refreshToggles()
    }

    private fun refreshToggles() {
        fullbright.text = toggleText("menu.lighting.fullbright", light.fullbright)
        ambientOcclusion.text = toggleText("menu.lighting.ambient_occlusion", light.ambientOcclusion)
    }

    private fun brightnessText(step: Int): ChatComponent {
        val percent = LightingControls.gammaPercent(LightingControls.gammaForStep(step))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.lighting.brightness"), "$percent%")
    }

    private fun playerLightText(step: Int): ChatComponent {
        val intensity = LightingControls.playerLightPercent(LightingControls.playerLightForStep(step))
        val value = if (intensity == 0) {
            IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.lighting.off"))
        } else {
            ChatComponent.of("$intensity%")
        }
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft("menu.lighting.player_light"), value)
    }

    private fun toggleText(key: String, enabled: Boolean): ChatComponent {
        val state = if (enabled) "menu.lighting.on" else "menu.lighting.off"
        val stateText = IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(state))
        return IntegratedLanguage.LANGUAGE.forceTranslate(minosoft(key), stateText)
    }

    companion object : GUIBuilder<LayoutedGUIElement<LightingMenu>> {
        private const val DEFAULT_PLAYER_LIGHT_INTENSITY = 0.15f
        private const val DEFAULT_FULLBRIGHT = false
        private const val DEFAULT_AMBIENT_OCCLUSION = true

        override fun build(guiRenderer: GUIRenderer): LayoutedGUIElement<LightingMenu> {
            return LayoutedGUIElement(LightingMenu(guiRenderer))
        }
    }
}
