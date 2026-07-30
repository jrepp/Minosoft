/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge POSITION_TEXTURE GENERIC_TEXTURE uTextures,uViewProjectionMatrix

#version 330 core

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTintColor;

#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:animation"

uniform mat4 uViewProjectionMatrix;

void main() {
    gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
    finTintColor = getRGBColor(floatBitsToUint(vinTintColor) & 0xFFFFFFu);
    setTexture(vinUV, vinTexture);
}
