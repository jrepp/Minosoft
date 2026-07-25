/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

in vec2 finUV;
out vec4 foutColor;
uniform sampler2D uTexture;

void main() {
    vec4 source = texture(uTexture, finUV);
    vec3 coolShadows = vec3(0.012, 0.025, 0.055) * (1.0 - source.rgb);
    foutColor = vec4(clamp(pow(source.rgb, vec3(0.92)) + coolShadows, 0.0, 1.0), source.a);
}
