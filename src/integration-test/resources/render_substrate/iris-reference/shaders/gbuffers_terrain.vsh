/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2020 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core
uniform int renderStage;
uniform ivec2 atlasSize;
// minosoft:texture_array_index terrainTextureArray

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinAmbientUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinLightTint;
layout (location = 4) in vec2 mc_Entity;
layout (location = 5) in vec2 at_midTexCoord;
layout (location = 6) in vec4 at_tangent;
layout (location = 7) in vec3 vaNormal;
layout (location = 8) in vec4 at_midBlock;

out vec3 finFragmentPosition;
out lowp vec3 finPlayerLightTint;
flat out float finTerrainBlockId;
out vec4 finTerrainMaterialGuard;

uniform mat4 uViewProjectionMatrix;

#include "minosoft:vsh"
#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:light"
#include "minosoft:animation"

const vec3 AMBIENT_OCCLUSION[4] = vec3[4](
    vec3(1.0),
    vec3(0.85),
    vec3(0.75),
    vec3(0.60)
);

void main() {
    uint terrainTextureArray = floatBitsToUint(vinTexture) >> 28u;
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uViewProjectionMatrix * vec4(vinPosition, 1.0);
    uint lightTint = floatBitsToUint(vinLightTint);
    vec4 materialTint = getRGBColor(lightTint & 0xFFFFFFu);
    finTintColor = materialTint * getLight(lightTint >> 24u);
    finFragmentPosition = vinPosition;

    uint ambientUV = floatBitsToUint(vinAmbientUV);
    vec3 ambientOcclusion = AMBIENT_OCCLUSION[(ambientUV >> 24u) & 0x3u];
    finTintColor.rgb *= ambientOcclusion;
    finPlayerLightTint = materialTint.rgb * ambientOcclusion;
    finTerrainBlockId = mc_Entity.x;
    finTerrainMaterialGuard = vec4(
        dot(at_midTexCoord, vec2(1.0)),
        dot(at_tangent, vec4(1.0)),
        dot(vaNormal, vec3(1.0)),
        dot(at_midBlock, vec4(1.0)) + float(atlasSize.x + atlasSize.y)
    );
    setTexture(uv_unpack(ambientUV), vinTexture);
}
