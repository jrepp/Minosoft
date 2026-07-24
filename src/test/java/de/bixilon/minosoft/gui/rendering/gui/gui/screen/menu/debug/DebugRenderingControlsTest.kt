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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.menu.debug

import de.bixilon.minosoft.gui.rendering.debug.DebugRenderingControls
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugRenderingControlsTest {

    @Test
    fun `fill toggles to wireframe`() {
        assertEquals(PolygonModes.LINE, DebugRenderingControls.toggleWireframe(PolygonModes.FILL))
    }

    @Test
    fun `wireframe toggles back to fill`() {
        assertEquals(PolygonModes.FILL, DebugRenderingControls.toggleWireframe(PolygonModes.LINE))
    }

    @Test
    fun `unsupported point mode enters wireframe predictably`() {
        assertEquals(PolygonModes.LINE, DebugRenderingControls.toggleWireframe(PolygonModes.POINT))
    }

    @Test
    fun `only line mode is presented as wireframe`() {
        assertTrue(DebugRenderingControls.isWireframe(PolygonModes.LINE))
        assertFalse(DebugRenderingControls.isWireframe(PolygonModes.FILL))
        assertFalse(DebugRenderingControls.isWireframe(PolygonModes.POINT))
    }
}
