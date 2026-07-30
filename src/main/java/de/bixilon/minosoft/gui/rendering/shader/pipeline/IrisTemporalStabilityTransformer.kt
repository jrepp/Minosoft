/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

/**
 * Adds narrowly detected temporal-stability contracts to resolved shader-pack
 * sources before the fixed-function compatibility bridge is applied.
 *
 * Every transform requires the complete authored source pattern it replaces.
 * Unrelated packs and newer pack revisions therefore remain unchanged instead
 * of receiving a partially compatible motion/history ABI.
 */
internal object IrisTemporalStabilityTransformer {
    private const val MOTION_BUFFER = 14
    private const val CLOUD_HISTORY_BUFFER = 15
    private const val FOLIAGE_CONDITION = "defined(WAVING_FOLIAGE) || defined(WAVING_LEAVES)"

    fun transform(
        name: String,
        phase: ShaderProgramPhase,
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        if (phase == ShaderProgramPhase.TERRAIN && name.startsWith("gbuffers_terrain")) {
            return stabilizeWavingTerrain(stages)
        }
        if (phase == ShaderProgramPhase.COMPOSITE && name == "composite6") {
            return stabilizeCloudHistory(stages)
        }
        if (
            phase == ShaderProgramPhase.ENTITY ||
            phase == ShaderProgramPhase.HAND
        ) {
            return suppressJitterForNoTaaMaterial(stages)
        }
        return stages
    }

