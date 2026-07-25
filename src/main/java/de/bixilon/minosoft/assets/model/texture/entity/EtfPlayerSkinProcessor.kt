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
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer

enum class EtfPlayerNoseType {
    NONE,
    VILLAGER,
    VILLAGER_TEXTURED,
    TEXTURED,
}

data class EtfPlayerSkinBuffers(
    val base: TextureBuffer? = null,
    val blink: TextureBuffer? = null,
    val blink2: TextureBuffer? = null,
    val emissive: TextureBuffer? = null,
    val blinkEmissive: TextureBuffer? = null,
    val blink2Emissive: TextureBuffer? = null,
    val enchant: TextureBuffer? = null,
    val blinkEnchant: TextureBuffer? = null,
    val blink2Enchant: TextureBuffer? = null,
    val coat: TextureBuffer? = null,
    val coatEmissive: TextureBuffer? = null,
    val coatEnchant: TextureBuffer? = null,
    val coatStyle: Int = 0,
    val coatLength: Int = 1,
    val fatCoat: Boolean = false,
    val forcedSolidLowerSkin: Boolean = false,
    val nose: TextureBuffer? = null,
    val noseEmissive: TextureBuffer? = null,
    val noseEnchant: TextureBuffer? = null,
    val noseType: EtfPlayerNoseType = EtfPlayerNoseType.NONE,
)

/**
 * Bounded, renderer-independent reader for ETF 7.0.13's player-skin control
 * pixels. The derived buffers cover blinking, emissive skin passes, coat
 * extraction, moved-coat source removal, forced lower-skin opacity, and
 * villager/textured nose material derivation, and enchant-mask extraction.
 */
