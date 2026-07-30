/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

/* RENDERTARGETS: 0 */

out lowp vec4 foutColor;
#ifdef MINOSOFT_STATE_ABI_SUN_SCATTER
in vec3 finFragmentPosition;
uniform float uIntensity;
uniform vec3 uSunPosition;

#define PI 3.1415926535897932384626433832795
#define CLOSE_DISTANCE 0.18
#define MAX_DISTANCE 2.1
#define END_DISTANCE MAX_DISTANCE - 0.2
#define YELLOWISH vec3(1.0, 0.6, 0.15)
#define REDISH vec3(1.0, 0.4, 0.05)
#else
uniform vec4 uSkyColor;
#endif

void main() {
#ifdef MINOSOFT_STATE_ABI_SUN_SCATTER
    foutColor = vec4(REDISH, uIntensity);
    float distanceToSun = length(uSunPosition - finFragmentPosition);
    float distanceMultiplier;
    if (distanceToSun < CLOSE_DISTANCE) {
        distanceMultiplier = (1.0 - distanceToSun / CLOSE_DISTANCE) / 2.0 + 0.5;
    } else if (distanceToSun > END_DISTANCE) {
        float modifier = (distanceToSun - END_DISTANCE) / (MAX_DISTANCE - END_DISTANCE) / 2.0;
        distanceMultiplier = modifier * modifier * 0.3;
    } else {
        distanceMultiplier = (1.0 - (distanceToSun - CLOSE_DISTANCE) / (END_DISTANCE - CLOSE_DISTANCE)) * 0.2 + 0.3;
    }
    foutColor.a *= distanceMultiplier;
    float yDistance = abs(finFragmentPosition.y * 5.0);
    if (yDistance > 0.8) {
        foutColor.a *= (1.0 - yDistance) * 5.0;
        foutColor.rgb = YELLOWISH;
    } else if (yDistance > 0.4) {
        float scaled = (yDistance - 0.4) * 2.5;
        foutColor.rgb = mix(mix(foutColor.rgb, YELLOWISH, 0.1), YELLOWISH, scaled);
    } else if (yDistance > 0.3) {
        float scaled = (yDistance - 0.3) * 10.0;
        foutColor.rgb = mix(foutColor.rgb, mix(foutColor.rgb, YELLOWISH, 0.1), scaled * scaled);
    }
#else
    foutColor = uSkyColor;
#endif
}
