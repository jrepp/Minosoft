/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

#if defined(MINOSOFT_STATE_ABI_BLOCK) || defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK)
out lowp vec4 foutColor;
uniform lowp vec4 uOutlineColor;
#ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
uniform float uFlashProgress;
in vec4 finFlashColor;
#endif

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"

void main() {
    applyDefaults();
    applyTint();
    applyTexel();
#ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
    foutColor = mix(foutColor, finFlashColor, uFlashProgress);
#endif
    if (uOutlineColor.a > 0.0) foutColor = uOutlineColor;
}
#elif defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
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
}
#endif