object EtfPlayerSkinProcessor {
    fun process(source: TextureBuffer): EtfPlayerSkinBuffers? {
        if (source.size != Vec2i(64) || !hasSignature(source)) return null
        var modified: TextureBuffer? = null
        fun mutableBase(): TextureBuffer = modified ?: duplicate(source).also { modified = it }

        val blinkType = choice(source, 52, 16)
        val legacyNoseUpper = LEGACY_NOSE_UPPER.all { (x, y) -> choice(source, x, y) == VILLAGER_NOSE_MARKER }
        val legacyNoseLower = LEGACY_NOSE_LOWER.all { (x, y) -> choice(source, x, y) == VILLAGER_NOSE_MARKER }
        var noseType = if (legacyNoseUpper || legacyNoseLower) EtfPlayerNoseType.VILLAGER else EtfPlayerNoseType.NONE
        var removeNosePixels = legacyNoseUpper
        val noseChoice = choice(source, 53, 17)
        val nose = when (noseChoice) {
            1 -> {
                noseType = EtfPlayerNoseType.VILLAGER
                null
            }
            7 -> {
                noseType = EtfPlayerNoseType.VILLAGER_TEXTURED
                null
            }
            8 -> {
                noseType = EtfPlayerNoseType.VILLAGER
                removeNosePixels = true
                null
            }
            9 -> {
                noseType = EtfPlayerNoseType.VILLAGER_TEXTURED
                removeNosePixels = true
                null
            }
            in 2..6 -> texturedNose(source, NOSE_TEXTURE_SOURCES[noseChoice - 2]).also {
                if (noseType == EtfPlayerNoseType.NONE) noseType = EtfPlayerNoseType.TEXTURED
            }
            else -> null
        }
        if (removeNosePixels) {
            mutableBase().clear(LEGACY_NOSE_UPPER_BOUNDS)
            if (blinkType in 1..2) {
                mutableBase().clear(LAZY_BLINK_NOSE_1)
                if (blinkType == 2) mutableBase().clear(LAZY_BLINK_NOSE_2)
            }
        }

        val coatStyle = choice(source, 52, 17).takeIf { it in 1..8 } ?: 0
        val coatLength = choice(source, 52, 18).takeIf { it in 1..8 } ?: 1
        val coat = coatStyle.takeIf { it != 0 }?.let {
            coat(source, coatLength - 1, ignoreTopTexture = it >= 5)
        }
        if (coatStyle in MOVED_COAT_STYLES) {
            mutableBase().apply {
                clear(Bounds(4, 32, 7, 35))
                clear(Bounds(4, 48, 7, 51))
                clear(Bounds(0, 36, 15, 36 + coatLength - 1))
                clear(Bounds(0, 52, 15, 52 + coatLength - 1))
            }
        }

        val forcedSolidLowerSkin = choice(source, 53, 18) == 1
        if (forcedSolidLowerSkin) forceSolidLowerSkin(mutableBase())
        val base = modified ?: source

        val blinkHeight = choice(source, 52, 19).takeIf { it in 1..8 } ?: 1
        val (blink, blink2) = when (blinkType) {
            1 -> blink(base, FACE_1, 1, FACE_3) to null
            2 -> blink(base, FACE_1, 1, FACE_3) to blink(base, FACE_2, 1, FACE_4)
            3 -> blink(base, OPTIMIZED_1, blinkHeight) to null
            4 -> blink(base, OPTIMIZED_2, blinkHeight) to blink(base, OPTIMIZED_2_SECOND, blinkHeight)
            5 -> blink(base, OPTIMIZED_4, blinkHeight) to blink(base, OPTIMIZED_4_SECOND, blinkHeight)
            else -> null to null
        }

        val marker = MARKER_CHOICES.indexOfFirst { (x, y) -> choice(source, x, y) == EMISSIVE_MARKER }
        val markerBounds = MARKERS.getOrNull(marker)
        val emissive = markerBounds?.let { matchingPixels(base, base, it) }
        val blinkEmissive = markerBounds?.let { bounds -> blink?.let { matchingPixels(base, it, bounds) } }
        val blink2Emissive = markerBounds?.let { bounds -> blink2?.let { matchingPixels(base, it, bounds) } }
        val coatEmissive = markerBounds?.let { bounds -> coat?.let { matchingPixels(base, it, bounds) } }
        val noseEmissive = markerBounds?.let { bounds -> nose?.let { matchingPixels(base, it, bounds) } }
        val enchantMarker = MARKER_CHOICES.indexOfFirst { (x, y) -> choice(source, x, y) == ENCHANT_MARKER }
        val enchantBounds = MARKERS.getOrNull(enchantMarker)
        val enchant = enchantBounds?.let { matchingPixels(base, base, it) }
        val blinkEnchant = enchantBounds?.let { bounds -> blink?.let { matchingPixels(base, it, bounds) } }
        val blink2Enchant = enchantBounds?.let { bounds -> blink2?.let { matchingPixels(base, it, bounds) } }
        val coatEnchant = enchantBounds?.let { bounds -> coat?.let { matchingPixels(base, it, bounds) } }
        val noseEnchant = enchantBounds?.let { bounds -> nose?.let { matchingPixels(base, it, bounds) } }
        return EtfPlayerSkinBuffers(
            base = modified,
            blink = blink,
            blink2 = blink2,
            emissive = emissive,
            blinkEmissive = blinkEmissive,
            blink2Emissive = blink2Emissive,
            enchant = enchant,
            blinkEnchant = blinkEnchant,
            blink2Enchant = blink2Enchant,
            coat = coat,
            coatEmissive = coatEmissive,
            coatEnchant = coatEnchant,
            coatStyle = coatStyle,
            coatLength = coatLength,
            fatCoat = coatStyle in FAT_COAT_STYLES,
            forcedSolidLowerSkin = forcedSolidLowerSkin,
            nose = nose.takeIf { noseType == EtfPlayerNoseType.TEXTURED },
            noseEmissive = noseEmissive.takeIf { noseType == EtfPlayerNoseType.TEXTURED },
            noseEnchant = noseEnchant.takeIf { noseType == EtfPlayerNoseType.TEXTURED },
            noseType = noseType,
        )
    }

    internal fun hasSignature(source: TextureBuffer): Boolean = SIGNATURE.all { pixel ->
        nativeAbgr(source.getRGBA(pixel.x, pixel.y)) == pixel.color
    }

    internal fun choice(source: TextureBuffer, x: Int, y: Int): Int =
        CHOICES[nativeAbgr(source.getRGBA(x, y))] ?: nativeAbgr(source.getRGBA(x, y))

