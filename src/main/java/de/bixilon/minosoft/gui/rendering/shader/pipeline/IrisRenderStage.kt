/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass

/**
 * Pinned numeric contract of Iris 1.7.2's WorldRenderingPhase.
 *
 * Values are explicit instead of using this enum's ordinal so a local
 * refactor cannot silently change the shader-pack ABI.
 */
internal enum class IrisRenderStage(val shaderValue: Int) {
    NONE(0),
    SKY(1),
    SUNSET(2),
    CUSTOM_SKY(3),
    SUN(4),
    MOON(5),
    STARS(6),
    VOID(7),
    TERRAIN_SOLID(8),
    TERRAIN_CUTOUT_MIPPED(9),
    TERRAIN_CUTOUT(10),
    ENTITIES(11),
    BLOCK_ENTITIES(12),
    DESTROY(13),
    OUTLINE(14),
    DEBUG(15),
    HAND_SOLID(16),
    TERRAIN_TRANSLUCENT(17),
    TRIPWIRE(18),
    PARTICLES(19),
    CLOUDS(20),
    RAIN_SNOW(21),
    WORLD_BORDER(22),
    HAND_TRANSLUCENT(23),
    ;

    fun uploadTo(native: NativeShader, declaredUniforms: Set<String>): Int {
        if (UNIFORM !in declaredUniforms) return 0
        if (!native.hasUniform(UNIFORM)) return 0
        native.setInt(UNIFORM, shaderValue)
        return 1
    }

    companion object {
        const val UNIFORM = "renderStage"
        val SUPPORTED_UNIFORMS = setOf(UNIFORM)

        fun terrain(material: TerrainMaterialClass): IrisRenderStage = when (material) {
            TerrainMaterialClass.OPAQUE,
            TerrainMaterialClass.EMISSIVE_ADDITIVE,
            -> TERRAIN_SOLID

            // Minosoft's cutout atlas is mipmapped, matching Iris's Sodium
            // GBUFFER_CUTOUT terrain pass.
            TerrainMaterialClass.CUTOUT -> TERRAIN_CUTOUT_MIPPED
            TerrainMaterialClass.TRANSLUCENT -> TERRAIN_TRANSLUCENT
        }

        fun scene(semantic: PipelineSemantic, contract: SceneShaderContract): IrisRenderStage = when (semantic) {
            PipelineSemantic.SKY -> when (contract.family) {
                SceneProgramFamily.CLOUDS -> CLOUDS
                SceneProgramFamily.SUN -> SUN
                SceneProgramFamily.MOON -> MOON
                SceneProgramFamily.SKY_TEXTURED -> CUSTOM_SKY
                else -> when (contract.stateAbi) {
                    SceneStateAbi.SUN_SCATTER -> SUN
                    else -> SKY
                }
            }

            PipelineSemantic.TERRAIN_OPAQUE,
            PipelineSemantic.TERRAIN_EMISSIVE,
            -> TERRAIN_SOLID

            PipelineSemantic.TERRAIN_CUTOUT -> TERRAIN_CUTOUT_MIPPED
            PipelineSemantic.TERRAIN_TRANSLUCENT -> TERRAIN_TRANSLUCENT
            PipelineSemantic.DISTANT_TERRAIN -> TERRAIN_SOLID
            PipelineSemantic.DISTANT_WATER -> TERRAIN_TRANSLUCENT
            PipelineSemantic.ENTITIES,
            PipelineSemantic.ENTITIES_TRANSLUCENT,
            -> ENTITIES

            PipelineSemantic.BLOCK_ENTITIES,
            PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
            -> BLOCK_ENTITIES

            PipelineSemantic.PARTICLES,
            PipelineSemantic.PARTICLES_OPAQUE,
            PipelineSemantic.PARTICLES_TRANSLUCENT,
            -> PARTICLES

            PipelineSemantic.WEATHER -> RAIN_SNOW
            PipelineSemantic.HAND -> if (contract.family == SceneProgramFamily.HAND_WATER) {
                HAND_TRANSLUCENT
            } else {
                HAND_SOLID
            }

            PipelineSemantic.WORLD_OVERLAY -> when {
                contract.family == SceneProgramFamily.DAMAGED_BLOCK -> DESTROY
                contract.stateAbi == SceneStateAbi.WORLD_BORDER -> WORLD_BORDER
                contract.family == SceneProgramFamily.LINE -> OUTLINE
                else -> NONE
            }

            PipelineSemantic.AUTO -> NONE
        }
    }
}
