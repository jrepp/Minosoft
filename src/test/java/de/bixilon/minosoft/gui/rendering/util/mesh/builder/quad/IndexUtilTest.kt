/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad

import de.bixilon.kutil.collections.primitive.ints.HeapIntList
import kotlin.test.Test
import kotlin.test.assertContentEquals

class IndexUtilTest {
    @Test
    fun `brightness orientation can select the alternate diagonal`() {
        val normal = HeapIntList()
        val flipped = HeapIntList()

        IndexUtil.addTriangleQuad(normal, 10, front = true, reverse = false)
        IndexUtil.addTriangleQuad(flipped, 10, front = true, reverse = false, flipDiagonal = true)

        assertContentEquals(intArrayOf(10, 13, 12, 12, 11, 10), normal.toArray())
        assertContentEquals(intArrayOf(10, 13, 11, 13, 12, 11), flipped.toArray())
    }
}
