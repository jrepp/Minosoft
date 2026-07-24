/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.framebuffer.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldPostProcessorsTest {

    @Test
    fun `closing active processor restores previous owner`() {
        val processors = WorldPostProcessors<String>()
        val first = processors.install("first", "one")
        val second = processors.install("second", "two")

        assertEquals("two", processors.processor)
        assertEquals(listOf("first", "second"), processors.owners())

        second.close()
        assertEquals("one", processors.processor)
        assertEquals(listOf("first"), processors.owners())

        first.close()
        assertNull(processors.processor)
        assertEquals(emptyList(), processors.owners())
    }

    @Test
    fun `closing stale handle cannot remove replacement`() {
        val processors = WorldPostProcessors<String>()
        val stale = processors.install("iris", "old")
        val current = processors.install("iris", "new")

        stale.close()

        assertEquals("new", processors.processor)
        assertEquals(listOf("iris"), processors.owners())
        current.close()
        assertNull(processors.processor)
    }
}
