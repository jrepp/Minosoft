/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.loader

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PalettedTexturePermutationTest {

    @Test
    fun `maps palette colors and multiplies alpha`() {
        val source = RGBA8Buffer(Vec2i(2, 1)).apply {
            setRGBA(0, 0, 10, 20, 30, 128)
            setRGBA(1, 0, 99, 98, 97, 255)
        }
        val key = RGBA8Buffer(Vec2i(1, 1)).apply {
            setRGBA(0, 0, 10, 20, 30, 255)
        }
        val palette = RGBA8Buffer(Vec2i(1, 1)).apply {
            setRGBA(0, 0, 40, 50, 60, 128)
        }

        val result = PalettedTexturePermutation.apply(source, key, palette)

        assertEquals(RGBAColor(40, 50, 60, 64), result.getRGBA(0, 0))
        assertEquals(RGBAColor(0, 0, 0, 0), result.getRGBA(1, 0))
    }

    @Test
    fun `rejects mismatched key and palette sizes`() {
        assertFailsWith<IllegalArgumentException> {
            PalettedTexturePermutation.apply(
                RGBA8Buffer(Vec2i(1, 1)),
                RGBA8Buffer(Vec2i(2, 1)),
                RGBA8Buffer(Vec2i(1, 1)),
            )
        }
    }
}
