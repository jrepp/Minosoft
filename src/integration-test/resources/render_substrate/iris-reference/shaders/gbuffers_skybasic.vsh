/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// minosoft:scene_bridge SKY_POSITION SKY_COLOR uSkyViewProjectionMatrix,uSkyColor
// minosoft:scene_bridge SUN_SCATTER SUN_SCATTER uScatterMatrix,uSunPosition,uIntensity

#version 330 core
uniform int renderStage;

layout (location = 0) in vec3 vinPosition;
#ifdef MINOSOFT_STATE_ABI_SUN_SCATTER
uniform mat4 uScatterMatrix;
out vec3 finFragmentPosition;
#else
uniform mat4 uSkyViewProjectionMatrix;
#endif

void main() {
#ifdef MINOSOFT_STATE_ABI_SUN_SCATTER
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uScatterMatrix * vec4(vinPosition, 1.0);
    finFragmentPosition = vinPosition;
#else
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : uSkyViewProjectionMatrix * vec4(vinPosition, 1.0);
#endif
}
