/*
 * Minosoft
 * Copyright (C) 2020 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

#version 330 core

#define FOG

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
    if (finTintColor.a == 0.0f) discard;
    applyDefaults();
    applyTint();
    vec4 texel = getTexture(finTextureArray, vec3(finTextureUV, finTextureLayer));
    if (finAllowTransparency != 0u || uGlint) {
        if (texel.a < 0.5f) discard;
    } else if (uAllowBaseTransparency) {
        if (texel.a <= 0.0f) discard;
    } else {
        texel.a = 1.0f;
    }

    if (uGlint) {
        if (texel.a < 0.5f) discard;
        vec2 positionUV = vec2(finFragmentPosition.x - finFragmentPosition.z, finFragmentPosition.y) * 0.16f;
        float first = sampleGlint(positionUV + vec2(uGlintTime * 0.010f, 0.0f)).r;
        mat2 rotate = mat2(0.7071068f, -0.7071068f, 0.7071068f, 0.7071068f);
        float second = sampleGlint((rotate * positionUV) - vec2(uGlintTime * 0.008f, 0.0f)).r;
        float intensity = clamp((first + second) * 0.65f, 0.0f, 1.0f);
        foutColor *= vec4(vec3(0.38f, 0.19f, 0.608f) * intensity, texel.a);
    } else {
        foutColor *= texel;
    }

    #ifdef FOG
    fog_set();
    #endif
}
