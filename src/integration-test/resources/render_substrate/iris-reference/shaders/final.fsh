/*
 * Minosoft Iris reference shader pack
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

const float sunPathRotation = 18.0;
const float wetnessHalflife = 40.0;
const float drynessHalflife = 12.0;
const float eyeBrightnessHalflife = 4.0;
const int noiseTextureResolution = 64;

in vec2 finUV;
out vec4 foutColor;
uniform sampler2D colortex0;
uniform sampler2D noisetex;
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;
uniform float rainStrength;
uniform int renderStage;
uniform int fogMode;
uniform int fogShape;
uniform float fogDensity;
uniform float fogStart;
uniform float fogEnd;
uniform vec3 fogColor;
uniform float iris_FogDensity;
uniform float iris_FogStart;
uniform float iris_FogEnd;
uniform vec4 iris_FogColor;
uniform ivec3 cameraPositionInt;
uniform vec3 cameraPositionFract;
uniform ivec3 previousCameraPositionInt;
uniform vec3 previousCameraPositionFract;
uniform vec3 sunPosition;
uniform vec3 moonPosition;
uniform float shadowAngle;
uniform vec3 shadowLightPosition;
uniform vec3 upPosition;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferPreviousProjection;
uniform ivec3 currentDate;
uniform ivec3 currentTime;
uniform ivec2 currentYearTime;
uniform int isEyeInWater;
uniform bool is_sneaking;
uniform bool is_sprinting;
uniform bool is_hurt;
uniform bool is_invisible;
uniform bool is_burning;
uniform bool is_on_ground;
uniform bool hideGUI;
uniform bool isRightHanded;
uniform float currentPlayerHealth;
uniform float maxPlayerHealth;
uniform float currentPlayerHunger;
uniform float maxPlayerHunger;
uniform float currentPlayerArmor;
uniform float maxPlayerArmor;
uniform float currentPlayerAir;
uniform float maxPlayerAir;
uniform bool firstPersonCamera;
uniform bool isSpectator;
uniform float blindness;
uniform float darknessFactor;
uniform float darknessLightFactor;
uniform float nightVision;
uniform float playerMood;
uniform float constantMood;
uniform int biome;
uniform int biome_category;
uniform int biome_precipitation;
uniform float rainfall;
uniform float temperature;
uniform ivec2 eyeBrightness;
uniform ivec2 eyeBrightnessSmooth;
uniform vec3 skyColor;
uniform float pi;
uniform float wetness;
uniform vec3 playerLookVector;
uniform vec3 playerBodyVector;
uniform int bedrockLevel;
uniform float cloudHeight;
uniform int heightLimit;
uniform int logicalHeightLimit;
uniform bool hasCeiling;
uniform bool hasSkylight;
uniform float ambientLight;
uniform float cloudTime;
uniform int currentColorSpace;
uniform int currentSelectedBlockId;
uniform vec3 currentSelectedBlockPos;

void main() {
    vec4 source = texture(colortex0, finUV);
    if (
        renderStage < 0 || fogMode < 0 || fogShape < 0 ||
        fogDensity < 0.0 || iris_FogDensity < 0.0 ||
        fogEnd < fogStart || iris_FogEnd < iris_FogStart ||
        cameraPositionInt.x == -2147483648 ||
        previousCameraPositionInt.x == -2147483648 ||
        shadowAngle < 0.0 ||
        length(sunPosition + moonPosition) > 0.01 ||
        length(shadowLightPosition) < 1.0 ||
        length(upPosition) < 1.0 ||
        gbufferPreviousModelView[3][3] < 0.0 ||
        gbufferPreviousProjection[3][3] < 0.0 ||
        currentDate.x < 0 ||
        currentTime.x < 0 ||
        currentYearTime.x < 0 ||
        isEyeInWater < 0 ||
        currentPlayerHealth < -2.0 ||
        maxPlayerHealth < -2.0 ||
        currentPlayerHunger < -2.0 ||
        maxPlayerHunger < -2.0 ||
        currentPlayerArmor < -2.0 ||
        maxPlayerArmor < -2.0 ||
        currentPlayerAir < -2.0 ||
        maxPlayerAir < -2.0 ||
        blindness < 0.0 || blindness > 1.0 ||
        darknessFactor < 0.0 || darknessFactor > 1.0 ||
        darknessLightFactor < 0.0 || darknessLightFactor > 1.0 ||
        nightVision < 0.0 || nightVision > 1.0 ||
        playerMood < 0.0 || playerMood > 1.0 ||
        constantMood < 0.0 || constantMood > 1.0 ||
        biome < 0 ||
        biome_category < 0 ||
        biome_precipitation < 0 || biome_precipitation > 2 ||
        rainfall < 0.0 ||
        temperature < -10.0 ||
        (firstPersonCamera && isSpectator) ||
        eyeBrightness.x < 0 ||
        eyeBrightnessSmooth.x < 0 ||
        length(skyColor) < -1.0 ||
        pi < 3.0 ||
        wetness < 0.0 || wetness > 1.0 ||
        length(playerLookVector) < 0.5 ||
        length(playerBodyVector) < 0.5 ||
        heightLimit <= 0 ||
        logicalHeightLimit <= 0 || logicalHeightLimit > heightLimit ||
        cloudHeight < float(bedrockLevel) ||
        ambientLight < 0.0 || ambientLight > 1.0 ||
        (hasCeiling && hasSkylight) ||
        cloudTime < 0.0 ||
        currentColorSpace < 0 || currentColorSpace > 4 ||
        currentSelectedBlockId < -32768 ||
        currentSelectedBlockPos.x < -300.0 ||
        (hideGUI && isRightHanded) ||
        (is_sneaking && is_sprinting && is_hurt && is_invisible && is_burning && is_on_ground)
    ) {
        source.rgb =
            fogColor + iris_FogColor.rgb +
            cameraPositionFract + previousCameraPositionFract;
    }
    vec2 pixel = gl_FragCoord.xy / vec2(viewWidth, viewHeight);
    float dither = fract(sin(dot(pixel + frameTimeCounter, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    float irisNoise = texture(noisetex, finUV).r - 0.5;
    vec3 coolShadows = vec3(0.012, 0.025, 0.055) * (1.0 - source.rgb) * (1.0 + rainStrength * 0.15);
    vec3 graded = pow(source.rgb, vec3(0.92)) + coolShadows + vec3(dither / 1024.0 + irisNoise / 1048576.0);
    foutColor = vec4(clamp(graded, 0.0, 1.0), source.a);
}
