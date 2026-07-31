/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.model.interop

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class TerrainInteropTest {
    @Test
    fun `descriptor defensively snapshots compatibility declarations`() {
        val capabilities = mutableSetOf(TerrainProviderCapability.REGION_BATCHING)
        val layouts = mutableSetOf("minosoft:near")
        val descriptor = descriptor(capabilities = capabilities, physicalLayoutIds = layouts)

        capabilities.clear()
        layouts.clear()

        assertEquals(setOf(TerrainProviderCapability.REGION_BATCHING), descriptor.capabilities)
        assertEquals(setOf("minosoft:near"), descriptor.physicalLayoutIds)
        assertThrows<UnsupportedOperationException> {
            (descriptor.capabilities as MutableSet).clear()
        }
    }

    @Test
    fun `descriptor rejects incomplete provider declarations`() {
        assertThrows<IllegalArgumentException> { descriptor(providerId = " ") }
        assertThrows<IllegalArgumentException> { descriptor(physicalLayoutIds = emptySet()) }
        assertThrows<IllegalArgumentException> { descriptor(supportedViews = setOf("")) }
    }

    private fun descriptor(
        providerId: String = "minosoft:near",
        capabilities: Set<TerrainProviderCapability> = emptySet(),
        physicalLayoutIds: Set<String> = setOf("minosoft:near"),
        supportedViews: Set<String> = setOf("minosoft:main"),
    ) = TerrainInteropDescriptor(
        providerId = providerId,
        domains = setOf(TerrainDomain.NEAR),
        capabilities = capabilities,
        materials = setOf(TerrainMaterialClass.OPAQUE),
        semanticVertexLayoutId = "minosoft:terrain",
        physicalLayoutIds = physicalLayoutIds,
        supportedViews = supportedViews,
        uploadCapabilities = setOf(TerrainUploadCapability.BUFFER_UPDATE),
        shaderInputs = setOf("position"),
        lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
        tintSemantics = TerrainTintSemantics.BIOME_INPUT,
    )
}
