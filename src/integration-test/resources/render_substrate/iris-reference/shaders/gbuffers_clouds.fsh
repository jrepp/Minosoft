/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

#define DISTANCE_MULTIPLIER 0.03

out lowp vec4 foutColor;
uniform vec3 uCloudsColor;
flat in float finBrightness;

#include "minosoft:fog"

void main() {
    foutColor = vec4(uCloudsColor * finBrightness, 1.0);
    fog_set();
}
