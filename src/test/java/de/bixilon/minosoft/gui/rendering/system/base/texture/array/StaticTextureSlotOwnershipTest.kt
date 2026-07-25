/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.array

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticTextureSlotOwnershipTest {

    @Test
    fun `shared coordinate remains live until every generation retires`() {
        val ownership = StaticTextureSlotOwnership<String>()
        val first = ownership.retain(listOf("64:3"), listOf("64:3"))
        val second = ownership.retain(listOf("64:3"), listOf("64:3"))

        first.close()
        assertTrue(ownership.freeSlots().isEmpty())

        second.close()
        assertEquals(setOf("64:3"), ownership.freeSlots())
    }

    @Test
    fun `resolution move frees only the retired coordinate`() {
        val ownership = StaticTextureSlotOwnership<String>()
        val old = ownership.retain(listOf("16:2"), listOf("16:2"))
        val replacement = ownership.retain(listOf("64:7"), listOf("64:7"))

        old.close()
        assertEquals(setOf("16:2"), ownership.freeSlots())
        assertFalse("64:7" in ownership.freeSlots())

        ownership.forget(setOf("16:2"))
        assertEquals(1, ownership.diagnostics().managed)
        replacement.close()
        assertEquals(setOf("64:7"), ownership.freeSlots())
    }

    @Test
    fun `preexisting resource pack coordinate is permanent`() {
        val ownership = StaticTextureSlotOwnership<String>()
        val lease = ownership.retain(listOf("64:1"), emptyList())

        lease.close()
        assertTrue(ownership.freeSlots().isEmpty())
        assertEquals(1, ownership.diagnostics().permanent)
    }
}
