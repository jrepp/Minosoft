/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge POSITION_TEXTURE_2D GENERIC_TEXTURE_2D uTextures
// minosoft:scene_bridge WORLD_BORDER WORLD_BORDER uTextures,uViewProjectionMatrix,uCameraPosition,fog,uTintColor,uTexture,uTextureOffset
// minosoft:scene_bridge POSITION_TEXTURE BEACON_BEAM uTextures,uViewProjectionMatrix,uMatrix,uTextureOffset

#version 330 core

#if defined(MINOSOFT_STATE_ABI_BEACON_BEAM)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTintColor;

uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;
uniform float uTextureOffset;

#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:animation"

void main() {
    gl_Position = uViewProjectionMatrix * uMatrix * vec4(vinPosition, 1.0);
    finTintColor = getRGBAColor(floatBitsToUint(vinTintColor));
    setTexture(vinUV + vec2(0.0, uTextureOffset), vinTexture);
}
#elif defined(MINOSOFT_STATE_ABI_WORLD_BORDER)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinUVIndex;
layout (location = 2) in float vinWidth;

uniform mat4 uViewProjectionMatrix;
uniform uint uTexture;
uniform float uTextureOffset;
uniform vec3 uCameraPosition;

out vec3 finFragmentPosition;

#define DENSITY 1.0
#define HEIGHT 300.0

#include "minosoft:uv"
#include "minosoft:color"
#include "minosoft:light"
#include "minosoft:animation"

void main() {
    vec3 position = vinPosition;
    if (position.y < 0.0) position.y = uCameraPosition.y - (HEIGHT / 2.0);
    else if (position.y > 0.0) position.y = uCameraPosition.y + (HEIGHT / 2.0);
    gl_Position = uViewProjectionMatrix * vec4(position, 1.0);
    vec2 uv = CONST_UV[floatBitsToUint(vinUVIndex)];
    uv.x *= vinWidth / DENSITY;
    uv.y *= vinWidth * (HEIGHT / vinWidth) / 2.0 / DENSITY;
    uv += vec2(uTextureOffset);
    setTexture(uv, uTexture);
    finFragmentPosition = position;
}
#else
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTintColor;

#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:animation"

void main() {
    gl_Position = vec4(vinPosition, 1.0);
    finTintColor = getRGBAColor(floatBitsToUint(vinTintColor));
    setTexture(vinUV, vinTexture);
}
#endif
