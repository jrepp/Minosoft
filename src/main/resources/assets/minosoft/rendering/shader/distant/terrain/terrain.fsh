/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

#version 330 core

in vec4 finColor;
in float finHorizontalDistance;
flat in uint finSurfaceFlags;
out vec4 foutColor;

uniform bool uDistantFogEnabled;
uniform bool uDistantWater;
uniform vec4 uDistantFogColor;
uniform float uDistantNearFadeStart;
uniform float uDistantNearFadeEnd;
uniform float uDistantFarFogStart;
uniform float uDistantFarFogEnd;

void main() {
    if ((finSurfaceFlags & 0x1u) != 0u) discard;
    foutColor = finColor;
    if (uDistantWater) {
        foutColor.a *= smoothstep(uDistantNearFadeStart, uDistantNearFadeEnd, finHorizontalDistance);
    } else if (finHorizontalDistance < uDistantNearFadeStart) {
        // CPU coverage ownership is page-granular. Its partial-page refinement can remain
        // conservative while children are unavailable, so enforce the native seam at fragment
        // precision instead of allowing a coarse solid page to project through nearby terrain.
        discard;
    }
    if (!uDistantFogEnabled) return;

    float farFog = smoothstep(uDistantFarFogStart, uDistantFarFogEnd, finHorizontalDistance);
    foutColor.rgb = mix(foutColor.rgb, uDistantFogColor.rgb, farFog);
    foutColor.a = mix(foutColor.a, 1.0, farFog);
}
