/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

#version 330 core

layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinTintColor;
layout (location = 2) in float vinLight;

uniform mat4 uViewProjectionMatrix;

out vec4 finTintColor;

#include "minosoft:color"
#include "minosoft:light"

void main() {
    gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0f);
    finTintColor = getRGBAColor(floatBitsToUint(vinTintColor))
        * getLight(floatBitsToUint(vinLight) & 0xFFu);
}
