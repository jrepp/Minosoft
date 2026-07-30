/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge BLOCK_FEATURE BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor
// minosoft:scene_bridge BLOCK_FEATURE FLASHING_BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor,uFlashColor,uFlashProgress
// minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uLight,uLightMapBuffer,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius

#version 330 core
uniform int renderStage;
uniform int blockEntityId;
uniform ivec4 blendFunc;

void retainIrisBlockEntityAbi() {
    if (blockEntityId == -2147483647) gl_Position += vec4(blendFunc);
}

#if defined(MINOSOFT_STATE_ABI_BLOCK) || defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTint;

uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;
uniform vec4 uTintColor;
#ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
uniform vec4 uFlashColor;
out vec4 finFlashColor;
#endif

out vec3 finFragmentPosition;

#include "minosoft:vsh"
#include "minosoft:tint"
#include "minosoft:animation"
#include "minosoft:color"

void main() {
    vec4 position = uMatrix * vec4(vinPosition, 1.0);
    gl_Position = uViewProjectionMatrix * position;
    finTintColor = getRGBColor(floatBitsToUint(vinTint)) * uTintColor;
    finFragmentPosition = position.xyz;
    setTexture(uv_unpack(floatBitsToUint(vinUV)), vinTexture);
#ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
    finFlashColor = uFlashColor;
#endif
    retainIrisBlockEntityAbi();
}
#elif defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTransformNormal;
layout (location = 3) in float vinTexture;

out vec3 finFragmentPosition;

#include "minosoft:tint"
#include "minosoft:animation"
#include "minosoft:light"
#include "minosoft:skeletal/vertex"

uniform uint uLight;

void main() {
    run_skeletal(floatBitsToUint(vinTransformNormal), vinPosition);
    setTexture(vinUV, vinTexture);
    finTintColor *= getLight(uLight & 0xFFu);
    retainIrisBlockEntityAbi();
}
#endif
