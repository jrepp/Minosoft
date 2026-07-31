/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.model.page

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TerrainCoordinatesTest {
    @Test
    fun `negative block boundaries select the containing page`() {
        val extent = TerrainPageExtent(16L, 16L, 16L)

        assertEquals(
            TerrainAbsoluteBlockPosition(-16L, -16L, -32L),
            TerrainAbsoluteBlockPosition(-1L, -16L, -17L).containingPage(extent).blocks,
        )
        assertEquals(
            TerrainAbsoluteBlockPosition(0L, 0L, 0L),
            TerrainAbsoluteBlockPosition(15L, 15L, 15L).containingPage(extent).blocks,
        )
    }

    @Test
    fun `absolute and frame-relative arithmetic rejects overflow`() {
        assertThrows<ArithmeticException> {
            TerrainAbsoluteBlockPosition(Long.MAX_VALUE, 0L, 0L).offset(1L, 0L, 0L)
        }
        assertThrows<ArithmeticException> {
            TerrainFrameTransform(
                TerrainCameraPosition(0.0, 0.0, 0.0),
                TerrainRenderOrigin(TerrainAbsoluteBlockPosition(Long.MIN_VALUE, 0L, 0L)),
            ).page(TerrainPageOrigin(TerrainAbsoluteBlockPosition(Long.MAX_VALUE, 0L, 0L)))
        }
    }

    @Test
    fun `origin rebasing changes transforms without changing page-local geometry`() {
        val pageOrigin = TerrainPageOrigin(TerrainAbsoluteBlockPosition(96L, -64L, -48L))
        val localGeometry = TerrainPageRelativePosition(2.5, 4.0, 7.5)
        val bounds = TerrainPageRelativeBounds(
            TerrainPageRelativePosition(0.0, 0.0, 0.0),
            TerrainPageRelativePosition(16.0, 16.0, 16.0),
        )
        val first = frame(64L).page(pageOrigin)
        val rebased = frame(96L).page(pageOrigin)

        assertEquals(TerrainFramePosition(34.5f, -60.0f, -40.5f), first.position(localGeometry, bounds))
        assertEquals(TerrainFramePosition(2.5f, -60.0f, -40.5f), rebased.position(localGeometry, bounds))
        assertEquals(TerrainPageRelativePosition(2.5, 4.0, 7.5), localGeometry)
    }

    @Test
    fun `camera and page-local state must be finite and layout bounded`() {
        assertThrows<IllegalArgumentException> {
            TerrainCameraPosition(Double.NaN, 0.0, 0.0)
        }
        assertThrows<IllegalArgumentException> {
            TerrainCameraPosition(0.0, Double.POSITIVE_INFINITY, 0.0)
        }
        assertThrows<IllegalArgumentException> {
            TerrainPageRelativePosition(0.0, 0.0, Double.NEGATIVE_INFINITY)
        }

        val bounds = TerrainPageRelativeBounds(
            TerrainPageRelativePosition(0.0, 0.0, 0.0),
            TerrainPageRelativePosition(16.0, 16.0, 16.0),
        )
        assertThrows<IllegalArgumentException> {
            frame(0L).page(TerrainPageOrigin(TerrainAbsoluteBlockPosition(0L, 0L, 0L)))
                .position(TerrainPageRelativePosition(-0.01, 0.0, 0.0), bounds)
        }
    }

    @Test
    fun `initial convention declares forward OpenGL depth`() {
        assertEquals(
            TerrainClipSpaceDepthRange.NEGATIVE_ONE_TO_ONE,
            TerrainForwardOpenGlDepthConvention.clipSpaceDepthRange,
        )
        assertEquals(
            TerrainProjectionDepthDirection.FORWARD,
            TerrainForwardOpenGlDepthConvention.projectionDirection,
        )
        assertEquals(-1.0, TerrainForwardOpenGlDepthConvention.nearClipDepth)
        assertEquals(1.0, TerrainForwardOpenGlDepthConvention.farClipDepth)
        assertFalse(TerrainForwardOpenGlDepthConvention.supportsReversedDepth)
    }

    private fun frame(originX: Long) = TerrainFrameTransform(
        TerrainCameraPosition(100.5, 70.0, -40.25),
        TerrainRenderOrigin(TerrainAbsoluteBlockPosition(originX, 0L, 0L)),
    )
}
