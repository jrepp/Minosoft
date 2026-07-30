/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

layout (points) in;
layout (triangle_strip, max_vertices = 4) out;

uniform mat4 uViewProjectionMatrix;
uniform vec3 uCameraRight;
uniform vec3 uCameraUp;

out vec4 finTintColor;
out vec3 finFragmentPosition;

in Vertex {
    vec2 minUV;
    vec2 maxUV;
    flat uint array;
    flat float layer;
    float scale;
    vec4 tintColor;
} ginVertex[];

#include "minosoft:tint"
#include "minosoft:animation"

void emitParticleVertex(vec3 offset, vec2 uv) {
    vec3 pointPosition = gl_in[0].gl_Position.xyz;
    finFragmentPosition = pointPosition + (offset * ginVertex[0].scale);
    gl_Position = uViewProjectionMatrix * vec4(finFragmentPosition, 1.0);
    finTextureUV = uv;
    EmitVertex();
}

void main() {
    finTintColor = ginVertex[0].tintColor;
    finTextureArray = ginVertex[0].array;
    finTextureLayer = ginVertex[0].layer;
    emitParticleVertex(-(uCameraRight - uCameraUp), vec2(ginVertex[0].minUV.x, ginVertex[0].minUV.y));
    emitParticleVertex(-(uCameraRight + uCameraUp), vec2(ginVertex[0].minUV.x, ginVertex[0].maxUV.y));
    emitParticleVertex(uCameraRight + uCameraUp, vec2(ginVertex[0].maxUV.x, ginVertex[0].minUV.y));
    emitParticleVertex(uCameraRight - uCameraUp, vec2(ginVertex[0].maxUV.x, ginVertex[0].maxUV.y));
    EndPrimitive();
}
