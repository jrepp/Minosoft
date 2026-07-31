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
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.interop.TerrainLightingSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.model.interop.TerrainTintSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainUploadCapability

/** Stable exported capabilities of Minosoft's compact distant page renderer. */
object DistantTerrainInterop {
    const val PROVIDER_ID = "minosoft:distant-terrain"
    const val SEMANTIC_LAYOUT_ID = "minosoft:distant-semantic-v2"
    const val PHYSICAL_LAYOUT_ID = "minosoft:distant-compact-v1"
    const val PHYSICAL_LAYOUT_GENERATION = 1L

    val descriptor = TerrainInteropDescriptor(
        providerId = PROVIDER_ID,
        domains = setOf(TerrainDomain.DISTANT),
        capabilities = setOf(
            TerrainProviderCapability.COMPACT_VERTEX_ENCODING,
            TerrainProviderCapability.REGION_BATCHING,
            TerrainProviderCapability.AUXILIARY_VIEWS,
            TerrainProviderCapability.DISTANT_WATER,
        ),
        materials = setOf(TerrainMaterialClass.OPAQUE, TerrainMaterialClass.DISTANT_WATER),
        semanticVertexLayoutId = SEMANTIC_LAYOUT_ID,
        physicalLayoutIds = setOf(PHYSICAL_LAYOUT_ID),
        supportedViews = setOf("minosoft:main", "minosoft:shadow"),
        uploadCapabilities = setOf(TerrainUploadCapability.BUFFER_UPDATE, TerrainUploadCapability.MULTI_DRAW),
        shaderInputs = setOf("position", "color", "block_light", "sky_light", "normal", "material", "page_offset"),
        lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
        tintSemantics = TerrainTintSemantics.RESOLVED_COLOR,
        foreignProfileIds = setOf("distanthorizons:2.4.4-b"),
    )
}
