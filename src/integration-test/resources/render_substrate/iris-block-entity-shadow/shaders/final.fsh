/*
 * Focused Minosoft Iris block-entity shadow acceptance pack.
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

in vec2 finUV;
out vec4 foutColor;
uniform sampler2D colortex0;

void main() {
    foutColor = texture(colortex0, finUV);
}
