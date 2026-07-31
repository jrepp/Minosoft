/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.terrain.runtime

import de.bixilon.minosoft.gui.rendering.terrain.scene.TerrainRuntimeMode
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainRuntimeSelectionTest {
    @Test
    fun `consolidated runtime is the production default`() {
        val selection = TerrainRuntimeSelection.parse(null)

        assertEquals(TerrainRuntimeMode.UNIFIED, selection.mode)
        assertTrue(selection.semanticArtifacts)
        assertTrue(selection.regionStorage)
        assertTrue(selection.distantHierarchy)
    }

    @Test
    fun `comparison mode captures artifacts without replacing production storage`() {
        val selection = TerrainRuntimeSelection.parse("unified-compare")

        assertEquals(TerrainRuntimeMode.UNIFIED_COMPARE, selection.mode)
        assertTrue(selection.semanticArtifacts)
        assertFalse(selection.regionStorage)
        assertFalse(selection.distantHierarchy)
    }

    @Test
    fun `legacy mode is one coherent rollback boundary`() {
        val selection = TerrainRuntimeSelection.parse("legacy")

        assertEquals(TerrainRuntimeMode.LEGACY, selection.mode)
        assertFalse(selection.semanticArtifacts)
        assertFalse(selection.regionStorage)
        assertFalse(selection.distantHierarchy)
    }

    @Test
    fun `invalid selection fails closed`() {
        assertThrows<IllegalArgumentException> {
            TerrainRuntimeSelection.parse("region-only")
        }
    }
}
