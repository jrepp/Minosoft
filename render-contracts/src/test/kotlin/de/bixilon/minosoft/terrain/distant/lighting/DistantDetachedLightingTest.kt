/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the license, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.lighting

import kotlin.test.Test
import kotlin.test.assertEquals

class DistantDetachedLightingTest {
    @Test
    fun `block light crosses page boundaries and stops at directional blockers`() {
        val source = coordinate(0, 1, 1)
        val blocker = coordinate(2, 1, 1)
        val volume = DistantDetachedLighting.calculate(6, 0, 3, 3, false) { x, y, z ->
            when (coordinate(x, y, z)) {
                source -> DistantLightVoxel(emission = 15)
                blocker -> DistantLightVoxel(propagationMask = 0, skylightEnters = false)
                else -> DistantLightVoxel()
            }
        }

        assertEquals(15, volume[0, 1, 1].block)
        assertEquals(14, volume[1, 1, 1].block)
        assertEquals(0, volume[2, 1, 1].block)
        assertEquals(8, volume[5, 1, 1].block, "light reaches the far side around the blocker")
    }

    @Test
    fun `direct sky uses height boundaries and filtered sky attenuates below`() {
        val filtered = coordinate(1, 2, 1)
        val opaque = coordinate(2, 2, 1)
        val volume = DistantDetachedLighting.calculate(4, 0, 5, 3, true) { x, y, z ->
            when (coordinate(x, y, z)) {
                filtered -> DistantLightVoxel(filtersSkylight = true)
                opaque -> DistantLightVoxel(propagationMask = 0, skylightEnters = false)
                else -> DistantLightVoxel()
            }
        }

        assertEquals(15, volume[1, 2, 1].sky)
        assertEquals(14, volume[1, 1, 1].sky)
        assertEquals(15, volume[2, 3, 1].sky)
        assertEquals(0, volume[2, 2, 1].sky)
        assertEquals(14, volume[2, 1, 1].sky, "indirect sky reaches below the opaque voxel")
    }

    @Test
    fun `sixteen voxel halo excludes every outside block light source`() {
        val volume = DistantDetachedLighting.calculate(32, 0, 1, 1, false) { x, _, _ ->
            if (x == 0) DistantLightVoxel(emission = 15) else DistantLightVoxel()
        }

        assertEquals(1, volume[14, 0, 0].block)
        assertEquals(0, volume[15, 0, 0].block)
        assertEquals(0, volume[16, 0, 0].block)
    }

    @Test
    fun `dimensions without skylight remain dark`() {
        val volume = DistantDetachedLighting.calculate(2, -4, 4, 2, false) { _, _, _ ->
            DistantLightVoxel()
        }

        assertEquals(0, volume[0, 3, 0].sky)
    }

    private fun coordinate(x: Int, y: Int, z: Int): Triple<Int, Int, Int> = Triple(x, y, z)
}
