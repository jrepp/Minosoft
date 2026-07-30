/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

out lowp vec4 foutColor;
uniform float uIntensity;

#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:animation"

void main() {
    if (uIntensity <= 0.0) discard;
    applyTexel();
    foutColor.a *= uIntensity;
}
