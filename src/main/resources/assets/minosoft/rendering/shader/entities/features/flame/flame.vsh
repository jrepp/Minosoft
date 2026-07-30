/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

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
}
