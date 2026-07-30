/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

out lowp vec4 foutColor;
uniform vec4 uTintColor;

#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:animation"

void main() {
    applyDefaults();
#ifdef MINOSOFT_STATE_ABI_SKY_TEXTURE
    foutColor.rgb *= uTintColor.rgb;
#else
    foutColor *= uTintColor;
    discard_alpha();
#endif
    applyTexel();
}
