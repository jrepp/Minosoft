/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge WEATHER WEATHER uTextures,uIntensity,uOffset,uTexture

#version 330 core
uniform int renderStage;

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in vec2 vinUV;
layout (location = 2) in float vinOffset;
layout (location = 3) in float vinOffsetMultiplicator;
layout (location = 4) in float vinAlphaMultiplicator;

uniform float uIntensity;
uniform float uOffset;
uniform uint uTexture;

#include "minosoft:animation"

void main() {
    gl_Position = vec4(vinPosition, 1.0);
    vec2 uv = vinUV;
    uv.y += vinOffset + (uOffset * vinOffsetMultiplicator);
    setTexture(uv, uTexture);
}
