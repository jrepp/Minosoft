/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

out vec4 shadowColor;

void main() {
    shadowColor = vec4(vec3(gl_FragCoord.z), 1.0);
}
