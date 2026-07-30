/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

out lowp vec4 foutColor;

#ifdef MINOSOFT_STATE_ABI_WORLD_BORDER
uniform vec4 uTintColor;

#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"

void main() {
    applyDefaults();
    foutColor *= uTintColor;
    applyTexel();
}
#else
#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:animation"

void main() {
    applyDefaults();
    applyTint();
    applyTexel();
}
#endif
