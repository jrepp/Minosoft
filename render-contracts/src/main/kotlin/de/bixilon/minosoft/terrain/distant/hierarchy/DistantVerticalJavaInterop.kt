/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId

/** Stable Java bridge around Kotlin value-class constructors used by server adapters. */
object DistantVerticalJavaInterop {
    @JvmStatic
    fun voxel(
        material: String?,
        fluidMaterial: String?,
        fluidLevel: Int,
        fluidClassification: String?,
        blockLight: Int,
        skyLight: Int,
        biomeInput: String?,
        opaque: Boolean,
        emissive: Boolean,
        generated: Boolean,
        confidence: Int,
    ): DistantVoxelSample = DistantVoxelSample(
        material = material?.let(::TerrainSemanticMaterialId),
        fluid = when {
            fluidMaterial == null && fluidClassification == null -> null
            fluidMaterial != null && fluidClassification != null -> DistantFluidSample(
                TerrainSemanticMaterialId(fluidMaterial), fluidLevel, fluidClassification,
            )
            else -> throw IllegalArgumentException("Incomplete distant fluid sample")
        },
        blockLight = blockLight,
        skyLight = skyLight,
        tint = biomeInput?.let { DistantTintSample(null, it, 0L) },
        opaque = opaque,
        emissive = emissive,
        generated = generated,
        confidence = confidence,
    )
}
