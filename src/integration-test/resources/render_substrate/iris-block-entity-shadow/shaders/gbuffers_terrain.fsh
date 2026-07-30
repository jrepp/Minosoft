/*
 * Focused Minosoft Iris block-entity shadow acceptance pack.
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

out lowp vec4 foutColor;
in vec4 finMaterialGuard;
uniform ivec2 gtextureSize;
// minosoft:texture_array_index finTextureArray

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:animation"

void main() {
    if (isnan(dot(finMaterialGuard, vec4(1.0)))) discard;
    if (gtextureSize.x <= 0 || gtextureSize.y <= 0) discard;
    applyDefaults();
    applyTint();
    applyTexel();
}
