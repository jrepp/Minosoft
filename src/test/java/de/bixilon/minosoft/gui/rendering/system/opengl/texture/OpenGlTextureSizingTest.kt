/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.opengl.texture

import de.bixilon.kmath.vec.vec2.i.Vec2i
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenGlTextureSizingTest {

    @Test
    fun `dynamic arrays retain sufficient capacity`() {
        assertEquals(256, OpenGlTextureSizing.growPowerOfTwo(256, 64))
    }

    @Test
    fun `dynamic arrays grow to the next power of two`() {
        assertEquals(128, OpenGlTextureSizing.growPowerOfTwo(64, 65))
        assertEquals(256, OpenGlTextureSizing.growPowerOfTwo(64, 256))
    }

    @Test
    fun `font arrays preserve independent width and height maxima`() {
        assertEquals(
            Vec2i(1024, 3600),
            OpenGlTextureSizing.arraySize(
                1024,
                listOf(Vec2i(512, 2144), Vec2i(576, 3600), Vec2i(1024, 1024)),
            ),
        )
    }
}
