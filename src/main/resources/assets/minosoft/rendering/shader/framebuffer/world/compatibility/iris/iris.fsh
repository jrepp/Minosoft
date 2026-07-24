/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

#version 330 core

in vec2 finUV;

out lowp vec4 foutColor;

uniform sampler2D uTexture;

#include "minosoft:alpha"

/*
 * A deliberately visible compatibility presentation: lifted cool shadows,
 * warmer highlights, stronger local contrast, and a restrained vignette.
 * This is Minosoft's source-native Iris adapter pass, not an OptiFine-format
 * shader-pack program.
 */
void main() {
    foutColor = texture(uTexture, finUV);
    discard_alpha();

    vec3 source = foutColor.rgb;
    float luminance = dot(source, vec3(0.2126f, 0.7152f, 0.0722f));
    vec3 graded = mix(vec3(luminance), source, 1.30f);
    graded = pow(max(graded, vec3(0.0f)), vec3(0.82f));
    graded = (graded - 0.5f) * 1.12f + 0.5f;

    float shadows = 1.0f - smoothstep(0.08f, 0.62f, luminance);
    float highlights = smoothstep(0.28f, 0.92f, luminance);
    graded += shadows * vec3(0.012f, 0.052f, 0.090f);
    graded += highlights * vec3(0.075f, 0.030f, -0.024f);

    vec2 centered = finUV - vec2(0.5f);
    float vignette = 1.0f - 0.28f * smoothstep(0.20f, 0.70f, length(centered));
    foutColor.rgb = clamp(graded * vignette, 0.0f, 1.0f);
}
