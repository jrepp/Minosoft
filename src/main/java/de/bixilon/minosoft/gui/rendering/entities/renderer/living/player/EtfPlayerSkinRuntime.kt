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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.player

import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureBlinkState
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureBlinkTimeline
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterial
import de.bixilon.minosoft.assets.model.texture.entity.EtfPlayerNoseType
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureState

data class EtfPlayerSkinFrame(
    val base: DynamicTexture,
    val emissive: DynamicTexture?,
    val enchant: DynamicTexture?,
    val blinkState: EntityTextureBlinkState,
    val allowBaseTransparency: Boolean = false,
    val coat: DynamicTexture? = null,
    val coatEmissive: DynamicTexture? = null,
    val coatEnchant: DynamicTexture? = null,
    val coatInflation: Float = 0.0f,
    val nose: DynamicTexture? = null,
    val noseEmissive: DynamicTexture? = null,
    val noseEnchant: DynamicTexture? = null,
    val noseType: EtfPlayerNoseType = EtfPlayerNoseType.NONE,
)

data class EtfPlayerSkinTextures(
    val base: DynamicTexture,
    val blink: DynamicTexture? = null,
    val blink2: DynamicTexture? = null,
    val emissive: DynamicTexture? = null,
    val blinkEmissive: DynamicTexture? = null,
    val blink2Emissive: DynamicTexture? = null,
    val enchant: DynamicTexture? = null,
    val blinkEnchant: DynamicTexture? = null,
    val blink2Enchant: DynamicTexture? = null,
    val coat: DynamicTexture? = null,
    val coatEmissive: DynamicTexture? = null,
    val coatEnchant: DynamicTexture? = null,
    val fatCoat: Boolean = false,
    val allowBaseTransparency: Boolean = false,
    val nose: DynamicTexture? = null,
    val noseEmissive: DynamicTexture? = null,
    val noseEnchant: DynamicTexture? = null,
    val noseType: EtfPlayerNoseType = EtfPlayerNoseType.NONE,
    val blinkFrequencyTicks: Int = EntityTextureMaterial.DEFAULT_BLINK_FREQUENCY,
    val blinkLengthTicks: Int = EntityTextureMaterial.DEFAULT_BLINK_LENGTH,
) {
    init {
        require(blinkFrequencyTicks > 0) { "Blink frequency must be positive." }
        require(blinkLengthTicks in 0..EntityTextureMaterial.MAX_BLINK_LENGTH) {
            "Blink length must be between 0 and ${EntityTextureMaterial.MAX_BLINK_LENGTH} ticks."
        }
        require(blink2 == null || blink != null) { "A second blink frame requires a closed-eye frame." }
    }

    fun at(tick: Long, seed: Long): EtfPlayerSkinFrame {
        val state = if (blink == null) {
            EntityTextureBlinkState.OPEN
        } else {
            EntityTextureBlinkTimeline.stateAt(
                tick = tick,
                seed = seed,
                frequencyTicks = blinkFrequencyTicks,
                lengthTicks = blinkLengthTicks,
                hasHalfFrame = blink2 != null,
            )
        }
        return when (state) {
            EntityTextureBlinkState.OPEN -> frame(base, emissive.loaded(), enchant.loaded(), state)
            EntityTextureBlinkState.HALF -> frame(
                blink2.loaded() ?: blink.loaded() ?: base,
                blink2Emissive.loaded() ?: blinkEmissive.loaded() ?: emissive.loaded(),
                blink2Enchant.loaded() ?: blinkEnchant.loaded() ?: enchant.loaded(),
                state,
            )
            EntityTextureBlinkState.CLOSED -> frame(
                blink.loaded() ?: base,
                blinkEmissive.loaded() ?: emissive.loaded(),
                blinkEnchant.loaded() ?: enchant.loaded(),
                state,
            )
        }
    }

    private fun frame(
        base: DynamicTexture,
        emissive: DynamicTexture?,
        enchant: DynamicTexture?,
        state: EntityTextureBlinkState,
    ) = EtfPlayerSkinFrame(
        base = base,
        emissive = emissive,
        enchant = enchant,
        blinkState = state,
        allowBaseTransparency = allowBaseTransparency,
        coat = coat.loaded(),
        coatEmissive = coatEmissive.loaded(),
        coatEnchant = coatEnchant.loaded(),
        coatInflation = if (fatCoat) FAT_COAT_INFLATION_DELTA else 0.0f,
        nose = when (noseType) {
            EtfPlayerNoseType.VILLAGER_TEXTURED -> base
            EtfPlayerNoseType.VILLAGER, EtfPlayerNoseType.TEXTURED -> nose.loaded()
            EtfPlayerNoseType.NONE -> null
        },
        noseEmissive = when (noseType) {
            EtfPlayerNoseType.VILLAGER_TEXTURED -> emissive
            EtfPlayerNoseType.TEXTURED -> noseEmissive.loaded()
            EtfPlayerNoseType.VILLAGER, EtfPlayerNoseType.NONE -> null
        },
        noseEnchant = if (noseType == EtfPlayerNoseType.TEXTURED) noseEnchant.loaded() else null,
        noseType = noseType,
    )

    private fun DynamicTexture?.loaded(): DynamicTexture? =
        this?.takeIf { it.state == DynamicTextureState.LOADED }

    private companion object {
        const val FAT_COAT_INFLATION_DELTA = 0.5f
    }
}

internal enum class EtfPlayerDerivedTextureKind {
    BASE,
    BLINK,
    BLINK_2,
    EMISSIVE,
    BLINK_EMISSIVE,
    BLINK_2_EMISSIVE,
    ENCHANT,
    BLINK_ENCHANT,
    BLINK_2_ENCHANT,
    COAT,
    COAT_EMISSIVE,
    COAT_ENCHANT,
    NOSE,
    NOSE_EMISSIVE,
    NOSE_ENCHANT,
}

internal enum class EtfPlayerSharedTextureIdentifier {
    VILLAGER_NOSE,
}

/**
 * Dynamic-texture cache key. Keeping the source texture object in the key
 * prevents a downloaded skin replacement with the same profile URL from
 * reusing derived pixels from the retired source.
 */
internal data class EtfPlayerDerivedTextureIdentifier(
    val source: DynamicTexture,
    val kind: EtfPlayerDerivedTextureKind,
)
