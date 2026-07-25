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

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EtfPlayerSkinProcessorTest {
    @Test
    fun `signature-only skin remains identified for ETF transparency policy`() {
        val skin = signedSkin()

        val result = assertNotNull(EtfPlayerSkinProcessor.process(skin))
        assertNull(result.base)
        assertNull(result.blink)
        assertNull(result.emissive)
        assertEquals(false, result.forcedSolidLowerSkin)
    }

    @Test
    fun `ETF signature produces two blink frames and an emissive mask`() {
        val skin = RGBA8Buffer(Vec2i(64))
        signature.forEach { (x, y, color) -> skin.setRGBA(x, y, abgr(color)) }
        skin.setRGBA(52, 16, abgr(-256)) // two-frame blink
        skin.setRGBA(0, 0, RGBAColor(10, 20, 30))
        skin.setRGBA(24, 0, RGBAColor(40, 50, 60))
        skin.setRGBA(52, 17, abgr(-65281)) // copied thin coat
        skin.setRGBA(1, 17, abgr(-65281)) // marker region 1 is emissive
        skin.setRGBA(1, 18, abgr(-256)) // marker region 2 is enchanted
        val glow = RGBAColor(70, 80, 90)
        val sparkle = RGBAColor(110, 120, 130)
        skin.setRGBA(56, 16, glow)
        skin.setRGBA(56, 24, sparkle)
        skin.setRGBA(10, 10, glow)
        skin.setRGBA(11, 11, sparkle)
        skin.setRGBA(0, 36, glow)
        skin.setRGBA(12, 36, sparkle)
        assertEquals(Vec2i(64), skin.size)
        signature.forEach { (x, y, color) -> assertEquals(color, nativeAbgr(skin.getRGBA(x, y))) }
        assertEquals(-256, nativeAbgr(skin.getRGBA(52, 16)))
        assertEquals(true, EtfPlayerSkinProcessor.hasSignature(skin))
        assertEquals(2, EtfPlayerSkinProcessor.choice(skin, 52, 16))
        assertEquals(1, EtfPlayerSkinProcessor.choice(skin, 1, 17))
        val sourcePosition = skin.data.position()

        val result = assertNotNull(EtfPlayerSkinProcessor.process(skin))
        assertEquals(sourcePosition, skin.data.position())
        assertEquals(RGBAColor(10, 20, 30), result.blink!!.getRGBA(8, 8))
        assertEquals(RGBAColor(40, 50, 60), result.blink2!!.getRGBA(8, 8))
        assertEquals(glow, result.emissive!!.getRGBA(10, 10))
        assertEquals(0, result.emissive.getA(11, 10))
        assertNotNull(result.blinkEmissive)
        assertNotNull(result.blink2Emissive)
        assertEquals(glow, result.coat!!.getRGBA(16, 36))
        assertEquals(glow, result.coatEmissive!!.getRGBA(16, 36))
        assertEquals(sparkle, result.enchant!!.getRGBA(11, 11))
        assertEquals(sparkle, result.coatEnchant!!.getRGBA(36, 36))
    }

    @Test
    fun `ordinary skin has no ETF player feature allocation`() {
        assertNull(EtfPlayerSkinProcessor.process(RGBA8Buffer(Vec2i(64))))
    }

    @Test
    fun `moved fat coat and lower skin opacity follow ETF control pixels`() {
        val skin = signedSkin()
        skin.setRGBA(52, 17, abgr(-16711936)) // style 4: moved, fat, includes top
        skin.setRGBA(52, 18, abgr(-16776961)) // length 3
        skin.setRGBA(53, 18, abgr(-65281)) // force lower skin solid
        val top = RGBAColor(10, 20, 30)
        val side = RGBAColor(40, 50, 60)
        skin.setRGBA(4, 32, top)
        skin.setRGBA(0, 36, side)
        skin.setRGBA(8, 8, 70, 80, 90, 0)

        val result = assertNotNull(EtfPlayerSkinProcessor.process(skin))
        assertEquals(4, result.coatStyle)
        assertEquals(3, result.coatLength)
        assertTrue(result.fatCoat)
        assertTrue(result.forcedSolidLowerSkin)
        assertEquals(top, result.coat!!.getRGBA(20, 32))
        assertEquals(side, result.coat.getRGBA(16, 36))
        val base = assertNotNull(result.base)
        assertEquals(0, base.getA(4, 32))
        assertEquals(0, base.getA(0, 36))
        assertEquals(RGBAColor(70, 80, 90), base.getRGBA(8, 8))
    }

    @Test
    fun `lower coat styles omit the top and preserve copied source pixels`() {
        val skin = signedSkin()
        skin.setRGBA(52, 17, abgr(-65536)) // style 6: moved, thin, ignores top
        val top = RGBAColor(10, 20, 30)
        val side = RGBAColor(40, 50, 60)
        skin.setRGBA(4, 32, top)
        skin.setRGBA(0, 36, side)

        val result = assertNotNull(EtfPlayerSkinProcessor.process(skin))
        assertFalse(result.fatCoat)
        assertEquals(0, result.coat!!.getA(20, 32))
        assertEquals(side, result.coat.getRGBA(16, 36))
        assertEquals(0, assertNotNull(result.base).getA(4, 32))
    }

    @Test
    fun `textured nose is transposed mirrored aligned and emissive`() {
        val skin = signedSkin()
        skin.setRGBA(53, 17, abgr(-256)) // textured nose source 1
        skin.setRGBA(1, 17, abgr(-65281)) // marker region 1 is emissive
        skin.setRGBA(1, 18, abgr(-256)) // marker region 2 is enchanted
        val glow = RGBAColor(70, 80, 90)
        val sparkle = RGBAColor(110, 120, 130)
        skin.setRGBA(56, 16, glow)
        skin.setRGBA(56, 24, sparkle)
        skin.setRGBA(12, 32, glow)
        skin.setRGBA(13, 32, sparkle)

        val result = assertNotNull(EtfPlayerSkinProcessor.process(skin))
        assertEquals(EtfPlayerNoseType.TEXTURED, result.noseType)
        val nose = assertNotNull(result.nose)
        assertEquals(glow, nose.getRGBA(0, 32))
        assertEquals(glow, nose.getRGBA(63, 32))
        assertEquals(0, nose.getA(0, 0))
        val emissive = assertNotNull(result.noseEmissive)
        assertEquals(glow, emissive.getRGBA(0, 32))
        assertEquals(0, emissive.getA(8, 32))
        val enchant = assertNotNull(result.noseEnchant)
        assertEquals(sparkle, enchant.getRGBA(0, 40))
        assertEquals(0, enchant.getA(8, 40))
    }

    @Test
    fun `textured villager nose removal edits base and lazy blink sources`() {
        val skin = signedSkin()
        skin.setRGBA(52, 16, abgr(-256)) // two-stage lazy blink
        skin.setRGBA(53, 17, abgr(9)) // upstream raw control value 9
        val color = RGBAColor(10, 20, 30)
        skin.setRGBA(43, 13, color)
        skin.setRGBA(35, 5, color)
        skin.setRGBA(59, 5, color)

        val result = assertNotNull(EtfPlayerSkinProcessor.process(skin))
        assertEquals(EtfPlayerNoseType.VILLAGER_TEXTURED, result.noseType)
        val base = assertNotNull(result.base)
        assertEquals(0, base.getA(43, 13))
        assertEquals(0, base.getA(35, 5))
        assertEquals(0, base.getA(59, 5))
        assertEquals(0, result.blink!!.getA(35, 5))
        assertEquals(0, result.blink2!!.getA(59, 5))
    }

    private fun signedSkin() = RGBA8Buffer(Vec2i(64)).also { skin ->
        signature.forEach { (x, y, color) -> skin.setRGBA(x, y, abgr(color)) }
    }

    private fun abgr(value: Int): RGBAColor = RGBAColor(
        red = value and 0xFF,
        green = (value ushr 8) and 0xFF,
        blue = (value ushr 16) and 0xFF,
        alpha = (value ushr 24) and 0xFF,
    )

    private fun nativeAbgr(color: RGBAColor): Int =
        (color.alpha shl 24) or (color.blue shl 16) or (color.green shl 8) or color.red

    private val signature = listOf(
        Triple(1, 16, -16776961),
        Triple(0, 16, -16777089),
        Triple(0, 17, -16776961),
        Triple(2, 16, -16711936),
        Triple(3, 16, -16744704),
        Triple(3, 17, -16711936),
        Triple(0, 18, -65536),
        Triple(0, 19, -8454144),
        Triple(1, 19, -65536),
        Triple(3, 18, -1),
        Triple(2, 19, -1),
    )
}
