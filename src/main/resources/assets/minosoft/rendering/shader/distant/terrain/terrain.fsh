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
in float finDistance;
flat in uint finSurfaceFlags;
out vec4 foutColor;

uniform bool uDistantFogEnabled;
uniform bool uDistantEnvironmentFog;
uniform bool uDistantWater;
uniform vec4 uDistantFogColor;
uniform float uDistantNearFadeStart;
uniform float uDistantNearFadeEnd;
uniform float uDistantFarFogStart;
uniform float uDistantFarFogEnd;

const uint DISTANT_SURFACE_BASE_PAGE = 0x2u;

void main() {
    if ((finSurfaceFlags & 0x1u) != 0u) discard;
    foutColor = finColor;
    bool basePage = (finSurfaceFlags & DISTANT_SURFACE_BASE_PAGE) != 0u;
    if (uDistantWater) {
        if (!basePage) {
            foutColor.a *= smoothstep(uDistantNearFadeStart, uDistantNearFadeEnd, finHorizontalDistance);
        }
    } else if (
        !basePage &&
        finHorizontalDistance < uDistantNearFadeStart
    ) {
        // CPU coverage ownership is page-granular. Its partial-page refinement can remain
        // conservative while children are unavailable, so retain the radial overlap guard for
        // coarse pages. Exact base pages are already masked against native ownership on the CPU.
        discard;
    }
    if (!uDistantFogEnabled) return;

    float fogDistance = uDistantEnvironmentFog ? finDistance : finHorizontalDistance;
    float farFog = smoothstep(uDistantFarFogStart, uDistantFarFogEnd, fogDistance);
    foutColor.rgb = mix(foutColor.rgb, uDistantFogColor.rgb, farFog);
    foutColor.a = mix(foutColor.a, 1.0, farFog);
}
