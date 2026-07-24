/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

#ifndef PLAYER_LIGHT
#define PLAYER_LIGHT

uniform vec3 uPlayerLightPosition;
uniform float uPlayerLightIntensity;
uniform float uPlayerLightRadius;

float playerLightContribution(vec3 position) {
    float distanceRatio = clamp(distance(position, uPlayerLightPosition) / max(uPlayerLightRadius, 0.001f), 0.0f, 1.0f);
    float falloff = 1.0f - distanceRatio;
    return uPlayerLightIntensity * falloff * falloff;
}

lowp vec3 applyPlayerLight(lowp vec3 light, vec3 position) {
    return max(light, vec3(playerLightContribution(position)));
}

#endif
