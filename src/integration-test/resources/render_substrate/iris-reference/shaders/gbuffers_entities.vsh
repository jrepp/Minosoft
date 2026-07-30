/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uTintColor,uOutlineColor
// minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uLight,uLightMapBuffer,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius
// minosoft:scene_bridge PLAYER_SKELETAL PLAYER uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uIndexLayer,uTintColor,uSkinParts,uInflate,uHideBase,uFeaturePart,uAllowBaseTransparency,uGlint,uGlintTexture,uGlintTime
// minosoft:scene_bridge BILLBOARD_TEXT BILLBOARD_TEXT uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor
// minosoft:scene_bridge POSITION_COLOR LIGHTNING uViewProjectionMatrix,uMatrix
// minosoft:scene_bridge POSITION_TEXTURE ENTITY_FLAME uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix
// minosoft:scene_bridge BLOCK_FEATURE BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor
// minosoft:scene_bridge BLOCK_FEATURE FLASHING_BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor,uFlashColor,uFlashProgress

#version 330 core
uniform int renderStage;
uniform int entityId;
uniform int blockEntityId;
uniform int currentRenderedItemId;
uniform vec4 entityColor;
uniform ivec4 blendFunc;

void retainIrisDrawAbi() {
    if (entityId == -2147483647 && blockEntityId == -2147483647 && currentRenderedItemId == -2147483647) {
        gl_Position += entityColor + vec4(blendFunc);
    }
}

#if defined(MINOSOFT_STATE_ABI_LIGHTNING)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinTintColor;

uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;
out vec4 finTintColor;

#include "minosoft:color"

void main() {
    gl_Position = uViewProjectionMatrix * uMatrix * vec4(vinPosition, 1.0);
    finTintColor = getRGBAColor(floatBitsToUint(vinTintColor));
}
#elif defined(MINOSOFT_STATE_ABI_ENTITY_FLAME)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTintColor;

uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;
out vec3 finFragmentPosition;

#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:animation"

void main() {
    vec4 position = uMatrix * vec4(vinPosition, 1.0);
    gl_Position = uViewProjectionMatrix * position;
    finFragmentPosition = position.xyz;
    finTintColor = getRGBColor(floatBitsToUint(vinTintColor) & 0xFFFFFFu);
    setTexture(vinUV, vinTexture);
    retainIrisDrawAbi();
}
#elif defined(MINOSOFT_STATE_ABI_BLOCK) || defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK)
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
    retainIrisDrawAbi();
}
#elif defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTransformNormal;
layout (location = 3) in float vinTexture;

out vec3 finFragmentPosition;

#include "minosoft:tint"
#include "minosoft:animation"
#include "minosoft:color"
#include "minosoft:skeletal/vertex"

uniform vec4 uTintColor;

void main() {
    run_skeletal(floatBitsToUint(vinTransformNormal), vinPosition);
    setTexture(vinUV, vinTexture);
    finTintColor *= uTintColor;
    retainIrisDrawAbi();
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
    retainIrisDrawAbi();
}
#elif defined(MINOSOFT_STATE_ABI_PLAYER)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinPartTransformNormal;

out vec3 finFragmentPosition;
uniform uint uIndexLayer;
uniform vec4 uTintColor;
uniform uint uSkinParts;
uniform float uInflate;
uniform bool uHideBase;
uniform uint uFeaturePart;
flat out uint finAllowTransparency;

#include "minosoft:tint"
#include "minosoft:skeletal/vertex"
#include "minosoft:color"
#include "minosoft:animation"

#define POSITIVE_INFINITY 1.0 / 0.0

void main() {
    uint partTransformNormal = floatBitsToUint(vinPartTransformNormal);
    uint skinPart = (partTransformNormal >> 19u) & 0xFFu;
    bool hiddenFeature = skinPart >= 0xF0u && skinPart != uFeaturePart;
    bool hiddenBase = skinPart == 0u && uHideBase;
    bool hiddenSkinPart = skinPart > 0u && skinPart < 0xF0u && ((1u << (skinPart - 1u)) & uSkinParts) == 0u;
    if (hiddenFeature || hiddenBase || hiddenSkinPart) {
        gl_Position = vec4(POSITIVE_INFINITY);
        finTintColor.a = 0.0;
        return;
    }
    finAllowTransparency = skinPart;
    vec3 inflatedPosition = vinPosition + decodeNormal(partTransformNormal & 0xFFFu) * uInflate;
    run_skeletal(partTransformNormal, inflatedPosition);
    finTintColor *= uTintColor;
    setTexture(vinUV, uIndexLayer);
    retainIrisDrawAbi();
}
#elif defined(MINOSOFT_STATE_ABI_BILLBOARD_TEXT)
layout (location = 0) in vec2 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTint;

uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;
uniform vec4 uTintColor;
out vec3 finFragmentPosition;

#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:animation"

void main() {
    vec4 position = uMatrix * vec4(vinPosition, 0.0, 1.0);
    gl_Position = uViewProjectionMatrix * position;
    finTintColor = getRGBAColor(floatBitsToUint(vinTint)) * uTintColor;
    finFragmentPosition = position.xyz;
    setTexture(vinUV, vinTexture);
}
#endif
