/*
 * Focused Minosoft Iris block-entity shadow acceptance pack.
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinAmbientUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinLightTint;
layout (location = 4) in vec2 mc_Entity;
layout (location = 5) in vec2 at_midTexCoord;
layout (location = 6) in vec4 at_tangent;
layout (location = 7) in vec3 vaNormal;
layout (location = 8) in vec4 at_midBlock;

uniform mat4 uViewProjectionMatrix;
uniform ivec2 atlasSize;
// minosoft:texture_array_index terrainTextureArray

out vec4 finMaterialGuard;

#include "minosoft:vsh"
#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:light"
#include "minosoft:animation"

void main() {
    uint terrainTextureArray = floatBitsToUint(vinTexture) >> 28u;
    uint ambientUV = floatBitsToUint(vinAmbientUV);
    uint lightTint = floatBitsToUint(vinLightTint);
    gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
    finTintColor = getRGBColor(lightTint & 0xFFFFFFu) * getLight(lightTint >> 24u);
    finMaterialGuard = vec4(
        dot(mc_Entity, vec2(1.0)) + dot(at_midTexCoord, vec2(1.0)),
        dot(at_tangent, vec4(1.0)),
        dot(vaNormal, vec3(1.0)),
        dot(at_midBlock, vec4(1.0)) + float(atlasSize.x + atlasSize.y)
    );
    setTexture(uv_unpack(ambientUV), vinTexture);
}
