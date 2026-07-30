/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.block

import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockMeshBuilderTest {

    @Test
    fun `face uv midpoint is retained after packed quantization`() {
        val midpoint = BlockMeshBuilder.faceUvMidpoint(
            PackedUVArray(
                floatArrayOf(
                    PackedUV(0.75f, 0.25f).raw,
                    PackedUV(0.25f, 0.75f).raw,
                    PackedUV(0.25f, 0.25f).raw,
                    PackedUV(0.75f, 0.75f).raw,
                ),
            ),
        )

        assertEquals(0.5f, midpoint.x, 1.0f / PackedUV.MASK)
        assertEquals(0.5f, midpoint.y, 1.0f / PackedUV.MASK)
    }
}
