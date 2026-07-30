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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions

/**
 * Immutable identity and overlay state for one scene draw.
 *
 * This state deliberately lives outside [de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract]:
 * the contract describes a stable mesh/state ABI, while these values may change
 * between two draws using the same linked program.
 */
data class IrisDrawState(
    val entity: ResourceLocation? = null,
    val blockEntity: BlockState? = null,
    val item: ResourceLocation? = null,
    val entityColor: Vec4f = Vec4f.EMPTY,
) {
    fun over(parent: IrisDrawState): IrisDrawState = IrisDrawState(
        entity = entity ?: parent.entity,
        blockEntity = blockEntity ?: parent.blockEntity,
        item = item ?: parent.item,
        entityColor = if (entityColor == Vec4f.EMPTY) parent.entityColor else entityColor,
    )

    companion object {
        val EMPTY = IrisDrawState()
    }
}

internal data class IrisResolvedDrawState(
    val entityId: Int,
    val blockEntityId: Int,
    val currentRenderedItemId: Int,
    val entityColor: Vec4f,
    val blendFunc: IrisBlendFunction = IrisBlendFunction.EMPTY,
) {
    fun uploadTo(native: NativeShader, declaredUniforms: Set<String>): Int {
        var uploads = 0
        for (uniform in declaredUniforms) {
            if (uniform !in SUPPORTED_UNIFORMS || !native.hasUniform(uniform)) continue
            when (uniform) {
                "entityId" -> native.setInt(uniform, entityId)
                "blockEntityId" -> native.setInt(uniform, blockEntityId)
                "currentRenderedItemId" -> native.setInt(uniform, currentRenderedItemId)
                "entityColor" -> native.setVec4f(uniform, entityColor)
                "blendFunc" -> native.setVec4i(
                    uniform,
                    blendFunc.sourceRGB,
                    blendFunc.destinationRGB,
                    blendFunc.sourceAlpha,
                    blendFunc.destinationAlpha,
                )
            }
            uploads++
        }
        return uploads
    }

    companion object {
        val SUPPORTED_UNIFORMS = setOf(
            "entityId",
            "blockEntityId",
            "currentRenderedItemId",
            "entityColor",
            "blendFunc",
        )

        fun blend(enabled: Boolean, state: BlendFunctionState): IrisBlendFunction {
            if (!enabled) return IrisBlendFunction.EMPTY
            return IrisBlendFunction(
                state.sourceRGB.irisGl,
                state.destinationRGB.irisGl,
                state.sourceAlpha.irisGl,
                state.destinationAlpha.irisGl,
            )
        }

        private val BlendingFunctions.irisGl: Int
            get() = when (this) {
                BlendingFunctions.ZERO -> 0
                BlendingFunctions.ONE -> 1
                BlendingFunctions.SOURCE_COLOR -> 0x0300
                BlendingFunctions.ONE_MINUS_SOURCE_COLOR -> 0x0301
                BlendingFunctions.SOURCE_ALPHA -> 0x0302
                BlendingFunctions.ONE_MINUS_SOURCE_ALPHA -> 0x0303
                BlendingFunctions.DESTINATION_ALPHA -> 0x0304
                BlendingFunctions.ONE_MINUS_DESTINATION_ALPHA -> 0x0305
                BlendingFunctions.DESTINATION_COLOR -> 0x0306
                BlendingFunctions.ONE_MINUS_DESTINATION_COLOR -> 0x0307
                BlendingFunctions.SOURCE_ALPHA_SATURATE -> 0x0308
                BlendingFunctions.CONSTANT_COLOR -> 0x8001
                BlendingFunctions.ONE_MINUS_CONSTANT_COLOR -> 0x8002
                BlendingFunctions.CONSTANT_ALPHA -> 0x8003
                BlendingFunctions.ONE_MINUS_CONSTANT_ALPHA -> 0x8004
            }
    }
}

internal data class IrisBlendFunction(
    val sourceRGB: Int,
    val destinationRGB: Int,
    val sourceAlpha: Int,
    val destinationAlpha: Int,
) {
    companion object {
        val EMPTY = IrisBlendFunction(0, 0, 0, 0)
    }
}