    private fun blink(
        source: TextureBuffer,
        eyes: Bounds,
        eyeHeight: Int,
        overlay: Bounds? = null,
    ): TextureBuffer {
        val target = duplicate(source)
        copy(source, target, eyes, 8, 8 + eyeHeight - 1)
        overlay?.let { copy(source, target, it, 40, 8 + eyeHeight - 1) }
        return target
    }

    private fun duplicate(source: TextureBuffer): TextureBuffer =
        source.create(source.size).also { it.put(source, Vec2i(0), Vec2i(0), source.size) }

    private fun coat(source: TextureBuffer, length: Int, ignoreTopTexture: Boolean): TextureBuffer {
        val target = RGBA8Buffer(source.size)
        if (!ignoreTopTexture) {
            copy(source, target, Bounds(4, 32, 7, 35 + length), 20, 32)
            copy(source, target, Bounds(4, 48, 7, 51 + length), 24, 32)
        }
        copy(source, target, Bounds(0, 36, 7, 36 + length), 16, 36)
        copy(source, target, Bounds(12, 36, 15, 36 + length), 36, 36)
        copy(source, target, Bounds(4, 52, 15, 52 + length), 24, 36)
        return target
    }

    private fun texturedNose(source: TextureBuffer, bounds: Bounds): TextureBuffer {
        val small = RGBA8Buffer(Vec2i(8))
        var targetY = 0
        for (x in bounds.x1..bounds.x2) {
            var targetX = 0
            for (y in bounds.y1..bounds.y2) {
                small.setRGBA(targetX++, targetY, source.getRGBA(x, y))
            }
            targetY++
        }
        for (x in 4 until 8) {
            for (y in 0 until 8) small.setRGBA(x, y, small.getRGBA(7 - x, y))
        }
        for (x in 0 until 8) {
            for (y in 0 until 4) {
                val lower = small.getRGBA(x, y + 4)
                small.setRGBA(x, y + 4, small.getRGBA(x, y))
                small.setRGBA(x, y, lower)
            }
        }
        // Player textures share one dynamic array whose current baseline is
        // 64×64. Nearest-neighbor expansion preserves the upstream 8×8 UV
        // domain without adding per-texture UV scale state to the player mesh.
        val target = RGBA8Buffer(source.size)
        for (y in 0 until target.size.y) {
            for (x in 0 until target.size.x) target.setRGBA(x, y, small.getRGBA(x / 8, y / 8))
        }
        return target
    }

    private fun copy(source: TextureBuffer, target: TextureBuffer, bounds: Bounds, targetX: Int, targetY: Int) {
        for (y in 0 until bounds.height) {
            for (x in 0 until bounds.width) {
                target.setRGBA(targetX + x, targetY + y, source.getRGBA(bounds.x1 + x, bounds.y1 + y))
            }
        }
    }

    private fun TextureBuffer.clear(bounds: Bounds) {
        for (y in bounds.y1..bounds.y2) {
            for (x in bounds.x1..bounds.x2) setRGBA(x, y, 0, 0, 0, 0)
        }
    }

    private fun forceSolidLowerSkin(skin: TextureBuffer) {
        for (bounds in SOLID_LOWER_SKIN) {
            for (y in bounds.y1..bounds.y2) {
                for (x in bounds.x1..bounds.x2) {
                    val color = skin.getRGBA(x, y)
                    skin.setRGBA(x, y, color.red, color.green, color.blue, 0xFF)
                }
            }
        }
    }

    private fun matchingPixels(markers: TextureBuffer, source: TextureBuffer, bounds: Bounds): TextureBuffer? {
        val colors = buildSet {
            for (y in bounds.y1..bounds.y2) {
                for (x in bounds.x1..bounds.x2) {
                    markers.getRGBA(x, y).takeIf { it.alpha != 0 }?.let { add(it.rgba) }
                }
            }
        }
        if (colors.isEmpty()) return null
        val target = RGBA8Buffer(source.size)
        var present = false
        for (y in 0 until source.size.y) {
            for (x in 0 until source.size.x) {
                val color = source.getRGBA(x, y)
                if (color.rgba !in colors) continue
                target.setRGBA(x, y, color)
                present = true
            }
        }
        return target.takeIf { present }
    }

