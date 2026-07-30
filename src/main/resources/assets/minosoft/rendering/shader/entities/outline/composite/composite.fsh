/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

#version 330 core

in vec2 finUV;

out lowp vec4 foutColor;

uniform sampler2D uTexture;
uniform vec2 uTexelSize;

void main() {
    if (texture(uTexture, finUV).a > 0.0f) {
        discard;
    }

    vec4 outline = vec4(0.0f);
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) {
                continue;
            }
            vec4 candidate = texture(uTexture, finUV + vec2(x, y) * uTexelSize);
            if (candidate.a > outline.a) {
                outline = candidate;
            }
        }
    }
    if (outline.a <= 0.0f) {
        discard;
    }
    foutColor = outline;
}