    private fun stabilizeWavingTerrain(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        val vertex = normalizedLines(stages.vertex)
        val fragment = normalizedLines(stages.fragment)
        if (
            "Complementary Shaders by EminGT" !in vertex ||
            "void DoWave_Foliage" !in vertex ||
            "void DoWave_Leaves" !in vertex ||
            "DoWave(position.xyz, mat);" !in vertex ||
            "Complementary Shaders by EminGT" !in fragment ||
            "/* DRAWBUFFERS:06 */" !in fragment ||
            "/* DRAWBUFFERS:064 */" !in fragment
        ) {
            return stages
        }
        require("colortex$MOTION_BUFFER" !in vertex && "colortex$MOTION_BUFFER" !in fragment) {
            "Shader pack already owns colortex$MOTION_BUFFER; foliage motion vectors require a free buffer"
        }

        var stableVertex = vertex.replace(
            "vec3 GetRawWave(in vec3 pos, float wind) {",
            """
                float minosoftWavingFrameTime;

                float minosoftWavingDistanceFade(vec3 playerPos) {
                    float fadeStart = max(far * 0.35, 32.0);
                    float fadeEnd = max(far * 0.75, fadeStart + 16.0);
                    return 1.0 - smoothstep(fadeStart, fadeEnd, length(playerPos));
                }

                vec3 GetRawWave(in vec3 pos, float wind) {
            """.trimIndent(),
        )
        stableVertex = stableVertex
            .replace("frameTimeCounter * waveSpeed", "minosoftWavingFrameTime * waveSpeed")
            .replace(
                "playerPos.xyz += wave * waveMult;",
                "playerPos.xyz += wave * waveMult * minosoftWavingDistanceFade(playerPos);",
            )
            .replace(
                "out vec3 vertexPos;",
                """
                    out vec3 vertexPos;
                    #if $FOLIAGE_CONDITION
                        out vec4 minosoftPreviousClipPosition;
                    #endif
                """.trimIndent(),
            )

        val positionBlock = """
            vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;
                vertexPos = position.xyz;

                #ifdef WAVING_ANYTHING_TERRAIN
                    DoWave(position.xyz, mat);
                #endif

                gl_Position = gl_ProjectionMatrix * gbufferModelView * position;
        """.trimIndent()
        require(positionBlock in stableVertex) {
            "Complementary terrain waving source changed before previous-position injection"
        }
        stableVertex = stableVertex.replace(
            positionBlock,
            """
                vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;
                vertexPos = position.xyz;

                #if $FOLIAGE_CONDITION
                    vec4 minosoftPreviousPosition = position;
                    minosoftWavingFrameTime = max(frameTimeCounter - frameTime, 0.0);
                    DoWave(minosoftPreviousPosition.xyz, mat);
                    minosoftPreviousPosition.xyz += cameraPosition - previousCameraPosition;
                    minosoftPreviousClipPosition =
                        gbufferPreviousProjection * gbufferPreviousModelView * minosoftPreviousPosition;
                #endif

                #ifdef WAVING_ANYTHING_TERRAIN
                    minosoftWavingFrameTime = frameTimeCounter;
                    DoWave(position.xyz, mat);
                #endif

                gl_Position = gl_ProjectionMatrix * gbufferModelView * position;
            """.trimIndent(),
        )

        var stableFragment = fragment.replace(
            "in vec3 vertexPos;",
            """
                in vec3 vertexPos;
                #if $FOLIAGE_CONDITION
                    in vec4 minosoftPreviousClipPosition;
                    #ifndef RGBA16F
                        #define RGBA16F 34842
                    #endif
                    const int colortex${MOTION_BUFFER}Format = RGBA16F;
                    const bool colortex${MOTION_BUFFER}Clear = true;
                    const vec4 colortex${MOTION_BUFFER}ClearColor =
                        vec4(0.0, 0.0, 0.0, 0.0);
                #endif
            """.trimIndent(),
        )
        val sampledColor = """
            #if ANISOTROPIC_FILTER == 0
                    vec4 color = texture2D(tex, texCoord);
                #else
                    vec4 color = textureAF(tex, texCoord);
                #endif

                float smoothnessD = 0.0, materialMask = 0.0;
        """.trimIndent()
        require(sampledColor in stableFragment) {
            "Complementary terrain sampling source changed before alpha-coverage injection"
        }
        stableFragment = stableFragment.replace(
            sampledColor,
            """
                #if ANISOTROPIC_FILTER == 0
                    vec4 color = texture2D(tex, texCoord);
                #else
                    vec4 color = textureAF(tex, texCoord);
                #endif

                bool minosoftFoliageMaterial =
                    mat == 10005 || mat == 10021 || mat == 10009 || mat == 10013 ||
                    mat == 10769 || mat == 10972 || mat == 10976;
                if (minosoftFoliageMaterial) {
                    vec2 minosoftTextureExtent = vec2(textureSize(tex, 0));
                    float minosoftFootprint = max(
                        length(dFdx(texCoord * minosoftTextureExtent)),
                        length(dFdy(texCoord * minosoftTextureExtent))
                    );
                    float minosoftMipLevel = max(log2(max(minosoftFootprint, 1.0)), 0.0);
                    float minosoftMipFactor =
                        clamp(minosoftMipLevel * 0.25, 0.0, 1.0);
                    float minosoftCoverageThreshold =
                        mix(0.10, 0.45, minosoftMipFactor);
                    float minosoftCoverageWidth = clamp(
                        fwidth(color.a),
                        0.01,
                        0.08
                    );
                    float minosoftCoverage = smoothstep(
                        minosoftCoverageThreshold - minosoftCoverageWidth,
                        minosoftCoverageThreshold + minosoftCoverageWidth,
                        color.a
                    );
                    vec2 minosoftCoverageCell =
                        floor(texCoord * minosoftTextureExtent * 4.0);
                    float minosoftCoverageDither = fract(
                        52.9829189 * fract(
                            dot(minosoftCoverageCell, vec2(0.06711056, 0.00583715))
                        )
                    );
                    if (minosoftCoverage <= minosoftCoverageDither) discard;
                    color.a = 1.0;
                }

                float smoothnessD = 0.0, materialMask = 0.0;
            """.trimIndent(),
        )

        val outputBlock = """
            /* DRAWBUFFERS:06 */
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);

                #if BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0
                    /* DRAWBUFFERS:064 */
                    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);
                #endif
        """.trimIndent()
        require(outputBlock in stableFragment) {
            "Complementary terrain outputs changed before motion-vector injection"
        }
        stableFragment = stableFragment.replace(
            outputBlock,
            """
                #if $FOLIAGE_CONDITION
                    /* RENDERTARGETS: 0,6,$MOTION_BUFFER */
                #else
                    /* DRAWBUFFERS:06 */
                #endif
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);

                #if $FOLIAGE_CONDITION
                    bool minosoftWavingMaterial =
                        mat == 10005 || mat == 10021 || mat == 10009 || mat == 10013 ||
                        mat == 10769 || mat == 10972 || mat == 10976;
                    float minosoftPreviousW = minosoftPreviousClipPosition.w;
                    vec2 minosoftPreviousUv =
                        minosoftPreviousClipPosition.xy / max(abs(minosoftPreviousW), 0.000001) *
                        sign(minosoftPreviousW) * 0.5 + 0.5;
                    vec4 minosoftMotion = vec4(
                        minosoftPreviousUv,
                        0.0,
                        minosoftWavingMaterial && abs(minosoftPreviousW) > 0.000001 ? 1.0 : 0.0
                    );
                #endif

                #if BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0
                    #if $FOLIAGE_CONDITION
                        /* RENDERTARGETS: 0,6,4,$MOTION_BUFFER */
                    #else
                        /* DRAWBUFFERS:064 */
                    #endif
                    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);
                    #if $FOLIAGE_CONDITION
                        gl_FragData[3] = minosoftMotion;
                    #endif
                #elif $FOLIAGE_CONDITION
                    gl_FragData[2] = minosoftMotion;
                #endif
            """.trimIndent(),
        )
        return stages.copy(vertex = stableVertex, fragment = stableFragment)
    }

