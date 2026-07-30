/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge CLOUD CLOUD uViewProjectionMatrix,uCameraPosition,fog,uCloudsColor,uOffset,uYOffset

#version 330 core
uniform int renderStage;

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in uint vinSide;

out vec3 finFragmentPosition;
flat out float finBrightness;

uniform mat4 uViewProjectionMatrix;
uniform float uOffset;
uniform float uYOffset;

void main() {
    vec3 position = vinPosition;
    position.x -= uOffset;
    position.y += uYOffset;
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uViewProjectionMatrix * vec4(position, 1.0);
    finFragmentPosition = position;

    switch (vinSide) {
        case 0u: finBrightness = 0.7; break;
        case 1u: finBrightness = 1.0; break;
        case 2u: case 3u: finBrightness = 0.9; break;
        case 4u: case 5u: finBrightness = 0.8; break;
    }
}
