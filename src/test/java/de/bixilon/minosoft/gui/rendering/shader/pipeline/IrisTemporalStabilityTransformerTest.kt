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

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrisTemporalStabilityTransformerTest {
    @Test
    fun `complementary foliage receives previous wind position distance fade and alpha coverage`() {
        val transformed = IrisTemporalStabilityTransformer.transform(
            "gbuffers_terrain",
            ShaderProgramPhase.TERRAIN,
            IrisLegacyShaderTransformer.Stages(
                vertex = TERRAIN_VERTEX,
                fragment = TERRAIN_FRAGMENT,
            ),
        )

        assertContains(transformed.vertex, "minosoftWavingFrameTime = max(frameTimeCounter - frameTime, 0.0)")
        assertContains(transformed.vertex, "gbufferPreviousProjection * gbufferPreviousModelView")
        assertContains(transformed.vertex, "minosoftWavingDistanceFade(playerPos)")
        assertContains(transformed.fragment, "#define RGBA16F 34842")
        assertContains(transformed.fragment, "const int colortex14Format = RGBA16F")
        assertContains(transformed.fragment, "minosoftMipLevel")
        assertContains(transformed.fragment, "mix(0.10, 0.45, minosoftMipFactor)")
        assertContains(transformed.fragment, "floor(texCoord * minosoftTextureExtent * 4.0)")
        assertContains(transformed.fragment, "if (minosoftCoverage <= minosoftCoverageDither) discard")
        assertContains(transformed.fragment, "color.a = 1.0")
        assertContains(
            transformed.fragment,
            """
                #endif

                bool minosoftFoliageMaterial =
            """.trimIndent(),
        )
        assertContains(transformed.fragment, "/* RENDERTARGETS: 0,6,14 */")
        assertContains(transformed.fragment, "/* RENDERTARGETS: 0,6,4,14 */")
        assertContains(transformed.fragment, "gl_FragData[3] = minosoftMotion")
    }

    @Test
    fun `complementary taa rejects medium changes and disoccluded cloud history`() {
        val transformed = IrisTemporalStabilityTransformer.transform(
            "composite6",
            ShaderProgramPhase.COMPOSITE,
            IrisLegacyShaderTransformer.Stages(
                vertex = "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
                fragment = TAA_FRAGMENT,
            ),
        )

        assertContains(transformed.fragment, "uniform sampler2D colortex14")
        assertContains(transformed.fragment, "uniform sampler2D colortex15")
        assertContains(transformed.fragment, "#define R16F 33325")
        assertContains(transformed.fragment, "normalize(viewPos1.xyz) * minosoftCloudDistance")
        assertContains(transformed.fragment, "minosoftPreviousCloudDepth")
        assertContains(transformed.fragment, "bool minosoftUnderwaterFrame = isEyeInWater == 1")
        assertContains(
            transformed.fragment,
            "bool minosoftPreviousUnderwaterFrame = minosoftPreviousHistory >= 2.0",
        )
        assertContains(
            transformed.fragment,
            "minosoftUnderwaterFrame != minosoftPreviousUnderwaterFrame",
        )
        assertContains(transformed.fragment, "minosoftPreviousCloudHistory - 2.0")
        assertTrue(
            Regex(
                """minosoftRejectHistory\s*=\s*minosoftRejectHistory\s*\|\|\s*frameCounter <= 1\s*\|\|""",
            ).containsMatchIn(transformed.fragment),
        )
        assertContains(transformed.fragment, "if (minosoftRejectHistory) blendFactor = 0.0")
        assertContains(transformed.fragment, "(isEyeInWater == 1 ? 2.0 : 0.0)")
        assertContains(transformed.fragment, "/* RENDERTARGETS: 3,2,15 */")
        assertContains(transformed.fragment, "texelFetch(colortex5, texelCoord, 0).a")
        assertEquals(
            1,
            Regex("""const int colortex15Format""").findAll(transformed.fragment).count(),
        )
    }

    @Test
    fun `material 254 scene bypass strips authored projection jitter`() {
        val transformed = IrisTemporalStabilityTransformer.transform(
            "gbuffers_entities",
            ShaderProgramPhase.ENTITY,
            IrisLegacyShaderTransformer.Stages(
                vertex = """
                    #version 330 compatibility
                    void main() {
                        gl_Position = ftransform();
                        gl_Position.xy = TAAJitter(gl_Position.xy, gl_Position.w);
                    }
                """.trimIndent(),
                fragment = """
                    #version 330 compatibility
                    // No SSAO, No TAA, Reduce Reflection
                    void main() {
                        float materialMask = OSIEBCA * 254.0;
                        gl_FragData[0] = vec4(materialMask);
                    }
                """.trimIndent(),
            ),
        )

        assertContains(transformed.vertex, "minosoft:no_projection_jitter material-mask-254")
        assertFalse("gl_Position.xy = TAAJitter" in transformed.vertex)
    }

    private companion object {
        val TERRAIN_VERTEX = """
            #version 330 compatibility
            // Complementary Shaders by EminGT
            uniform float far;
            uniform float frameTime;
            uniform float frameTimeCounter;
            uniform vec3 cameraPosition;
            uniform vec3 previousCameraPosition;
            uniform mat4 gbufferModelView;
            uniform mat4 gbufferModelViewInverse;
            uniform mat4 gbufferPreviousModelView;
            uniform mat4 gbufferPreviousProjection;
            out vec3 vertexPos;
            vec3 GetRawWave(in vec3 pos, float wind) {
                return vec3(wind) + pos;
            }
            vec3 GetWave(in vec3 pos, float waveSpeed) {
                float wind = frameTimeCounter * waveSpeed;
                float windRain = frameTimeCounter * waveSpeed;
                return GetRawWave(pos, wind + windRain);
            }
            void DoWave_Foliage(inout vec3 playerPos, vec3 worldPos, float waveMult) {
                vec3 wave = GetWave(worldPos, 170.0);
                playerPos.xyz += wave * waveMult;
            }
            void DoWave_Leaves(inout vec3 playerPos, vec3 worldPos, float waveMult) {
                vec3 wave = GetWave(worldPos, 170.0);
                playerPos.xyz += wave * waveMult;
            }
            void DoWave(inout vec3 playerPos, int mat) {
                DoWave_Foliage(playerPos, playerPos, 1.0);
            }
            void main() {
                int mat = 10005;
                vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;
                vertexPos = position.xyz;

                #ifdef WAVING_ANYTHING_TERRAIN
                    DoWave(position.xyz, mat);
                #endif

                gl_Position = gl_ProjectionMatrix * gbufferModelView * position;
            }
        """.trimIndent()

        val TERRAIN_FRAGMENT = """
            #version 330 compatibility
            // Complementary Shaders by EminGT
            in vec3 vertexPos;
            in vec2 texCoord;
            flat in int mat;
            uniform sampler2D tex;
            void main() {
                #if ANISOTROPIC_FILTER == 0
                    vec4 color = texture2D(tex, texCoord);
                #else
                    vec4 color = textureAF(tex, texCoord);
                #endif

                float smoothnessD = 0.0, materialMask = 0.0;
                float skyLightFactor = 1.0;
                vec3 normalM = vec3(0.0, 1.0, 0.0);

                /* DRAWBUFFERS:06 */
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);

                #if BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0
                    /* DRAWBUFFERS:064 */
                    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);
                #endif
            }
        """.trimIndent()

        val TAA_FRAGMENT = """
            #version 330 compatibility
            // Complementary Shaders by EminGT
            #ifdef FRAGMENT_SHADER
            uniform sampler2D colortex2;
            uniform sampler2D colortex5;
            #endif
            #ifdef FRAGMENT_SHADER
            void DoTAA(inout vec3 color, inout vec3 temp, float z1) {
                int materialMask = 0;
                vec4 viewPos1 = vec4(0.0, 0.0, -1.0, 1.0);
                float lViewPos1 = length(viewPos1);
                float cloudLinearDepth = texture2D(colortex5, texCoord).a;
                vec2 prvCoord = texCoord;
                if (z1 > 0.56) prvCoord = Reprojection(viewPos1);
                #if TAA_MOVEMENT_IMPROVEMENT_FILTER == 1
                    vec3 tempColor = textureCatmullRom(colortex2, prvCoord, view);
                #else
                    vec3 tempColor = texture2D(colortex2, prvCoord).rgb;
                #endif
                vec2 velocity = (texCoord - prvCoord.xy) * view;
                float blendFactor = 1.0;
                float velocityFactor = dot(velocity, velocity) * 10.0;
                blendFactor *= max(exp(-velocityFactor) * blendVariable, blendMinimum);
                color = mix(color, tempColor, blendFactor);
                temp = color;
            }
            void main() {
                vec3 color = vec3(1.0);
                vec3 temp = vec3(0.0);
                DoTAA(color, temp, 1.0);
                /* DRAWBUFFERS:32 */
                gl_FragData[0] = vec4(color, 1.0);
                gl_FragData[1] = vec4(temp, 1.0);
            }
            #endif
        """.trimIndent()
    }
}
