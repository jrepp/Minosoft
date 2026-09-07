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

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.gui.rendering.models.util.CuboidUtil
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray
import kotlin.test.Test
import kotlin.test.assertEquals

class IrisTerrainQuadTest {
    @Test
    fun `baked cuboid faces expose outward normals and UV handedness`() {
        for (direction in Directions.entries) {
            val positions = CuboidUtil.positions(direction, Vec3f(2, 3, 4), Vec3f(5, 7, 9))
            for (mirror in listOf(false, true)) {
                val uv = UnpackedUVArray(floatArrayOf(
                    if (mirror) 1f else 0f, 0f,
                    if (mirror) 0f else 1f, 0f,
                    if (mirror) 0f else 1f, 1f,
                    if (mirror) 1f else 0f, 1f,
                )).pack()
                val quad = IrisTerrainQuad.calculate(positions, uv)
                val expected = direction.vectorf
                assertEquals(expected.x, quad.normal.x, 0.00001f, direction.toString())
                assertEquals(expected.y, quad.normal.y, 0.00001f, direction.toString())
                assertEquals(expected.z, quad.normal.z, 0.00001f, direction.toString())
                // Increasing V follows the last edge even with mirrored U.
                val bitangent = Vec3f(
                    quad.normal.y * quad.tangent.z - quad.normal.z * quad.tangent.y,
                    quad.normal.z * quad.tangent.x - quad.normal.x * quad.tangent.z,
                    quad.normal.x * quad.tangent.y - quad.normal.y * quad.tangent.x,
                ) * quad.tangent.w
                val edge = Vec3f(positions[9] - positions[0], positions[10] - positions[1], positions[11] - positions[2])
                val unit = edge / edge.length()
                assertEquals(unit.x, bitangent.x, 0.00001f)
                assertEquals(unit.y, bitangent.y, 0.00001f)
                assertEquals(unit.z, bitangent.z, 0.00001f)
            }
        }
    }

    @Test
    fun `sloped top retains outward normal independent of translation`() {
        for (offset in listOf(0f, 1000f)) {
            val positions = CuboidUtil.positions(Directions.UP, Vec3f(0f), Vec3f(1f))
            for (i in 0..3) {
                positions[i * 3 + 1] += positions[i * 3]
                for (j in 0..2) positions[i * 3 + j] += offset
            }
            val quad = IrisTerrainQuad.calculate(positions, UnpackedUVArray(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)).pack())
            assertEquals(-0.70710677f, quad.normal.x, 0.00001f)
            assertEquals(0.70710677f, quad.normal.y, 0.00001f)
            assertEquals(0f, quad.normal.z, 0.00001f)
        }
    }
}
