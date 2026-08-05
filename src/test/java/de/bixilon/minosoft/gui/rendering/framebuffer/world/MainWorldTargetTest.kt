/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.framebuffer.world

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.scaledFramebufferSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainWorldTargetTest {
    @Test
    fun `retina limit only reduces high density world buffers`() {
        assertEquals(1.0f, effectiveWorldScale(1.0f, 0.75f, 1.0f))
        assertEquals(0.75f, effectiveWorldScale(1.0f, 0.75f, 2.0f))
        assertEquals(0.5f, effectiveWorldScale(0.5f, 0.75f, 2.0f))
    }

    @Test
    fun `scaled world dimensions use one rounding contract`() {
        assertEquals(Vec2i(2592, 1433), scaledFramebufferSize(Vec2i(3456, 1910), 0.75f))
    }

    @Test
    fun `framebuffer dimensions reject invalid scales and overflow`() {
        assertFailsWith<IllegalArgumentException> { scaledFramebufferSize(Vec2i(1, 1), Float.NaN) }
        assertFailsWith<IllegalArgumentException> { scaledFramebufferSize(Vec2i(1, 1), -1.0f) }
        assertFailsWith<IllegalArgumentException> { scaledFramebufferSize(Vec2i(Int.MAX_VALUE, 1), 2.0f) }
        assertFailsWith<IllegalArgumentException> { scaledFramebufferSize(Vec2i(0, 1), 1.0f) }
    }

    @Test
    fun `world scale rejects invalid runtime density`() {
        assertFailsWith<IllegalArgumentException> { effectiveWorldScale(1.0f, 0.75f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { effectiveWorldScale(1.0f, 0.75f, 0.0f) }
    }
}
