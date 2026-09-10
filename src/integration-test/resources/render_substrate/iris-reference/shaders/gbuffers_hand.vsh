/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge ARM_SKELETAL ARM uTextures,uTexture,uTintColor,uSkinParts,uViewProjectionMatrix,uMatrix
// minosoft:scene_bridge HELD_ITEM HELD_ITEM uTextures,uViewProjectionMatrix,uMatrix,uTintColor
// minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uTintColor,uOutlineColor

#version 330 core
uniform int renderStage;
uniform int currentRenderedItemId;
uniform int heldItemId;
uniform int heldItemId2;
uniform int heldBlockLightValue;
uniform int heldBlockLightValue2;
uniform vec3 heldBlockLightColor;
uniform vec3 heldBlockLightColor2;
uniform ivec4 blendFunc;

void retainIrisHandAbi() {
    if (currentRenderedItemId == -2147483647 && heldItemId == -2147483647 && heldItemId2 == -2147483647) {
        gl_Position += vec4(
            heldBlockLightColor + heldBlockLightColor2 + vec3(heldBlockLightValue + heldBlockLightValue2),
            0.0
        ) + vec4(blendFunc);
    }
}

#if defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED)
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
    retainIrisHandAbi();
}
#elif defined(MINOSOFT_STATE_ABI_HELD_ITEM)
layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinTint;

uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;
uniform vec4 uTintColor;

#include "minosoft:vsh"
#include "minosoft:tint"
#include "minosoft:animation"
#include "minosoft:color"

void main() {
    gl_Position = uViewProjectionMatrix * uMatrix * vec4(vinPosition, 1.0);
    finTintColor = getRGBColor(floatBitsToUint(vinTint)) * uTintColor;
    setTexture(uv_unpack(floatBitsToUint(vinUV)), vinTexture);
    retainIrisHandAbi();
}
#else
#define POSITIVE_INFINITY 1.0 / 0.0

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinPartTransformNormal;

out vec3 finFragmentPosition;
flat out uint finAllowTransparency;

uniform uint uTexture;
uniform vec4 uTintColor;
uniform uint uSkinParts;
uniform mat4 uViewProjectionMatrix;
uniform mat4 uMatrix;

#include "minosoft:tint"
#include "minosoft:color"
#include "minosoft:skeletal/shade"
#include "minosoft:animation"

void run_skeletal(uint inTransformNormal, vec3 inPosition) {
    vec4 position = uMatrix * vec4(inPosition, 1.0);
    gl_Position = renderStage < 0 ? vec4(0.0) : uViewProjectionMatrix * position;
    vec3 normal = transformNormal(decodeNormal(inTransformNormal & 0xFFFu), uMatrix);
    finTintColor = vec4(vec3(getShade(normal)), 1.0);
    finFragmentPosition = position.xyz;
}

void main() {
    uint partTransformNormal = floatBitsToUint(vinPartTransformNormal);
    uint skinPart = (partTransformNormal >> 19u) & 0xFFu;
    if (skinPart > 0u && ((1u << (skinPart - 1u)) & uSkinParts) == 0u) {
        gl_Position = vec4(POSITIVE_INFINITY);
        finTintColor.a = 0.0;
        return;
    }
    finAllowTransparency = skinPart;
    run_skeletal(partTransformNormal, vinPosition);
    finTintColor *= uTintColor;
    setTexture(vinUV, uTexture);
    retainIrisHandAbi();
}
#endif
