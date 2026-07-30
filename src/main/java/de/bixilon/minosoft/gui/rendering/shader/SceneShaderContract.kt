/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader

/**
 * Provider-neutral geometry family. The active shader pipeline combines this
 * hint with the graph semantic; for example every shader inside an entity pass
 * resolves through the entity family even when its host implementation is a
 * generic textured shader.
 */
enum class SceneProgramFamily {
    BASIC,
    LINE,
    LEASH,
    BEACON_BEAM,
    LIGHTNING,
    TEXTURED,
    TEXTURED_LIT,
    SKY_BASIC,
    SKY_TEXTURED,
    SUN,
    MOON,
    CLOUDS,
    TERRAIN,
    DISTANT_TERRAIN,
    DISTANT_WATER,
    DAMAGED_BLOCK,
    BLOCK,
    ENTITY,
    ENTITY_EYES,
    ARMOR_GLINT,
    PARTICLE,
    WEATHER,
    HAND,
    HAND_WATER,
}

/**
 * Exact physical vertex layouts currently emitted by Minosoft scene meshes.
 * An Iris program may replace a host shader only after it declares or builds a
 * bridge for this ABI.
 */
enum class SceneVertexAbi {
    POSITION_COLOR,
    POSITION_COLOR_LIGHT,
    POSITION_TEXTURE,
    POSITION_TEXTURE_2D,
    BLOCK_FEATURE,
    BILLBOARD_TEXT,
    SKELETAL,
    PLAYER_SKELETAL,
    ARM_SKELETAL,
    HELD_ITEM,
    PARTICLE_POINT,
    SKY_POSITION,
    SKY_TEXTURE,
    CLOUD,
    PLANET,
    SUN_SCATTER,
    WEATHER,
    WORLD_BORDER,
    DAMAGED_BLOCK,
    TERRAIN,
    DISTANT_TERRAIN,
}

/**
 * Retained host-state surface copied into a selected scene program. Vertex ABI
 * alone is insufficient because multiple shaders can consume the same physical
 * mesh layout while exposing different uniforms and texture bindings.
 */
enum class SceneStateAbi {
    COLOR,
    LIGHT_COLOR,
    BEACON_BEAM,
    LIGHTNING,
    GENERIC_TEXTURE,
    GENERIC_TEXTURE_2D,
    ENTITY_FLAME,
    BLOCK,
    FLASHING_BLOCK,
    BILLBOARD_TEXT,
    SKELETAL_TINTED,
    SKELETAL_LIGHTMAP,
    PLAYER,
    ARM,
    HELD_ITEM,
    PARTICLE,
    SKY_COLOR,
    SKY_TEXTURE,
    CLOUD,
    PLANET,
    SUN_SCATTER,
    WEATHER,
    WORLD_BORDER,
    DAMAGED_BLOCK,
    TERRAIN,
    DISTANT_TERRAIN,
}

data class SceneShaderContract(
    val family: SceneProgramFamily,
    val vertexAbi: SceneVertexAbi,
    val stateAbi: SceneStateAbi,
)
