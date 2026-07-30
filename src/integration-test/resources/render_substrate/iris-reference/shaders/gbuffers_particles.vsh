/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge PARTICLE_POINT PARTICLE uTextures,uLightMapBuffer,uViewProjectionMatrix,fog,uCameraPosition,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius,uCameraRight,uCameraUp

#version 330 core
uniform int renderStage;

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinMinUV;
layout (location = 2) in float vinMaxUV;
layout (location = 3) in float vinTexture;
layout (location = 4) in float vinScale;
layout (location = 5) in float vinTintColor;
layout (location = 6) in float vinLight;

#include "minosoft:vsh"
#include "minosoft:light"
#include "minosoft:player_light"

out Vertex {
    vec2 minUV;
    vec2 maxUV;
    flat uint array;
    flat float layer;
    float scale;
    vec4 tintColor;
} ginVertex;

#include "minosoft:color"
#include "minosoft:animation"

void main() {
    gl_Position = vec4(vinPosition, 1.0);
    ginVertex.maxUV = uv_unpack(floatBitsToUint(vinMaxUV));
    ginVertex.minUV = uv_unpack(floatBitsToUint(vinMinUV));
    ginVertex.scale = vinScale;
    vec4 light = getLight(floatBitsToUint(vinLight) & 0xFFu);
    light.rgb = applyPlayerLight(light.rgb, vinPosition);
    ginVertex.tintColor = getRGBAColor(floatBitsToUint(vinTintColor)) * light;
    setTexture(vinTexture);
    ginVertex.array = textureArray;
    ginVertex.layer = textureLayer;
}