    private fun stabilizeCloudHistory(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        val fragment = normalizedLines(stages.fragment)
        if (
            "Complementary Shaders by EminGT" !in fragment ||
            "void DoTAA(inout vec3 color, inout vec3 temp, float z1)" !in fragment ||
            "float cloudLinearDepth = texture2D(colortex5, texCoord).a;" !in fragment ||
            "/* DRAWBUFFERS:32 */" !in fragment
        ) {
            return stages
        }
        require("colortex$CLOUD_HISTORY_BUFFER" !in fragment) {
            "Shader pack already owns colortex$CLOUD_HISTORY_BUFFER; cloud history requires a free buffer"
        }

        var stable = fragment.replaceFirst(
            "#ifdef FRAGMENT_SHADER",
            """
            #ifdef FRAGMENT_SHADER

                uniform sampler2D colortex$MOTION_BUFFER;
                uniform sampler2D colortex$CLOUD_HISTORY_BUFFER;
                #ifndef R16F
                    #define R16F 33325
                #endif
                const int colortex${CLOUD_HISTORY_BUFFER}Format = R16F;
                const bool colortex${CLOUD_HISTORY_BUFFER}Clear = false;
            """.trimIndent(),
        )
        stable = stable.replace(
            "float lViewPos1 = length(viewPos1);",
            """
                float lViewPos1 = length(viewPos1);
                float minosoftCloudLinearDepth = texture2D(colortex5, texCoord).a;
                float minosoftCloudDistance =
                    minosoftCloudLinearDepth * minosoftCloudLinearDepth * renderDistance;
                bool minosoftCloudPixel =
                    minosoftCloudLinearDepth > 0.0 &&
                    minosoftCloudLinearDepth < 1.0 &&
                    minosoftCloudDistance < min(lViewPos1, renderDistance);
            """.trimIndent(),
        )
        stable = stable.replace(
            "void DoTAA(inout vec3 color, inout vec3 temp, float z1) {",
            """
                void DoTAA(inout vec3 color, inout vec3 temp, float z1) {
                    bool minosoftUnderwaterFrame = isEyeInWater == 1;
                    float minosoftPreviousHistory =
                        texelFetch(colortex$CLOUD_HISTORY_BUFFER, texelCoord, 0).r;
                    bool minosoftPreviousUnderwaterFrame = minosoftPreviousHistory >= 2.0;
                    bool minosoftRejectHistory =
                        frameCounter <= 1 ||
                        minosoftUnderwaterFrame != minosoftPreviousUnderwaterFrame;
            """.trimIndent(),
        )
        val samplingAnchor = """
            #if TAA_MOVEMENT_IMPROVEMENT_FILTER == 1
                    vec3 tempColor = textureCatmullRom(colortex2, prvCoord, view);
        """.trimIndent()
        require(samplingAnchor in stable) {
            "Complementary TAA sampling source changed before temporal reprojection injection"
        }
        stable = stable.replace(
            samplingAnchor,
            """
                vec4 minosoftMotion = texture2D(colortex$MOTION_BUFFER, texCoord);
                if (minosoftMotion.w > 0.5) {
                    prvCoord = minosoftMotion.xy;
                } else if (minosoftCloudPixel) {
                    vec3 minosoftCloudViewPosition =
                        normalize(viewPos1.xyz) * minosoftCloudDistance;
                    prvCoord = Reprojection(vec4(minosoftCloudViewPosition, 1.0));
                    bool minosoftHistoryInBounds =
                        prvCoord.x > 0.0 && prvCoord.x < 1.0 &&
                        prvCoord.y > 0.0 && prvCoord.y < 1.0;
                    float minosoftPreviousCloudHistory = minosoftHistoryInBounds
                        ? texture2D(colortex$CLOUD_HISTORY_BUFFER, prvCoord).r
                        : 0.0;
                    float minosoftPreviousCloudDepth =
                        minosoftPreviousCloudHistory >= 2.0
                            ? minosoftPreviousCloudHistory - 2.0
                            : minosoftPreviousCloudHistory;
                    float minosoftPreviousCloudDistance =
                        minosoftPreviousCloudDepth * minosoftPreviousCloudDepth * renderDistance;
                    float minosoftCloudDepthTolerance = max(8.0, minosoftCloudDistance * 0.05);
                    minosoftRejectHistory =
                        minosoftRejectHistory ||
                        frameCounter <= 1 ||
                        minosoftPreviousCloudDepth <= 0.0 ||
                        minosoftPreviousCloudDepth >= 1.0 ||
                        abs(minosoftPreviousCloudDistance - minosoftCloudDistance) >
                            minosoftCloudDepthTolerance;
                }

                #if TAA_MOVEMENT_IMPROVEMENT_FILTER == 1
                    vec3 tempColor = textureCatmullRom(colortex2, prvCoord, view);
            """.trimIndent(),
        )
        stable = stable.replace(
            "blendFactor *= max(exp(-velocityFactor)",
            """
                if (minosoftRejectHistory) blendFactor = 0.0;
                blendFactor *= max(exp(-velocityFactor)
            """.trimIndent(),
        )
        val outputBlock = """
            /* DRAWBUFFERS:32 */
                gl_FragData[0] = vec4(color, 1.0);
                gl_FragData[1] = vec4(temp, 1.0);
        """.trimIndent()
        require(outputBlock in stable) {
            "Complementary TAA outputs changed before cloud-history injection"
        }
        stable = stable.replace(
            outputBlock,
            """
                /* RENDERTARGETS: 3,2,$CLOUD_HISTORY_BUFFER */
                gl_FragData[0] = vec4(color, 1.0);
                gl_FragData[1] = vec4(temp, 1.0);
                float minosoftCurrentHistory =
                    texelFetch(colortex5, texelCoord, 0).a +
                    (isEyeInWater == 1 ? 2.0 : 0.0);
                gl_FragData[2] = vec4(minosoftCurrentHistory, 0.0, 0.0, 1.0);
            """.trimIndent(),
        )
        return stages.copy(fragment = stable)
    }

    private fun suppressJitterForNoTaaMaterial(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        if (
            "254.0" !in stages.fragment ||
            ("No TAA" !in stages.fragment && "materialMask" !in stages.fragment)
        ) {
            return stages
        }
        val jitteredProjection = Regex(
            """(?m)^[ \t]*gl_Position\.xy\s*=\s*TAAJitter\s*\([^;\r\n]+;\s*$""",
        )
        if (!jitteredProjection.containsMatchIn(stages.vertex)) return stages
        return stages.copy(
            vertex = jitteredProjection.replace(
                stages.vertex,
                "    // minosoft:no_projection_jitter material-mask-254",
            ),
        )
    }

    private fun normalizedLines(source: String): String =
        source.replace("\r\n", "\n").replace('\r', '\n')
}
