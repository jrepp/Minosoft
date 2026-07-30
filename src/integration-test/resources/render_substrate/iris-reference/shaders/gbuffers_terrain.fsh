/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core
uniform ivec2 gtextureSize;
// minosoft:texture_array_index finTextureArray

/* RENDERTARGETS: 0 */

#define FOG

out lowp vec4 foutColor;
in lowp vec3 finPlayerLightTint;
flat in float finTerrainBlockId;
in vec4 finTerrainMaterialGuard;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"
#include "minosoft:player_light"

void main() {
    if (isnan(finTerrainBlockId + dot(finTerrainMaterialGuard, vec4(1.0)))) discard;
    if (gtextureSize.x <= 0 || gtextureSize.y <= 0) discard;
    applyDefaults();
    applyTint();
    foutColor.rgb = max(foutColor.rgb, finPlayerLightTint * playerLightContribution(finFragmentPosition));
    applyTexel();
    foutColor.rgb = pow(max(foutColor.rgb, vec3(0.0)), vec3(0.96));
}
