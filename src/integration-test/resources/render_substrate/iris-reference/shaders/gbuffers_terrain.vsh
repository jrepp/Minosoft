/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2020 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinAmbientUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinLightTint;

out vec3 finFragmentPosition;
out lowp vec3 finPlayerLightTint;

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
    gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
    uint lightTint = floatBitsToUint(vinLightTint);
    vec4 materialTint = getRGBColor(lightTint & 0xFFFFFFu);
    finTintColor = materialTint * getLight(lightTint >> 24u);
    finFragmentPosition = vinPosition;

    uint ambientUV = floatBitsToUint(vinAmbientUV);
    vec3 ambientOcclusion = AMBIENT_OCCLUSION[(ambientUV >> 24u) & 0x3u];
    finTintColor.rgb *= ambientOcclusion;
    finPlayerLightTint = materialTint.rgb * ambientOcclusion;
    setTexture(uv_unpack(ambientUV), vinTexture);
}
