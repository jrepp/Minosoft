/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.border.world.mesh

import de.bixilon.kmath.vec.vec3.f.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldBorderMeshBuilderTest {
    @Test
    fun `border faces retain inward front-winding normals`() {
        assertEquals(Vec3f(0.0f, 0.0f, 1.0f), WorldBorderMeshBuilder.NORTH_NORMAL)
        assertEquals(Vec3f(0.0f, 0.0f, -1.0f), WorldBorderMeshBuilder.SOUTH_NORMAL)
        assertEquals(Vec3f(1.0f, 0.0f, 0.0f), WorldBorderMeshBuilder.WEST_NORMAL)
        assertEquals(Vec3f(-1.0f, 0.0f, 0.0f), WorldBorderMeshBuilder.EAST_NORMAL)
    }
}
