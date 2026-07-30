/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

#version 330 core

layout (location = 0) in vec2 vinPosition;
layout (location = 1) in vec2 vinUV;

out vec2 finUV;

void main() {
    gl_Position = vec4(vinPosition, 0.0f, 1.0f);
    finUV = vinUV;
}
