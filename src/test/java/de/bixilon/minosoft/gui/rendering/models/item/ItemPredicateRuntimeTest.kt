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

package de.bixilon.minosoft.gui.rendering.models.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemPredicateRuntimeTest {

    @Test
    fun `clock follows vanilla wrapped smoothing once per world tick`() {
        val runtime = ItemPredicateRuntime()

        val first = runtime.clock(worldAge = 1L, dayTime = 12_000, natural = true)
        val sameTick = runtime.clock(worldAge = 1L, dayTime = 18_000, natural = true)
        val next = runtime.clock(worldAge = 2L, dayTime = 12_000, natural = true)

        assertEquals(first, sameTick)
        assertTrue(first > 0.0f)
        assertTrue(next > first)
        assertTrue(next < 1.0f)
    }
}
