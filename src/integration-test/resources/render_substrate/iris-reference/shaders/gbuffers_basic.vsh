/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core
uniform int renderStage;

// minosoft:scene_bridge POSITION_COLOR COLOR uViewProjectionMatrix
// minosoft:scene_bridge POSITION_COLOR_LIGHT LIGHT_COLOR uLightMapBuffer,uViewProjectionMatrix

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinTintColor;
#ifdef MINOSOFT_STATE_ABI_LIGHT_COLOR
layout (location = 2) in float vinLight;
#endif

uniform mat4 uViewProjectionMatrix;

out vec4 finTintColor;

#include "minosoft:color"
#ifdef MINOSOFT_STATE_ABI_LIGHT_COLOR
#include "minosoft:light"
#endif

void main() {
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uViewProjectionMatrix * vec4(vinPosition, 1.0);
    finTintColor = getRGBAColor(floatBitsToUint(vinTintColor));
#ifdef MINOSOFT_STATE_ABI_LIGHT_COLOR
    finTintColor *= getLight(floatBitsToUint(vinLight) & 0xFFu);
#endif
}
