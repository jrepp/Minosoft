/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.gui.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuiMeshBuilderTest {
    @Test
    fun `index generation counts quads rather than vertices`() {
        assertEquals(0, GuiMeshBuilder.quadCount(0))
        assertEquals(1, GuiMeshBuilder.quadCount(4))
        assertEquals(64, GuiMeshBuilder.quadCount(256))
    }

    @Test
    fun `partial quads are rejected before index upload`() {
        assertFailsWith<IllegalArgumentException> { GuiMeshBuilder.quadCount(3) }
    }
}
