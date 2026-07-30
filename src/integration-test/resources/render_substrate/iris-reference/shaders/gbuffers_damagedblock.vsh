/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge DAMAGED_BLOCK DAMAGED_BLOCK uTextures,uLightMapBuffer,uViewProjectionMatrix,uCameraPosition,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius,fog,uTexture

#version 330 core
uniform int renderStage;

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinUV;
layout (location = 3) in float vinLight;

out vec3 finFragmentPosition;
uniform mat4 uViewProjectionMatrix;
uniform uint uTexture;

#include "minosoft:vsh"
#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:light"
#include "minosoft:animation"

void main() {
    gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
    finTintColor = getLight(floatBitsToUint(vinLight));
    finFragmentPosition = vinPosition;
    setTexture(uv_unpack(floatBitsToUint(vinUV)), uTexture);
}
