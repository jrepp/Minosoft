/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge PLANET PLANET uMatrix,uTintColor,uTextures
// minosoft:scene_bridge SKY_TEXTURE SKY_TEXTURE uTextures,uSkyViewProjectionMatrix,uTexture,uTintColor

#version 330 core
uniform int renderStage;

layout (location = 0) in vec3 vinPosition;
#ifdef MINOSOFT_STATE_ABI_SKY_TEXTURE
layout (location = 1) in uint uvIndex;
#else
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;
#endif

#include "minosoft:animation"

#ifdef MINOSOFT_STATE_ABI_SKY_TEXTURE
uniform mat4 uSkyViewProjectionMatrix;
uniform uint uTexture;
#include "minosoft:uv"
#else
uniform mat4 uMatrix;
#endif

void main() {
#ifdef MINOSOFT_STATE_ABI_SKY_TEXTURE
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uSkyViewProjectionMatrix * vec4(vinPosition, 1.0);
    setTexture(CONST_UV[uvIndex] * 20.0, uTexture);
#else
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uMatrix * vec4(vinPosition, 1.0);
    setTexture(vinUV, vinTexture);
#endif
}
