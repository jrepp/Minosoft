/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

#version 330 core

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
    gl_Position = uViewProjectionMatrix * uMatrix * vec4(vinPosition, 1.0f);
    finTintColor = getRGBColor(floatBitsToUint(vinTint)) * uTintColor;
    setTexture(uv_unpack(floatBitsToUint(vinUV)), vinTexture);
}