    private fun nativeAbgr(color: RGBAColor): Int =
        (color.alpha shl 24) or (color.blue shl 16) or (color.green shl 8) or color.red

    private data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        val width get() = x2 - x1 + 1
        val height get() = y2 - y1 + 1
    }

    private data class SignaturePixel(val x: Int, val y: Int, val color: Int)

    private val SIGNATURE = listOf(
        SignaturePixel(1, 16, -16776961),
        SignaturePixel(0, 16, -16777089),
        SignaturePixel(0, 17, -16776961),
        SignaturePixel(2, 16, -16711936),
        SignaturePixel(3, 16, -16744704),
        SignaturePixel(3, 17, -16711936),
        SignaturePixel(0, 18, -65536),
        SignaturePixel(0, 19, -8454144),
        SignaturePixel(1, 19, -65536),
        SignaturePixel(3, 18, -1),
        SignaturePixel(2, 19, -1),
    )
    private val CHOICES = mapOf(
        -65281 to 1,
        -256 to 2,
        -16776961 to 3,
        -16711936 to 4,
        -16760705 to 5,
        -65536 to 6,
        -16744449 to 7,
        -14483457 to 8,
        -12362096 to 666,
    )
    private const val EMISSIVE_MARKER = 1
    private const val ENCHANT_MARKER = 2
    private const val VILLAGER_NOSE_MARKER = 666
    private val MARKER_CHOICES = listOf(1 to 17, 1 to 18, 2 to 17, 2 to 18)
    private val MARKERS = listOf(
        Bounds(56, 16, 63, 23),
        Bounds(56, 24, 63, 31),
        Bounds(56, 32, 63, 39),
        Bounds(56, 40, 63, 47),
    )
    private val FACE_1 = Bounds(0, 0, 7, 7)
    private val FACE_2 = Bounds(24, 0, 31, 7)
    private val FACE_3 = Bounds(32, 0, 39, 7)
    private val FACE_4 = Bounds(56, 0, 63, 7)
    private val OPTIMIZED_1 = Bounds(12, 16, 19, 16)
    private val OPTIMIZED_2 = Bounds(12, 16, 19, 17)
    private val OPTIMIZED_2_SECOND = Bounds(12, 18, 19, 19)
    private val OPTIMIZED_4 = Bounds(12, 16, 19, 19)
    private val OPTIMIZED_4_SECOND = Bounds(36, 16, 43, 19)
    private val MOVED_COAT_STYLES = setOf(2, 4, 6, 8)
    private val FAT_COAT_STYLES = setOf(3, 4, 7, 8)
    private val LEGACY_NOSE_UPPER = (43..44).flatMap { x -> (13..15).map { y -> x to y } }
    private val LEGACY_NOSE_LOWER = (11..12).flatMap { x -> (13..15).map { y -> x to y } }
    private val LEGACY_NOSE_UPPER_BOUNDS = Bounds(43, 13, 44, 15)
    private val LAZY_BLINK_NOSE_1 = Bounds(35, 5, 36, 7)
    private val LAZY_BLINK_NOSE_2 = Bounds(59, 5, 60, 7)
    private val NOSE_TEXTURE_SOURCES = listOf(
        Bounds(12, 32, 19, 35),
        Bounds(36, 32, 43, 35),
        Bounds(12, 48, 19, 51),
        Bounds(28, 48, 35, 51),
        Bounds(44, 48, 51, 51),
    )
    private val SOLID_LOWER_SKIN = listOf(
        Bounds(8, 0, 23, 15),
        Bounds(0, 20, 55, 31),
        Bounds(0, 8, 7, 15),
        Bounds(24, 8, 31, 15),
        Bounds(0, 16, 11, 19),
        Bounds(20, 16, 35, 19),
        Bounds(44, 16, 51, 19),
        Bounds(20, 48, 27, 51),
        Bounds(36, 48, 43, 51),
        Bounds(16, 52, 47, 63),
    )
}
