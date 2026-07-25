/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

layout (location = 0) in vec3 vinPosition;

void main() {
    vec3 normalized = (vinPosition - vec3(8.0)) / 32.0;
    gl_Position = vec4(normalized.x, normalized.z, normalized.y, 1.0);
}
