/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

#define FOG

out lowp vec4 foutColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"
#include "minosoft:player_light"

void main() {
    applyDefaults();
    applyTint();
    foutColor.rgb = max(foutColor.rgb, vec3(playerLightContribution(finFragmentPosition)));
    applyTexel();
    if (foutColor.a < 0.1) discard;
    foutColor.a *= 0.5;
}
