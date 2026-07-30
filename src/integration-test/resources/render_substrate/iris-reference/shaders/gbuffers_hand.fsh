/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

#if defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED)
#define FOG

out lowp vec4 foutColor;
uniform lowp vec4 uOutlineColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"

void main() {
    applyDefaults();
    applyTint();
    applyTexel();
    if (uOutlineColor.a > 0.0) foutColor = uOutlineColor;
}
#elif defined(MINOSOFT_STATE_ABI_HELD_ITEM)
out lowp vec4 foutColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:animation"

void main() {
    applyDefaults();
    applyTint();
    applyTexel();
}
#else
#define FOG
#define DISABLE_ALPHA_DISCARD

out lowp vec4 foutColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"

flat in uint finAllowTransparency;

void main() {
    if (finTintColor.a == 0.0) discard;
    applyDefaults();
    applyTint();
    applyTexel();
    if (finAllowTransparency > 0u && foutColor.a < 0.5) discard;
    foutColor.a = 1.0;
}
#endif
