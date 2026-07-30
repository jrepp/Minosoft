/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core
uniform vec4 entityColor;

/* RENDERTARGETS: 0 */

#define FOG

#if defined(MINOSOFT_STATE_ABI_LIGHTNING)
in vec4 finTintColor;
out vec4 foutColor;

void main() {
    foutColor = finTintColor;
}
#elif defined(MINOSOFT_STATE_ABI_ENTITY_FLAME)
out lowp vec4 foutColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"

void main() {
    applyDefaults();
    applyTint();
    applyTexel();
    fog_set();
}
#elif defined(MINOSOFT_STATE_ABI_BLOCK) || defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK)
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
    foutColor.rgb = mix(foutColor.rgb, entityColor.rgb, entityColor.a);
#ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
    foutColor = mix(foutColor, finFlashColor, uFlashProgress);
#endif
    if (uOutlineColor.a > 0.0) foutColor = uOutlineColor;
}
#elif defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED)
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
    foutColor.rgb = mix(foutColor.rgb, entityColor.rgb, entityColor.a);
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
    foutColor.rgb = mix(foutColor.rgb, entityColor.rgb, entityColor.a);
}
#elif defined(MINOSOFT_STATE_ABI_PLAYER)
out lowp vec4 foutColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:fog"
#include "minosoft:animation"

flat in uint finAllowTransparency;
uniform bool uGlint;
uniform bool uAllowBaseTransparency;
uniform uint uGlintTexture;
uniform float uGlintTime;

vec4 sampleGlint(vec2 uv) {
    uint textureArray = uGlintTexture >> 28u;
    float textureLayer = float((uGlintTexture >> 12u) & 0xFFFFu);
    return getTexture(textureArray, vec3(uv, textureLayer));
}

void main() {
    if (finTintColor.a == 0.0) discard;
    applyDefaults();
    applyTint();
    vec4 texel = getTexture(finTextureArray, vec3(finTextureUV, finTextureLayer));
    if (finAllowTransparency != 0u || uGlint) {
        if (texel.a < 0.5) discard;
    } else if (uAllowBaseTransparency) {
        if (texel.a <= 0.0) discard;
    } else {
        texel.a = 1.0;
    }
    if (uGlint) {
        vec2 positionUV = vec2(finFragmentPosition.x - finFragmentPosition.z, finFragmentPosition.y) * 0.16;
        float first = sampleGlint(positionUV + vec2(uGlintTime * 0.010, 0.0)).r;
        mat2 rotate = mat2(0.7071068, -0.7071068, 0.7071068, 0.7071068);
        float second = sampleGlint((rotate * positionUV) - vec2(uGlintTime * 0.008, 0.0)).r;
        float intensity = clamp((first + second) * 0.65, 0.0, 1.0);
        foutColor *= vec4(vec3(0.38, 0.19, 0.608) * intensity, texel.a);
    } else {
        foutColor *= texel;
    }
    foutColor.rgb = mix(foutColor.rgb, entityColor.rgb, entityColor.a);
    fog_set();
}
#elif defined(MINOSOFT_STATE_ABI_BILLBOARD_TEXT)
#define DISABLE_MIPMAPS

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
#endif
