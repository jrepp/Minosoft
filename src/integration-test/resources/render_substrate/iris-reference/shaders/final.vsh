/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

//#define REFERENCE_PROFILE
#define REFERENCE_QUALITY 1 // [1 2 3]

layout (location = 0) in vec2 vinPosition;
layout (location = 1) in vec2 vinUV;

out vec2 finUV;

void main() {
    gl_Position = vec4(vinPosition, 0.0, 1.0 + float(REFERENCE_QUALITY) * 0.0);
    finUV = vinUV;
}
