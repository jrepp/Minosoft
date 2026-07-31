/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DistantTerrainInteropTest {
    @Test
    fun `compact distant provider exports water shadow and distinct layout contracts`() {
        val descriptor = DistantTerrainInterop.descriptor

        assertEquals(setOf(TerrainDomain.DISTANT), descriptor.domains)
        assertEquals(setOf(DistantTerrainInterop.PHYSICAL_LAYOUT_ID), descriptor.physicalLayoutIds)
        assertNotEquals("minosoft:near", DistantTerrainInterop.PHYSICAL_LAYOUT_ID)
        assertTrue(TerrainProviderCapability.COMPACT_VERTEX_ENCODING in descriptor.capabilities)
        assertTrue(TerrainProviderCapability.AUXILIARY_VIEWS in descriptor.capabilities)
        assertTrue(TerrainProviderCapability.DISTANT_WATER in descriptor.capabilities)
        assertTrue(TerrainMaterialClass.DISTANT_WATER in descriptor.materials)
        assertEquals(setOf("minosoft:main", "minosoft:shadow"), descriptor.supportedViews)
    }
}
