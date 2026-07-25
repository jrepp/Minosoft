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
import de.bixilon.minosoft.assets.model.texture.entity.EtfPlayerNoseType
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EtfPlayerSkinRuntimeTest {
    @Test
    fun `runtime chooses matching base and emissive blink frames`() {
        val base = texture("base")
        val blink = texture("blink")
        val blink2 = texture("blink2")
        val emissive = texture("emissive")
        val blinkEmissive = texture("blink_emissive")
        val blink2Emissive = texture("blink2_emissive")
        val enchant = texture("enchant")
        val blinkEnchant = texture("blink_enchant")
        val blink2Enchant = texture("blink2_enchant")
        val runtime = EtfPlayerSkinTextures(
            base = base,
            blink = blink,
            blink2 = blink2,
            emissive = emissive,
            blinkEmissive = blinkEmissive,
            blink2Emissive = blink2Emissive,
            enchant = enchant,
            blinkEnchant = blinkEnchant,
            blink2Enchant = blink2Enchant,
            blinkFrequencyTicks = 1,
            blinkLengthTicks = 3,
        )

        val frames = (0L..100L).map { runtime.at(it, 42L) }.associateBy { it.blinkState }
        assertSame(base, frames.getValue(EntityTextureBlinkState.OPEN).base)
        assertSame(emissive, frames.getValue(EntityTextureBlinkState.OPEN).emissive)
        assertSame(enchant, frames.getValue(EntityTextureBlinkState.OPEN).enchant)
        assertSame(blink2, frames.getValue(EntityTextureBlinkState.HALF).base)
        assertSame(blink2Emissive, frames.getValue(EntityTextureBlinkState.HALF).emissive)
        assertSame(blink2Enchant, frames.getValue(EntityTextureBlinkState.HALF).enchant)
        assertSame(blink, frames.getValue(EntityTextureBlinkState.CLOSED).base)
        assertSame(blinkEmissive, frames.getValue(EntityTextureBlinkState.CLOSED).emissive)
        assertSame(blinkEnchant, frames.getValue(EntityTextureBlinkState.CLOSED).enchant)
    }

    @Test
    fun `runtime falls back while a derived blink texture is pending`() {
        val base = texture("base")
        val blink = texture("blink").apply { state = DynamicTextureState.LOADING }
        val runtime = EtfPlayerSkinTextures(
            base = base,
            blink = blink,
            blinkFrequencyTicks = 1,
            blinkLengthTicks = 3,
        )

        val closed = (0L..100L)
            .map { runtime.at(it, 42L) }
            .first { it.blinkState == EntityTextureBlinkState.CLOSED }
        assertSame(base, closed.base)
    }

    @Test
    fun `runtime exposes loaded fat coat with ETF inflation delta`() {
        val base = texture("base")
        val coat = texture("coat")
        val coatEmissive = texture("coat_emissive")
        val runtime = EtfPlayerSkinTextures(
            base = base,
            coat = coat,
            coatEmissive = coatEmissive,
            fatCoat = true,
        )

        val frame = runtime.at(0L, 0L)
        assertSame(coat, frame.coat)
        assertSame(coatEmissive, frame.coatEmissive)
        assertEquals(0.5f, frame.coatInflation)
    }

    @Test
    fun `runtime preserves configured ETF base transparency across blink frames`() {
        val runtime = EtfPlayerSkinTextures(
            base = texture("base"),
            blink = texture("blink"),
            allowBaseTransparency = true,
            blinkFrequencyTicks = 1,
            blinkLengthTicks = 3,
        )

        val frames = (0L..100L).map { runtime.at(it, 42L) }
        assertEquals(true, frames.all { it.allowBaseTransparency })
    }

    @Test
    fun `skin textured villager nose follows selected blink and emissive frame`() {
        val base = texture("base")
        val blink = texture("blink")
        val emissive = texture("emissive")
        val blinkEmissive = texture("blink_emissive")
        val runtime = EtfPlayerSkinTextures(
            base = base,
            blink = blink,
            emissive = emissive,
            blinkEmissive = blinkEmissive,
            noseType = EtfPlayerNoseType.VILLAGER_TEXTURED,
            blinkFrequencyTicks = 1,
            blinkLengthTicks = 3,
        )

        val closed = (0L..100L)
            .map { runtime.at(it, 42L) }
            .first { it.blinkState == EntityTextureBlinkState.CLOSED }
        assertSame(blink, closed.nose)
        assertSame(blinkEmissive, closed.noseEmissive)
        assertEquals(EtfPlayerNoseType.VILLAGER_TEXTURED, closed.noseType)
    }

    private fun texture(identifier: String) = TestDynamicTexture(identifier).apply {
        state = DynamicTextureState.LOADED
    }

    private class TestDynamicTexture(identifier: Any) : DynamicTexture(identifier) {
        override val shaderId: Int = identifier.hashCode()
    }
}
