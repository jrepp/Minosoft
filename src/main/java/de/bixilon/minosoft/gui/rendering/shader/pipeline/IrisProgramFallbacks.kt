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
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass

/**
 * Iris 1.7.2 program fallback order expressed independently from compilation.
 * Keeping selection pure makes it possible to validate a pack before any GL
 * objects are allocated and prevents a geometry program from falling through
 * to a fullscreen composite program.
 */
object IrisProgramFallbacks {
    fun terrain(material: TerrainMaterialClass): List<String> = when (material) {
        TerrainMaterialClass.OPAQUE ->
            listOf("gbuffers_terrain_solid", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        TerrainMaterialClass.CUTOUT ->
            listOf("gbuffers_terrain_cutout", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        TerrainMaterialClass.TRANSLUCENT ->
            listOf("gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        TerrainMaterialClass.DISTANT_WATER ->
            listOf("gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        TerrainMaterialClass.EMISSIVE_ADDITIVE -> listOf("gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
    }

    fun shadow(material: TerrainMaterialClass): List<String> = when (material) {
        TerrainMaterialClass.OPAQUE -> listOf("shadow_solid", "shadow")
        TerrainMaterialClass.CUTOUT -> listOf("shadow_cutout", "shadow")
        TerrainMaterialClass.TRANSLUCENT -> listOf("shadow")
        TerrainMaterialClass.DISTANT_WATER -> listOf("shadow")
        TerrainMaterialClass.EMISSIVE_ADDITIVE -> emptyList()
    }

    fun shadowScene(): List<String> = listOf("shadow", "shadow_solid", "shadow_cutout")

    fun shadowScene(contract: SceneShaderContract): List<String> =
        if (contract.family == SceneProgramFamily.DISTANT_TERRAIN) {
            listOf("dh_shadow")
        } else {
            shadowScene()
        }

    fun scene(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
        separateEntityDraws: Boolean = false,
    ): List<String> {
        val family = family(semantic, contract)
        return when (family) {
            SceneProgramFamily.BASIC -> listOf("gbuffers_basic")
            SceneProgramFamily.LINE -> listOf("gbuffers_line", "gbuffers_basic")
            SceneProgramFamily.LEASH -> listOf("gbuffers_basic")
            SceneProgramFamily.BEACON_BEAM ->
                listOf("gbuffers_beaconbeam", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.LIGHTNING ->
                listOf("gbuffers_lightning", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.TEXTURED -> listOf("gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.TEXTURED_LIT -> listOf("gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.SKY_BASIC -> listOf("gbuffers_skybasic", "gbuffers_basic")
            SceneProgramFamily.SKY_TEXTURED -> listOf("gbuffers_skytextured", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.SUN,
            SceneProgramFamily.MOON,
            -> listOf("gbuffers_skytextured", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.CLOUDS -> listOf("gbuffers_clouds", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.TERRAIN -> listOf("gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.DISTANT_TERRAIN -> listOf("dh_terrain")
            SceneProgramFamily.DISTANT_WATER -> listOf("dh_water")
            SceneProgramFamily.DAMAGED_BLOCK -> listOf("gbuffers_damagedblock", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.BLOCK -> if (
                semantic in BLOCK_TRANSLUCENT_SEMANTICS && separateEntityDraws
            ) {
                listOf("gbuffers_block_translucent", "gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            } else {
                listOf("gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            }
            SceneProgramFamily.ENTITY_EYES ->
                listOf("gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.ARMOR_GLINT ->
                listOf("gbuffers_armor_glint", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.ENTITY -> if (
                semantic == PipelineSemantic.ENTITIES_TRANSLUCENT && separateEntityDraws
            ) {
                listOf("gbuffers_entities_translucent", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            } else {
                listOf("gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            }
            SceneProgramFamily.PARTICLE -> when (semantic) {
                PipelineSemantic.PARTICLES_TRANSLUCENT ->
                    listOf("gbuffers_particles_translucent", "gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")

                else -> listOf("gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            }

            SceneProgramFamily.WEATHER -> listOf("gbuffers_weather", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.HAND -> listOf("gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
            SceneProgramFamily.HAND_WATER ->
                listOf("gbuffers_hand_water", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")
        }
    }

    fun select(programs: Collection<ShaderProgramSource>, candidates: List<String>): ShaderProgramSource? {
        val byName = programs.associateBy(ShaderProgramSource::name)
        return candidates.firstNotNullOfOrNull(byName::get)
    }

    /**
     * A scene bridge is executable only when a retained host draw can request
     * its program root. ProgramId alone is not evidence of a producer route:
     * Iris 1.7.2 declares legacy IDs such as entities_glowing without exposing
     * a ShaderKey that selects them.
     */
    fun supportsSceneProgram(name: String): Boolean = name in SCENE_PROGRAMS

    private fun family(semantic: PipelineSemantic, contract: SceneShaderContract): SceneProgramFamily {
        if (
            contract.vertexAbi == SceneVertexAbi.BLOCK_FEATURE &&
            contract.family == SceneProgramFamily.BLOCK
        ) {
            return SceneProgramFamily.BLOCK
        }
        val declared = contract.family
        if (declared in SPECIALIZED_FAMILIES) return declared
        return when (semantic) {
        PipelineSemantic.ENTITIES,
        PipelineSemantic.ENTITIES_TRANSLUCENT,
        -> SceneProgramFamily.ENTITY
        PipelineSemantic.BLOCK_ENTITIES,
        PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
        -> SceneProgramFamily.BLOCK
        PipelineSemantic.PARTICLES,
        PipelineSemantic.PARTICLES_OPAQUE,
        PipelineSemantic.PARTICLES_TRANSLUCENT,
        -> SceneProgramFamily.PARTICLE

        PipelineSemantic.WEATHER -> SceneProgramFamily.WEATHER
        PipelineSemantic.HAND -> SceneProgramFamily.HAND
        PipelineSemantic.TERRAIN_OPAQUE,
        PipelineSemantic.TERRAIN_CUTOUT,
        PipelineSemantic.TERRAIN_TRANSLUCENT,
        PipelineSemantic.TERRAIN_EMISSIVE,
        -> SceneProgramFamily.TERRAIN
        PipelineSemantic.DISTANT_TERRAIN -> SceneProgramFamily.DISTANT_TERRAIN
        PipelineSemantic.DISTANT_WATER -> SceneProgramFamily.DISTANT_WATER

        PipelineSemantic.SKY,
        PipelineSemantic.WORLD_OVERLAY,
        PipelineSemantic.AUTO,
        -> declared
        }
    }

    private val SPECIALIZED_FAMILIES = setOf(
        SceneProgramFamily.LINE,
        SceneProgramFamily.LEASH,
        SceneProgramFamily.BEACON_BEAM,
        SceneProgramFamily.LIGHTNING,
        SceneProgramFamily.SUN,
        SceneProgramFamily.MOON,
        SceneProgramFamily.ENTITY_EYES,
        SceneProgramFamily.ARMOR_GLINT,
        SceneProgramFamily.HAND_WATER,
    )
    private val BLOCK_TRANSLUCENT_SEMANTICS = setOf(
        PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
        PipelineSemantic.ENTITIES_TRANSLUCENT,
    )

    private val SCENE_PROGRAMS = setOf(
        "gbuffers_basic",
        "gbuffers_line",
        "gbuffers_textured",
        "gbuffers_textured_lit",
        "gbuffers_skybasic",
        "gbuffers_skytextured",
        "gbuffers_clouds",
        "gbuffers_terrain",
        "gbuffers_damagedblock",
        "gbuffers_block",
        "gbuffers_block_translucent",
        "gbuffers_beaconbeam",
        "gbuffers_entities",
        "gbuffers_entities_translucent",
        "gbuffers_lightning",
        "gbuffers_particles",
        "gbuffers_particles_translucent",
        "gbuffers_armor_glint",
        "gbuffers_spidereyes",
        "gbuffers_hand",
        "gbuffers_weather",
        "gbuffers_hand_water",
        "dh_terrain",
        "dh_water",
    )
}
