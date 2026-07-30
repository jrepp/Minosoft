/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

#version 330 core

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinTintColor;
layout (location = 2) in float vinLight;
layout (location = 3) in float vinNormalMaterial;

uniform mat4 uViewProjectionMatrix;
uniform vec3 uCameraPosition;

out vec4 finColor;
out float finHorizontalDistance;
flat out uint finSurfaceFlags;

#include "minosoft:color"
#include "minosoft:light"

void main() {
    uint light = floatBitsToUint(vinLight);
    uint normalMaterial = floatBitsToUint(vinNormalMaterial);
    uint normal = normalMaterial & 0x7u;
    float faceShade = normal == 1u ? 1.0 : (normal == 2u || normal == 3u ? 0.82 : 0.68);
    finColor = getRGBAColor(floatBitsToUint(vinTintColor)) * getLight(light & 0xFFu) * vec4(vec3(faceShade), 1.0);
    finHorizontalDistance = length((vinPosition - uCameraPosition).xz);
    finSurfaceFlags = normalMaterial >> 11u;
    gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
}
