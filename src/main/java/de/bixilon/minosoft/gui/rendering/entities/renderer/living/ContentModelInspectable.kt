/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.living

import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterialFrame
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerSetInspection
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel

/** Read-only acceptance view of the retained content model currently composed by a renderer. */
interface ContentModelInspectable {
    val retainedContentModel: BakedSkeletalModel?
    val retainedContentControllers: GeckoLibControllerSetInspection?
}

/**
 * Separates replacement geometry from intentional effect passes so a live
 * capture can distinguish duplicate body draws from emissive/overlay work.
 */
data class ContentModelDrawPasses(
    val baseVertices: Int,
    val selectedTexturePasses: Int,
    val selectedTextureVertices: Int,
    val emissivePasses: Int,
    val emissiveVertices: Int,
    val geckoLayerCandidates: Int,
    val geckoLayerCandidateVertices: Int,
) {
    val geometryPasses get() = (if (baseVertices > 0) 1 else 0) + selectedTexturePasses
    val knownDrawPasses get() = geometryPasses + emissivePasses
}

fun BakedSkeletalModel.inspectDrawPasses(
    textures: Map<ResourceLocation, EntityTextureMaterialFrame>,
): ContentModelDrawPasses {
    val baseVertices = mesh.buffer.vertices.coerceAtLeast(0)
    var selectedTexturePasses = 0
    var selectedTextureVertices = 0
    var emissivePasses = 0
    var emissiveVertices = 0
    if (entityTextureLayers.isEmpty()) {
        textures.values.singleOrNull()?.emissive?.let { emissive ->
            val vertices = mesh(emissive).buffer.vertices.coerceAtLeast(0)
            if (vertices > 0) {
                emissivePasses++
                emissiveVertices += vertices
            }
        }
    } else {
        for ((base, layer) in entityTextureLayers) {
            val frame = textures[base] ?: continue
            layer.meshes[frame.base]?.buffer?.vertices?.coerceAtLeast(0)?.let { vertices ->
                if (vertices > 0) {
                    selectedTexturePasses++
                    selectedTextureVertices += vertices
                }
            }
            frame.emissive?.let(layer.meshes::get)?.buffer?.vertices?.coerceAtLeast(0)?.let { vertices ->
                if (vertices > 0) {
                    emissivePasses++
                    emissiveVertices += vertices
                }
            }
        }
    }
    val geckoLayerVertices = geckoRenderLayers.values
        .map { it.mesh.buffer.vertices.coerceAtLeast(0) }
        .filter { it > 0 }
    return ContentModelDrawPasses(
        baseVertices = baseVertices,
        selectedTexturePasses = selectedTexturePasses,
        selectedTextureVertices = selectedTextureVertices,
        emissivePasses = emissivePasses,
        emissiveVertices = emissiveVertices,
        geckoLayerCandidates = geckoLayerVertices.size,
        geckoLayerCandidateVertices = geckoLayerVertices.sum(),
    )
}
