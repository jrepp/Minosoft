/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisIdMaps
import de.bixilon.minosoft.tags.MinecraftTagTypes.BLOCK

/**
 * Immutable block material state copied into every terrain vertex.
 *
 * The render-type values match Iris 1.7.2's ExtendedDataHelper:
 * ordinary block geometry is -1 and fluid geometry is 1.
 */
data class IrisTerrainMaterial(
    val blockId: Int = -1,
    val renderType: Int = BLOCK_RENDER_TYPE,
    val centerX: Float = 0.0f,
    val centerY: Float = 0.0f,
    val centerZ: Float = 0.0f,
    val lightValue: Int = 0,
) {
    init {
        require(blockId in Short.MIN_VALUE..Short.MAX_VALUE) {
            "Iris terrain block ID must fit the pinned signed-short ABI: $blockId"
        }
        require(lightValue in 0..15) { "Terrain block light value must be in 0..15: $lightValue" }
    }

    companion object {
        const val BLOCK_RENDER_TYPE = -1
        const val FLUID_RENDER_TYPE = 1
        val EMPTY = IrisTerrainMaterial()
    }
}

/**
 * A generation snapshot used by asynchronous chunk meshing.
 *
 * Capturing the selected plan prevents a mesh from observing a changing ID
 * table halfway through a section. The Iris adapter invalidates terrain after
 * publishing a new generation, interrupting old jobs and rebuilding with a new
 * resolver.
 */
class IrisTerrainMaterialResolver(
    val generation: String?,
    private val maps: IrisIdMaps,
    private val tagMatcher: (ResourceLocation, BlockState) -> Boolean,
) {
    fun resolve(
        state: BlockState,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        fluid: Boolean,
    ) = IrisTerrainMaterial(
        blockId = maps.block(state, missing = -1, tagMatcher),
        renderType = if (fluid) IrisTerrainMaterial.FLUID_RENDER_TYPE else IrisTerrainMaterial.BLOCK_RENDER_TYPE,
        centerX = centerX,
        centerY = centerY,
        centerZ = centerZ,
        lightValue = state.luminance,
    )

    companion object {
        val EMPTY = IrisTerrainMaterialResolver(null, IrisIdMaps.EMPTY) { _, _ -> false }

        fun capture(context: RenderContext): IrisTerrainMaterialResolver {
            val plan = context.shaderPipeline.plan()
            return IrisTerrainMaterialResolver(plan?.fingerprint, plan?.idMaps ?: IrisIdMaps.EMPTY) { tag, state ->
                context.session.tags.isIn(BLOCK, tag, state.block) ||
                    context.session.legacyTags.isIn(BLOCK, tag, state.block)
            }
        }
    }
}
