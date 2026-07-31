/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.model.scene

import de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageSnapshot
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.page.TerrainAbsoluteBlockPosition
import de.bixilon.minosoft.terrain.model.page.TerrainCameraPosition
import de.bixilon.minosoft.terrain.model.page.TerrainRenderOrigin
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TerrainSceneSnapshotTest {
    @Test
    fun `main and shadow pin the identical coverage and distant selection`() {
        val pages = mutableListOf(distant(2L), distant(1L))
        val scene = TerrainSceneSnapshot(
            frameGeneration = 4L,
            worldEpoch = 7L,
            camera = TerrainCameraPosition(1.0, 2.0, 3.0),
            renderOrigin = TerrainRenderOrigin(TerrainAbsoluteBlockPosition(0L, 0L, 0L)),
            nearCoverage = TerrainCoverageSnapshot(7L, 2L, 3L, emptyList()),
            selectedDistantPages = pages,
            seam = TerrainSeamParameters.CONSERVATIVE_OVERLAP,
            layoutGeneration = 5L,
            materialGeneration = 6L,
            views = listOf(
                TerrainSceneViewDescriptor("minosoft:shadow", true),
                TerrainSceneViewDescriptor("minosoft:main", false),
            ),
        )
        pages.clear()

        val main = scene.forView("minosoft:main")
        val shadow = scene.forView("minosoft:shadow")
        assertSame(scene, main.scene)
        assertSame(main.nearCoverage, shadow.nearCoverage)
        assertSame(main.selectedDistantPages, shadow.selectedDistantPages)
        assertEquals(listOf(1L, 2L), scene.selectedDistantPages.map { it.x })
        assertThrows<IllegalArgumentException> { scene.forView("minosoft:reflection") }
    }

    @Test
    fun `scene rejects cross epoch and near pages in distant selection`() {
        assertThrows<IllegalArgumentException> {
            TerrainSceneSnapshot(
                1L,
                7L,
                TerrainCameraPosition(0.0, 0.0, 0.0),
                TerrainRenderOrigin(TerrainAbsoluteBlockPosition(0L, 0L, 0L)),
                TerrainCoverageSnapshot(7L, 0L, 0L, emptyList()),
                listOf(TerrainPageKey(TerrainDomain.NEAR, 0, 0L, 0L, 0L, 7L)),
                TerrainSeamParameters.CONSERVATIVE_OVERLAP,
                0L,
                0L,
                listOf(TerrainSceneViewDescriptor("minosoft:main", false)),
            )
        }
    }

    private fun distant(x: Long) = TerrainPageKey(TerrainDomain.DISTANT, 0, x, 0L, 0L, 7L)
}
