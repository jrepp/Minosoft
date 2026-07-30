/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uSkeletalBuffer
// minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uSkeletalBuffer
// minosoft:scene_bridge PLAYER_SKELETAL PLAYER uTextures,uSkeletalBuffer,uIndexLayer,uSkinParts,uInflate,uHideBase,uFeaturePart
// minosoft:scene_bridge BLOCK_FEATURE BLOCK uTextures,uMatrix
// minosoft:scene_bridge BLOCK_FEATURE FLASHING_BLOCK uTextures,uMatrix
// minosoft:scene_bridge POSITION_TEXTURE ENTITY_FLAME uTextures,uMatrix
// minosoft:scene_bridge TERRAIN TERRAIN uTextures

#version 330 core
uniform int renderStage;

#define MINOSOFT_SHADOW_MATRICES
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;

#if defined(MINOSOFT_STATE_ABI_ENTITY_FLAME)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;

uniform mat4 uMatrix;

#include "minosoft:animation"

void main() {
    vec4 position = uMatrix * vec4(vinPosition, 1.0);
    gl_Position = shadowProjection * shadowModelView * position;
    setTexture(vinUV, vinTexture);
}
#elif defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTransformNormal;
layout (location = 3) in float vinTexture;

#include "minosoft:skeletal/buffer"
#include "minosoft:animation"

void main() {
    uint packed = floatBitsToUint(vinTransformNormal);
    mat4 transform = uSkeletalTransforms[(packed >> 12u) & 0x7Fu];
    vec4 position = transform * vec4(vinPosition, 1.0);
    gl_Position = shadowProjection * shadowModelView * position;
    setTexture(vinUV, vinTexture);
}
#elif defined(MINOSOFT_STATE_ABI_PLAYER)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinPartTransformNormal;

uniform uint uIndexLayer;
uniform uint uSkinParts;
uniform float uInflate;
uniform bool uHideBase;
uniform uint uFeaturePart;

#include "minosoft:skeletal/buffer"
#include "minosoft:animation"

float decodeNormalPart(uint data) {
    return data < 8u ? (data / 8.0) - 1.0 : (data - 8u) / 7.0;
}

vec3 decodeNormal(uint normal) {
    return vec3(
        decodeNormalPart(normal & 0x0Fu),
        decodeNormalPart((normal >> 8u) & 0x0Fu),
        decodeNormalPart((normal >> 4u) & 0x0Fu)
    );
}

void main() {
    uint packed = floatBitsToUint(vinPartTransformNormal);
    uint skinPart = (packed >> 19u) & 0xFFu;
    bool hiddenFeature = skinPart >= 0xF0u && skinPart != uFeaturePart;
    bool hiddenBase = skinPart == 0u && uHideBase;
    bool hiddenSkinPart =
        skinPart > 0u && skinPart < 0xF0u &&
        ((1u << (skinPart - 1u)) & uSkinParts) == 0u;
    if (hiddenFeature || hiddenBase || hiddenSkinPart) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    mat4 transform = uSkeletalTransforms[(packed >> 12u) & 0x7Fu];
    vec3 inflated = vinPosition + decodeNormal(packed & 0xFFFu) * uInflate;
    vec4 position = transform * vec4(inflated, 1.0);
    gl_Position = shadowProjection * shadowModelView * position;
    setTexture(vinUV, uIndexLayer);
}
#elif defined(MINOSOFT_STATE_ABI_BLOCK) || defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTint;

uniform mat4 uMatrix;

#include "minosoft:vsh"
#include "minosoft:animation"

void main() {
    vec4 position = uMatrix * vec4(vinPosition, 1.0);
    gl_Position = shadowProjection * shadowModelView * position;
    setTexture(uv_unpack(floatBitsToUint(vinUV)), vinTexture);
}
#else
#include "/lib/shadow_terrain.vsh"
#endif
