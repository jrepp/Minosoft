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

import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrisLegacyShaderTransformerTest {
    @Test
    fun `material selector rewrite does not capture unrelated sampler parameters`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_textured",
            ShaderProgramPhase.ENTITY,
            """
                #version 120
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D texture;
                vec4 texture2D_POMSwitch(sampler2D sampler, vec2 uv, vec4 derivatives) {
                    return texture2DGradARB(sampler, uv, derivatives.xy, derivatives.zw);
                }
                vec3 skyFromTex(vec2 uv, sampler2D sampler) {
                    return texture2D(sampler, uv).rgb;
                }
                void main() {
                    gl_FragData[0] = texture2D_POMSwitch(
                        texture,
                        vec2(0.5),
                        vec4(0.0)
                    ) + vec4(skyFromTex(vec2(0.5), texture), 0.0);
                }
            """.trimIndent(),
        )

        assertContains(transformed.fragment, "texture2D_POMSwitch(int minosoftSampler,")
        assertContains(
            transformed.fragment,
            "minosoftSampleSelectedMaterialGrad(minosoftSampler,",
        )
        assertContains(transformed.fragment, "vec3 skyFromTex(vec2 uv, sampler2D sampler)")
        assertContains(transformed.fragment, "return texture(sampler, uv).rgb;")
    }

    @Test
    fun `distant fixed function programs use the compact normal material ABI`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "dh_terrain",
            ShaderProgramPhase.DISTANT_HORIZONS,
            """
                #version 330 compatibility
                varying vec4 color;
                attribute vec4 at_tangent;
                uniform sampler2D normals;
                uniform sampler2D specular;
                void main() {
                    color = gl_Color;
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    if (dhMaterialId == 12) gl_Position.x += gl_Normal.x;
                    gl_Position.xy += texture(normals, vec2(0.5)).xy * 0.0;
                    gl_Position.xy += texture(specular, vec2(0.5)).xy * 0.0;
                }
            """.trimIndent(),
            """
                #version 330 compatibility
                varying vec4 color;
                uniform sampler2D normals;
                uniform sampler2D specular;
                void main() {
                    gl_FragData[0] = color +
                        texture(normals, vec2(0.5)) * 0.0 +
                        texture(specular, vec2(0.5)) * 0.0;
                }
            """.trimIndent(),
        )

        assertContains(
            transformed.vertex,
            "// minosoft:scene_bridge DISTANT_TERRAIN DISTANT_TERRAIN uViewProjectionMatrix,uPageOffset",
        )
        assertContains(transformed.vertex, "layout (location = 3) in float vinNormalMaterial;")
        assertContains(transformed.vertex, "uniform mat4 dhProjection;")
        assertContains(transformed.vertex, "uniform vec3 uPageOffset;")
        assertContains(transformed.vertex, "vec4(vinPosition + uPageOffset, 1.0)")
        assertFalse("gl_Vertex" in transformed.vertex)
        assertFalse("dhMaterialId" in transformed.vertex)
        assertContains(transformed.vertex, "in vec4 at_tangent;")
        assertFalse("uniform sampler2D normals" in transformed.vertex)
        assertFalse("uniform sampler2D specular" in transformed.vertex)
        val compactVertex = transformed.vertex.replace(" ", "")
        assertContains(compactVertex, "minosoftNeutralNormal(vec2(0.5))")
        assertContains(compactVertex, "minosoftNeutralSpecular(vec2(0.5))")
        assertContains(transformed.fragment, "layout (location = 0) out vec4 minosoftFragmentColor0;")
        assertFalse("uniform sampler2D normals" in transformed.fragment)
        assertFalse("uniform sampler2D specular" in transformed.fragment)
        val compactFragment = transformed.fragment.replace(" ", "")
        assertContains(compactFragment, "minosoftNeutralNormal(vec2(0.5))")
        assertContains(compactFragment, "minosoftNeutralSpecular(vec2(0.5))")
    }

    @Test
    fun `tessellation stages ferry host terrain varyings and fixed matrices`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_terrain",
            ShaderProgramPhase.TERRAIN,
            """
                #version 120
                varying vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                }
            """.trimIndent(),
            """
                #version 120
                varying vec2 uv;
                uniform sampler2D texture;
                uniform sampler2D lightmap;
                void main() {
                    gl_FragData[0] = texture2D(texture, uv);
                }
            """.trimIndent(),
            """
                #version 400 compatibility
                layout(vertices = 3) out;
                void main() {
                    gl_out[gl_InvocationID].gl_Position =
                        gl_ModelViewMatrix * gl_in[gl_InvocationID].gl_Position;
                    gl_TessLevelOuter[0] = 1.0;
                    gl_TessLevelOuter[1] = 1.0;
                    gl_TessLevelOuter[2] = 1.0;
                    gl_TessLevelInner[0] = 1.0;
                }
            """.trimIndent(),
            """
                #version 400 compatibility
                layout(triangles, equal_spacing, cw) in;
                void main() {
                    gl_Position = gl_ProjectionMatrix * (
                        gl_TessCoord.x * gl_in[0].gl_Position +
                        gl_TessCoord.y * gl_in[1].gl_Position +
                        gl_TessCoord.z * gl_in[2].gl_Position
                    );
                }
            """.trimIndent(),
        )

        val control = transformed.tessellationControl.orEmpty()
        val evaluation = transformed.tessellationEvaluation.orEmpty()
        assertContains(control, "uniform mat4 gbufferModelView;")
        assertContains(control, "flat in uint minosoftTextureArray[];")
        assertContains(control, "out float minosoftTessellationFogFragCoord[];")
        assertContains(
            control,
            "minosoftTessellationTextureLayer[gl_InvocationID] = " +
                "minosoftTextureLayer[gl_InvocationID];",
        )
        assertContains(evaluation, "uniform mat4 gbufferProjection;")
        assertContains(evaluation, "flat out uint minosoftTextureArray;")
        assertContains(
            evaluation,
            "minosoftTextureLayer = gl_TessCoord.x * minosoftTessellationTextureLayer[0]",
        )
        assertFalse("gl_ModelViewMatrix" in control)
        assertFalse("gl_ProjectionMatrix" in evaluation)
    }

    @Test
    fun `texture array specialization compacts sparse physical sampler slots`() {
        val source = """
            #version 330 core
            uniform sampler2DArray uTextures[16];
            vec4 sampleTexture(uint textureArray, vec3 coordinate) {
                switch (textureArray) {
                    case 1u: return texture(uTextures[1], coordinate);
                    case 2u: return texture(uTextures[2], coordinate);
                    case 3u: return texture(uTextures[3], coordinate);
                    case 7u: return texture(uTextures[7], coordinate);
                    case 10u: return texture(uTextures[10], coordinate);
                    default: return vec4(1.0);
                }
            }
        """.trimIndent()

        val compact = IrisLegacyShaderTransformer.specializeTextureArrays(source, listOf(3, 7, 10))

        assertContains(compact, "uniform sampler2DArray uTextures[3]")
        assertContains(compact, "case 3u: return texture(uTextures[0], coordinate)")
        assertContains(compact, "case 7u: return texture(uTextures[1], coordinate)")
        assertContains(compact, "case 10u: return texture(uTextures[2], coordinate)")
        assertFalse("case 1u:" in compact)
        assertFalse("case 2u:" in compact)
    }

    @Test
    fun `material companions share static arrays and stay neutral on dynamic slots`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_entities",
            ShaderProgramPhase.ENTITY,
            """
                #version 330 compatibility
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D gtexture;
                uniform sampler2D normals;
                uniform sampler2D specular;
                void main() {
                    vec4 diffuse = texture(gtexture, vec2(0.25));
                    vec4 normal = texture(normals, vec2(0.25));
                    vec4 material = texture(specular, vec2(0.25));
                    gl_FragData[0] = diffuse + normal * 0.0 + material * 0.0;
                }
            """.trimIndent(),
        )

        assertFalse("uniform sampler2D normals" in transformed.fragment)
        assertFalse("uniform sampler2D specular" in transformed.fragment)
        assertContains(transformed.fragment, "vec4 normal = minosoftSampleSceneNormal(")
        assertContains(transformed.fragment, "vec4 material = minosoftSampleSceneSpecular(")
        assertContains(
            transformed.fragment,
            "vec3 coordinate = vec3(uv.x, " +
                "(fract(uv.y) + 1.0) / 3.0, minosoftSceneTextureLayer)",
        )
        assertContains(
            transformed.fragment,
            "vec3 coordinate = vec3(uv.x, " +
                "(fract(uv.y) + 2.0) / 3.0, minosoftSceneTextureLayer)",
        )

        val specializedVertex = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.vertex,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(specializedVertex, "case 3u: return minosoftStaticLogicalUv(uv);")
        assertContains(specializedVertex, "case 7u: return minosoftDynamicLogicalUv(uv);")
        assertFalse("minosoftMaterialLogicalUvCase" in specializedVertex)

        val specialized = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.fragment,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(specialized, "uniform sampler2DArray uTextures[2]")
        assertContains(
            specialized,
            "case 3u: return texture(uTextures[0], minosoftStaticTextureCoordinate(coordinate), bias);",
        )
        assertContains(
            specialized,
            "case 7u: return texture(uTextures[1], minosoftDynamicTextureCoordinate(coordinate), bias);",
        )
        assertContains(
            specialized,
            "case 7u: return vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);",
        )
        assertContains(
            specialized,
            "case 7u: return vec4(0.0, 0.0, 0.0, 1.0);",
        )
        assertContains(
            specialized,
            "minosoftStaticTextureSize(textureSize(uTextures[0], lod))",
        )
        assertContains(
            specialized,
            "minosoftDynamicTextureSize(textureSize(uTextures[1], lod))",
        )
        assertContains(
            specialized,
            "return vec3(coordinate.x, fract(coordinate.y) / 3.0, coordinate.z);",
        )
        assertFalse("uTextures[7]" in specialized)
    }

    @Test
    fun `modern terrain keeps pack UV and texel semantics across material pages`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_terrain",
            ShaderProgramPhase.TERRAIN,
            """
                #version 330 compatibility
                attribute vec4 mc_Entity;
                attribute vec2 mc_midTexCoord;
                out vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy + mc_midTexCoord * 0.0;
                    gl_Position = ftransform();
                }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D gtexture;
                uniform sampler2D normals;
                uniform sampler2D specular;
                out vec4 color;
                void main() {
                    vec2 dx = vec2(0.25);
                    vec2 dy = vec2(0.5);
                    color = textureGrad(gtexture, uv, dx, dy);
                    color += textureGrad(normals, uv, dx, dy) * 0.0;
                    color += texelFetch(specular, ivec2(1), 0) * 0.0;
                    color += vec4(textureSize(gtexture, 0), 0, 0) * 0.0;
                }
            """.trimIndent(),
        )

        val vertex = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.vertex,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(vertex, "case 3u: return minosoftStaticLogicalUv(uv);")
        assertContains(vertex, "case 7u: return minosoftDynamicLogicalUv(uv);")
        assertContains(vertex, "minosoftTextureArray = minosoftTextureBits >> 28u;")
        assertFalse("minosoftMaterialLogicalUvCase" in vertex)

        val fragment = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.fragment,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(
            fragment,
            "case 3u: return textureGrad(uTextures[0], " +
                "minosoftStaticTextureCoordinate(coordinate), " +
                "minosoftStaticTextureGradient(dx), minosoftStaticTextureGradient(dy));",
        )
        assertContains(
            fragment,
            "case 7u: return textureGrad(uTextures[1], " +
                "minosoftDynamicTextureCoordinate(coordinate), " +
                "minosoftDynamicTextureGradient(dx), minosoftDynamicTextureGradient(dy));",
        )
        assertContains(
            fragment,
            "case 3u: return textureGrad(uTextures[0], coordinate, " +
                "vec2(dx.x, dx.y / 3.0), vec2(dy.x, dy.y / 3.0));",
        )
        assertContains(
            fragment,
            "ivec3 layered = ivec3(coordinate + ivec2(0, pageSize.y * 2), " +
                "int(minosoftTextureLayer));",
        )
        assertContains(fragment, "case 3u: return texelFetch(uTextures[0], layered, lod);")
        assertContains(
            fragment,
            "case 3u: return minosoftStaticTextureSize(textureSize(uTextures[0], lod));",
        )
        assertContains(
            fragment,
            "case 7u: return minosoftDynamicTextureSize(textureSize(uTextures[1], lod));",
        )
        assertFalse("minosoftMaterialTextureCoordinate" in fragment)
        assertFalse("minosoftMaterialTextureGradient" in fragment)
        assertFalse("minosoftMaterialTextureSize" in fragment)
        assertFalse("uTextures[7]" in fragment)
    }

    @Test
    fun `legacy shadow lookup retains vec4 component semantics in core GLSL`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "composite1",
            ShaderProgramPhase.COMPOSITE,
            """
                #version 130
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 130
                uniform sampler2DShadow shadowtex1;
                void main() {
                    float direct = shadow2D(shadowtex1, vec3(0.5)).x;
                    float translucent = shadow2D(shadowtex1, vec3(0.5)).z;
                    gl_FragData[0] = vec4(direct + translucent);
                }
            """.trimIndent(),
        )

        assertContains(transformed.fragment, "float direct = texture(shadowtex1, vec3(0.5));")
        assertContains(transformed.fragment, "float translucent = vec4(texture(shadowtex1, vec3(0.5))).z;")
    }

    @Test
    fun `legacy shadow lookup handles bounded nested pack expressions without prefix capture`() {
        val nested = "(".repeat(16_384) + "vec3(0.5)" + ")".repeat(16_384)
        val transformed = IrisLegacyShaderTransformer.transform(
            "composite1",
            ShaderProgramPhase.COMPOSITE,
            """
                #version 130
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 130
                uniform sampler2DShadow shadowtex1;
                void main() {
                    float direct = shadow2D (shadowtex1, $nested).x;
                    vec2 paired = shadow2D(shadowtex1, vec3(0.5)).xy;
                    float untouched = my_shadow2D(shadowtex1, vec3(0.5)).x;
                    gl_FragData[0] = vec4(direct + paired.x + untouched);
                }
            """.trimIndent(),
        )

        assertContains(transformed.fragment, "texture(shadowtex1, $nested);")
        assertContains(transformed.fragment, "vec4(texture(shadowtex1, vec3(0.5))).xy")
        assertContains(transformed.fragment, "my_shadow2D(shadowtex1, vec3(0.5)).x")
    }

    @Test
    fun `modern shadow terrain uses retained position without redeclaring pack matrices`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "shadow",
            ShaderProgramPhase.SHADOW,
            """
                #version 330 compatibility
                uniform mat4 shadowProjection;
                uniform mat4 shadowModelView;
                uniform mat4 shadowProjectionInverse;
                uniform mat4 shadowModelViewInverse;
                attribute vec4 mc_Entity;
                void main() {
                    vec4 position = shadowModelViewInverse * shadowProjectionInverse * ftransform() + gl_Vertex * 0.0;
                    gl_Position = shadowProjection * shadowModelView * position;
                }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D gtexture;
                void main() { gl_FragData[0] = texture(gtexture, vec2(0.0)); }
            """.trimIndent(),
        )

        assertFalse("ftransform" in transformed.vertex)
        assertContains(
            transformed.vertex,
            "shadowModelViewInverse * shadowProjectionInverse * shadowProjection * shadowModelView * vec4(vinPosition, 1.0)",
        )
        // The mutually exclusive scene and terrain branches each retain their own
        // declaration; either compiled specialization still sees exactly one.
        assertEquals(2, Regex("""uniform mat4 shadowProjection;""").findAll(transformed.vertex).count())
        assertContains(transformed.vertex, "minosoft:scene_bridge SKELETAL SKELETAL_TINTED")
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_PLAYER)")
        assertContains(transformed.vertex, "minosoftFinishShadow")
        assertContains(transformed.vertex, "out vec2 texCoord;")
        assertContains(transformed.vertex, "texcoord = texCoord;")
    }

    @Test
    fun `legacy shadow terrain exposes retained entity scene bridges`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "shadow",
            ShaderProgramPhase.SHADOW,
            """
                #version 120
                uniform mat4 shadowProjection;
                uniform mat4 shadowModelView;
                void main() {
                    gl_Position = shadowProjection * shadowModelView * gl_Vertex;
                }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D tex;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(tex, texcoord); }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "minosoft:scene_bridge PLAYER_SKELETAL PLAYER")
        assertContains(transformed.vertex, "minosoft:scene_bridge POSITION_TEXTURE ENTITY_FLAME")
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_PLAYER)")
        assertContains(transformed.vertex, "minosoftFinishShadow")
    }

    @Test
    fun `legacy textured fallback exposes the retained world border ABI`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_textured",
            ShaderProgramPhase.TERRAIN,
            """
                #version 120
                varying vec2 texcoord;
                void main() {
                    texcoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D texture;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(texture, texcoord); }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "minosoft:scene_bridge WORLD_BORDER WORLD_BORDER")
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_WORLD_BORDER)")
        assertContains(transformed.vertex, "minosoftBorderUv")
        assertFalse("ftransform" in transformed.vertex)
    }

    @Test
    fun `modern entity programs expose the retained flame ABI`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_entities",
            ShaderProgramPhase.ENTITY,
            """
                #version 330 compatibility
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D gtexture;
                void main() { gl_FragData[0] = texture(gtexture, vec2(0.0)); }
            """.trimIndent(),
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge POSITION_TEXTURE ENTITY_FLAME",
        )
        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge BLOCK_FEATURE BLOCK",
        )
        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge BILLBOARD_TEXT BILLBOARD_TEXT",
        )
        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge PLAYER_SKELETAL PLAYER",
        )
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_ENTITY_FLAME)")
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_BLOCK)")
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_BILLBOARD_TEXT)")
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_PLAYER)")
        assertContains(transformed.vertex, "layout (location = 4) in vec3 vinNormal")
        assertContains(transformed.vertex, "layout (location = 5) in vec4 vinTangent")
        assertContains(transformed.vertex, "layout (location = 6) in vec2 vinMidUV")
        assertContains(transformed.vertex, "minosoftPrepareEntityUv(vinUV, vinMidUV)")
        assertContains(transformed.vertex, "minosoftDecodeItemUv(floatBitsToUint(vinUV))")
        assertContains(transformed.vertex, "normal = normalize(vinNormal);")
        assertContains(
            transformed.vertex,
            "vec3 minosoftNormal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);",
        )
        assertContains(
            transformed.vertex,
            "vec3 minosoftTangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);",
        )
        assertContains(
            transformed.vertex,
            "vec3 textTangent = normalize((uMatrix * vec4(1.0, 0.0, 0.0, 0.0)).xyz);",
        )
        assertContains(
            transformed.vertex,
            "vec3 textBitangent = normalize((uMatrix * vec4(0.0, 1.0, 0.0, 0.0)).xyz);",
        )
        assertContains(transformed.vertex, "vec3 textNormal = normalize(cross(textBitangent, textTangent));")
        assertContains(
            transformed.vertex,
            "minosoftPrepareEntityPbr(scene_pos, normal, vec4(textTangent, -1.0));",
        )
        assertContains(transformed.vertex, "flat out vec2 midCoord")
        assertContains(transformed.vertex, "layout (location = 3) in vec2 vinMidUV")
        assertContains(transformed.vertex, "layout (location = 4) in vec2 vinMidUV")
        assertContains(transformed.vertex, "layout (location = 4) in vec4 vinTangent")
        assertContains(transformed.vertex, "layout (location = 5) in vec4 vinTangent")
        assertContains(transformed.vertex, "minosoftPrepareEntityUv(vinUV, vinMidUV)")
        assertContains(transformed.vertex, "signMidCoordPos = sign(offset)")
        assertContains(transformed.vertex, "flat out vec3 binormal")
        assertContains(transformed.vertex, "out vec3 viewVector")
        assertContains(transformed.vertex, "out vec4 vTexCoordAM")
        assertContains(
            transformed.vertex,
            "minosoftPrepareEntityPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w))",
        )
        assertContains(transformed.vertex, "uViewProjectionMatrix * worldPosition")
    }

    @Test
    fun `modern weather preserves the pack flat light coordinate contract`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_weather",
            ShaderProgramPhase.WEATHER,
            "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
            """
                #version 330 compatibility
                flat in vec2 lmCoord;
                in vec2 texCoord;
                void main() { gl_FragData[0] = vec4(lmCoord, texCoord.x, 1.0); }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "flat out vec2 lmCoord;")
        assertContains(transformed.fragment, "flat in vec2 lmCoord;")
    }

    @Test
    fun `legacy weather mirrors a smooth pack light coordinate contract`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_weather",
            ShaderProgramPhase.WEATHER,
            "#version 120\nvoid main() { gl_Position = ftransform(); }",
            """
                #version 120
                varying vec2 lmCoord;
                varying vec2 texCoord;
                void main() { gl_FragData[0] = vec4(lmCoord, texCoord.x, 1.0); }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "out vec2 lmCoord;")
        assertFalse("flat out vec2 lmCoord;" in transformed.vertex)
        assertContains(transformed.fragment, "in vec2 lmCoord;")
    }

    @Test
    fun `modern beacon programs expose the retained textured face basis`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_beaconbeam",
            ShaderProgramPhase.BLOCK,
            "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
            "#version 330 compatibility\nvoid main() { gl_FragData[0] = vec4(1.0); }",
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge POSITION_TEXTURE BEACON_BEAM",
        )
        assertContains(transformed.vertex, "layout (location = 4) in vec3 vinNormal")
        assertContains(transformed.vertex, "layout (location = 5) in vec4 vinTangent")
        assertContains(
            transformed.vertex,
            "normal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);",
        )
        assertContains(
            transformed.vertex,
            "tangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);",
        )
        assertContains(
            transformed.vertex,
            "binormal = normalize(cross(normal, tangent)) * vinTangent.w;",
        )
        assertContains(transformed.vertex, "tbn = mat3(tangent, binormal, normal);")
    }

    @Test
    fun `modern lightning programs consume the retained ribbon normal`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_lightning",
            ShaderProgramPhase.ENTITY,
            "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
            "#version 330 compatibility\nvoid main() { gl_FragData[0] = vec4(1.0); }",
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge POSITION_COLOR LIGHTNING",
        )
        assertContains(transformed.vertex, "layout (location = 2) in vec3 vinNormal")
        assertContains(
            transformed.vertex,
            "normal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);",
        )
        assertContains(
            transformed.vertex,
            "tbn = mat3(minosoftTangent, minosoftBinormal, normal);",
        )
        assertFalse("normal = vec3(0.0, 1.0, 0.0);" in transformed.vertex)
    }

    @Test
    fun `modern block programs retain flashing and skeletal block entity states`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_block",
            ShaderProgramPhase.BLOCK,
            """
                #version 330 compatibility
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D gtexture;
                flat in vec3 binormal, tangent;
                void main() { gl_FragData[0] = texture(gtexture, vec2(0.0)); }
            """.trimIndent(),
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge BLOCK_FEATURE FLASHING_BLOCK",
        )
        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP",
        )
        assertContains(transformed.vertex, "#ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK")
        assertContains(transformed.vertex, "mix(tint, uFlashColor, uFlashProgress)")
        assertContains(transformed.vertex, "layout (location = 4) in vec2 vinMidUV")
        assertContains(transformed.vertex, "layout (location = 5) in vec4 vinTangent")
        assertContains(transformed.vertex, "layout (location = 5) in vec3 vinNormal")
        assertContains(transformed.vertex, "layout (location = 6) in vec4 vinTangent")
        assertContains(transformed.vertex, "signMidCoordPos = sign(midOffset)")
        assertContains(transformed.vertex, "uint(max(blockEntityId - 10000, 0))")
        assertContains(transformed.vertex, "minosoftBlockEntityLightMap[light]")
        assertContains(transformed.vertex, "flat out vec3 tangent")
        assertContains(transformed.fragment, "flat in vec3 binormal, tangent;")
        assertContains(transformed.vertex, "out vec3 viewVector")
        assertContains(transformed.vertex, "out vec4 vTexCoordAM")
        assertContains(
            transformed.vertex,
            "minosoftPrepareBlockPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w))",
        )
    }

    @Test
    fun `legacy entity bridge mirrors a smooth vec4 pack tangent contract`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_entities",
            ShaderProgramPhase.ENTITY,
            "#version 120\nvoid main() { gl_Position = ftransform(); }",
            """
                #version 120
                varying vec3 normal;
                varying vec4 tangent;
                void main() {
                    vec3 bitangent = cross(normal, tangent.xyz) * tangent.w;
                    gl_FragData[0] = vec4(bitangent, 1.0);
                }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "out vec4 tangent;")
        assertFalse("flat out vec4 tangent;" in transformed.vertex)
        assertContains(
            transformed.vertex,
            "tangent = vec4(normalize(surfaceTangent.xyz), surfaceTangent.w);",
        )
        assertContains(transformed.vertex, "cross(surfaceNormal, tangent.xyz)")
        assertContains(transformed.vertex, "mat3(tangent.xyz, binormal, surfaceNormal)")
        assertContains(transformed.fragment, "in vec4 tangent;")
    }

    @Test
    fun `modern hand programs retain per-face coordinates for integrated PBR`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_hand",
            ShaderProgramPhase.HAND,
            "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
            "#version 330 compatibility\nvoid main() { gl_FragData[0] = vec4(1.0); }",
        )

        assertContains(transformed.vertex, "flat out vec2 midCoord")
        assertContains(transformed.vertex, "layout (location = 3) in vec2 vinMidUV")
        assertContains(transformed.vertex, "layout (location = 4) in vec2 vinMidUV")
        assertContains(transformed.vertex, "layout (location = 4) in vec4 vinTangent")
        assertContains(transformed.vertex, "layout (location = 5) in vec3 vinNormal")
        assertContains(transformed.vertex, "layout (location = 6) in vec4 vinTangent")
        assertContains(transformed.vertex, "minosoftPrepareHandUv(vinUV, vinMidUV)")
        assertContains(transformed.vertex, "minosoftPrepareHandUv(unpackedUv, vinMidUV)")
        assertContains(transformed.vertex, "flat out vec3 binormal")
        assertContains(transformed.vertex, "out vec3 viewVector")
        assertContains(transformed.vertex, "out vec4 vTexCoordAM")
        assertContains(
            transformed.vertex,
            "minosoftPrepareHandPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w))",
        )
    }

    @Test
    fun `modern textured fallback covers overlays and the world border`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_textured",
            ShaderProgramPhase.BASIC,
            """
                #version 330 compatibility
                void main() {
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    vec4 color = gl_Color + gl_MultiTexCoord0 * 0.0;
                }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D tex;
                flat in vec4 glColor;
                void main() {
                    vec4 color = texture(tex, vec2(0.0));
                    color *= glColor;
                    gl_FragData[0] = color;
                }
            """.trimIndent(),
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge POSITION_TEXTURE_2D GENERIC_TEXTURE_2D",
        )
        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge WORLD_BORDER WORLD_BORDER",
        )
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_GENERIC_TEXTURE_2D)")
        assertContains(transformed.vertex, "uCameraPosition.y - 150.0")
        assertContains(transformed.vertex, "uv += vec2(uTextureOffset)")
        assertContains(transformed.vertex, "layout (location = 3) in vec3 vinNormal")
        assertContains(transformed.vertex, "normal = normalize(vinNormal)")
        assertContains(transformed.vertex, "uTexture,")
        assertContains(transformed.vertex, "uTintColor")
        assertContains(transformed.fragment, "minosoftSampleTextureArray")
        assertContains(transformed.fragment, "color *= glColor")
        val borderAlphaTest = IrisAlphaTestDefaults.scene(
            ShaderProgramSource(
                name = "gbuffers_textured",
                phase = ShaderProgramPhase.BASIC,
                vertex = transformed.vertex,
                fragment = transformed.fragment,
                uniforms = emptySet(),
                samplers = emptySet(),
            ),
            SceneProgramBridge(SceneVertexAbi.WORLD_BORDER, SceneStateAbi.WORLD_BORDER, emptySet()),
        )
        assertEquals(IrisAlphaTest.NON_ZERO, borderAlphaTest)
        assertContains(
            IrisLegacyShaderTransformer.alphaTest(transformed.fragment, borderAlphaTest),
            "minosoft:alpha_test GREATER 1.0E-4",
        )
    }

    @Test
    fun `hand routes retain the pinned Iris cutout alpha test`() {
        fun alphaTest(state: SceneStateAbi): IrisAlphaTest? = IrisAlphaTestDefaults.scene(
            ShaderProgramSource(
                name = "gbuffers_hand",
                phase = ShaderProgramPhase.HAND,
                vertex = "#version 330 core\nvoid main() {}",
                fragment = "#version 330 core\nvoid main() { gl_FragData[0] = vec4(1.0); }",
                uniforms = emptySet(),
                samplers = emptySet(),
            ),
            SceneProgramBridge(SceneVertexAbi.ARM_SKELETAL, state, emptySet()),
        )

        assertEquals(IrisAlphaTest.ONE_TENTH, alphaTest(SceneStateAbi.ARM))
        assertEquals(IrisAlphaTest.ONE_TENTH, alphaTest(SceneStateAbi.HELD_ITEM))
    }

    @Test
    fun `modern sky basic suppresses the duplicate host sun scatter`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_skybasic",
            ShaderProgramPhase.SKY,
            """
                #version 330 compatibility
                void main() {
                    gl_Position = ftransform();
                    vec4 color = gl_Color;
                }
            """.trimIndent(),
            """
                #version 330 compatibility
                void main() { gl_FragData[0] = vec4(1.0); }
            """.trimIndent(),
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge SUN_SCATTER SUN_SCATTER",
        )
        assertContains(transformed.vertex, "minosoftSkyBasicPosition()")
        assertContains(transformed.fragment, "#if defined(MINOSOFT_STATE_ABI_SUN_SCATTER)")
        assertContains(transformed.fragment, "void main() { discard; }")
    }

    @Test
    fun `legacy terrain fixed function inputs become the terrain ABI`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_textured",
            ShaderProgramPhase.BASIC,
            """
                #version 120
                attribute float mc_Entity;
                varying vec2 uv;
                void main() {
                    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    gl_FogFragCoord = length(gl_Vertex.xyz);
                }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D texture;
                uniform sampler2D lightmap;
                varying vec2 uv;
                void main() {
                    gl_FragData[0] = texture2D(texture, uv) * texture2D(lightmap, uv);
                }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "#version 330 core")
        assertContains(transformed.vertex, "layout (location = 0) in vec3 vinPosition")
        assertContains(transformed.vertex, "void minosoftLegacyMain()")
        assertContains(transformed.vertex, "minosoft:scene_bridge POSITION_TEXTURE GENERIC_TEXTURE")
        assertContains(transformed.vertex, "minosoft:scene_bridge SKELETAL SKELETAL_TINTED")
        assertContains(transformed.vertex, "minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP")
        assertContains(transformed.vertex, "minosoft:scene_bridge ARM_SKELETAL ARM")
        assertContains(transformed.vertex, "layout (location = 4) in vec3 vinNormal")
        assertContains(transformed.vertex, "layout (location = 5) in vec4 vinTangent")
        assertContains(
            transformed.vertex,
            "tbn = mat3(minosoftTangent, minosoftBinormal, minosoftNormal);",
        )
        assertContains(transformed.fragment, "uniform sampler2DArray uTextures[16]")
        assertContains(transformed.fragment, "uniform float uFogStart")
        assertContains(transformed.fragment, "minosoftSampleLightmap( uv)")
        assertFalse("gl_FragData" in transformed.fragment)
        assertFalse("uniform sampler2D texture;" in transformed.fragment)

        val vertex = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.vertex,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(
            vertex,
            "return minosoftMaterialLogicalUv(physicalUv, minosoftTextureArray);",
        )
        assertContains(vertex, "case 3u: return minosoftStaticLogicalUv(uv);")
        assertContains(vertex, "case 7u: return minosoftDynamicLogicalUv(uv);")
        assertFalse("minosoftMaterialLogicalUvCase" in vertex)

        val fragment = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.fragment,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(
            fragment,
            "case 3u: return texture(uTextures[0], " +
                "minosoftStaticTextureCoordinate(coordinate), bias);",
        )
        assertContains(
            fragment,
            "case 7u: return texture(uTextures[1], " +
                "minosoftDynamicTextureCoordinate(coordinate), bias);",
        )
        assertFalse("minosoftMaterialTextureCoordinate" in fragment)
    }

    @Test
    fun `legacy terrain removes vector mc entity declarations without duplicating swizzles`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_terrain",
            ShaderProgramPhase.TERRAIN,
            """
                #version 120
                attribute vec4 mc_Entity;
                void main() {
                    float blockId = mc_Entity.x;
                    gl_Position = ftransform();
                }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D texture;
                void main() { gl_FragData[0] = texture2D(texture, vec2(0.0)); }
            """.trimIndent(),
        )

        assertFalse("in vec4 minosoftMcEntity.x" in transformed.vertex)
        assertFalse("minosoftMcEntity.x.x" in transformed.vertex)
        assertFalse("ftransform" in transformed.vertex)
        assertContains(transformed.vertex, "float blockId = minosoftMcEntity.x;")
    }

    @Test
    fun `legacy terrain reconstructs pack player position from the host translated view`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_water",
            ShaderProgramPhase.TERRAIN,
            """
                #version 120
                uniform mat4 gbufferModelView;
                uniform mat4 gbufferModelViewInverse;
                void main() {
                    vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position = gl_ProjectionMatrix * gbufferModelView * position;
                }
            """.trimIndent(),
            """
                #version 120
                void main() { gl_FragData[0] = vec4(1.0); }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "uniform mat4 gbufferModelView;")
        assertContains(transformed.vertex, "uniform mat4 minosoftPlayerModelView;")
        assertContains(transformed.vertex, "uniform mat4 minosoftPlayerModelViewInverse;")
        assertContains(
            transformed.vertex,
            "vec4 position = minosoftPlayerModelViewInverse * gbufferModelView * vec4(vinPosition, 1.0);",
        )
        assertContains(
            transformed.vertex,
            "gl_Position = gbufferProjection * minosoftPlayerModelView * position;",
        )
    }

    @Test
    fun `modern terrain reconstructs pack player position from the host translated view`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_water",
            ShaderProgramPhase.TERRAIN,
            """
                #version 330 core
                uniform mat4 gbufferModelView;
                uniform mat4 gbufferModelViewInverse;
                in vec3 vaPosition;
                void main() {
                    vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position = gl_ProjectionMatrix * gbufferModelView * position;
                }
            """.trimIndent(),
            """
                #version 330 core
                uniform sampler2D gtexture;
                uniform mat4 gbufferModelViewInverse;
                layout(location = 0) out vec4 color;
                void main() {
                    color = vec4(mat3(gbufferModelViewInverse) * vec3(0.0, 1.0, 0.0), 1.0);
                    color += texture(gtexture, vec2(0.0));
                }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "uniform mat4 gbufferModelView;")
        assertContains(transformed.vertex, "uniform mat4 minosoftPlayerModelView;")
        assertContains(transformed.vertex, "uniform mat4 minosoftPlayerModelViewInverse;")
        assertContains(
            transformed.vertex,
            "vec4 position = minosoftPlayerModelViewInverse * gbufferModelView * vec4(vinPosition, 1.0);",
        )
        assertContains(
            transformed.vertex,
            "gl_Position = gbufferProjection * minosoftPlayerModelView * position;",
        )
        assertContains(transformed.fragment, "uniform mat4 minosoftPlayerModelViewInverse;")
        assertContains(
            transformed.fragment,
            "color = vec4(mat3(minosoftPlayerModelViewInverse) * vec3(0.0, 1.0, 0.0), 1.0);",
        )
    }

    @Test
    fun `modern Sodium color and light inputs unpack from the retained terrain word`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_terrain",
            ShaderProgramPhase.TERRAIN,
            """
                #version 330 core
                layout (location = 2) in vec4 vaColor;
                layout (location = 3) in ivec2 vaUV2;
                out vec4 color;
                void main() {
                    color = vaColor + vec4(vaUV2, 0, 0);
                    gl_Position = vec4(0.0);
                }
            """.trimIndent(),
            "#version 330 core\nvoid main() {}",
        )

        assertContains(transformed.vertex, "minosoft:terrain_bridge packed-color-light")
        assertContains(transformed.vertex, "layout (location = 3) in float vinLightTint")
        assertContains(transformed.vertex, "minosoftPackedColor()")
        assertContains(transformed.vertex, "ivec2 minosoftPackedLight()")
        assertFalse(Regex("""\bin\s+vec4\s+vaColor\b""").containsMatchIn(transformed.vertex))
        assertFalse(Regex("""\bin\s+ivec2\s+vaUV2\b""").containsMatchIn(transformed.vertex))
    }

    @Test
    fun `legacy final texture alias becomes colortex presentation`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "final",
            ShaderProgramPhase.FINAL,
            """
                #version 120
                varying vec2 uv;
                void main() {
                    gl_Position = ftransform();
                    uv = gl_MultiTexCoord0.xy;
                }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D texture;
                varying vec2 uv;
                void main() {
                    gl_FragData[0] = texture2D(texture, uv);
                }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "layout (location = 0) in vec2 minosoftPosition")
        assertContains(transformed.fragment, "uniform sampler2D colortex0;")
        assertContains(transformed.fragment, "texture(colortex0, uv)")
        assertFalse("ftransform" in transformed.vertex)
        assertFalse("gl_FragData" in transformed.fragment)
    }

    @Test
    fun `modern fullscreen programs replace compatibility vertex builtins`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "composite",
            ShaderProgramPhase.COMPOSITE,
            """
                #version 330 core
                vec2 lightCoordinates() {
                    return (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
                }
                void main() {
                    gl_Position = ftransform();
                    vec2 uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                }
            """.trimIndent(),
            "#version 330 core\nvoid main() {}",
        )

        assertContains(transformed.vertex, "layout (location = 0) in vec2 minosoftFullscreenPosition")
        assertContains(transformed.vertex, "layout (location = 1) in vec2 minosoftFullscreenUv")
        assertContains(transformed.vertex, "vec4(minosoftFullscreenPosition, 0.0, 1.0)")
        assertContains(transformed.vertex, "vec4(minosoftFullscreenUv, 0.0, 1.0)")
        assertFalse("ftransform" in transformed.vertex)
        assertFalse("gl_TextureMatrix" in transformed.vertex)
        assertFalse("gl_MultiTexCoord1" in transformed.vertex)
    }

    @Test
    fun `fullscreen fragments use player relative model view aliases`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "deferred1",
            ShaderProgramPhase.DEFERRED,
            "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }",
            """
                #version 330 core
                uniform mat4 gbufferModelView;
                uniform mat4 gbufferModelViewInverse;
                out vec4 color;
                void main() {
                    color = gbufferModelViewInverse * gbufferModelView * vec4(1.0);
                }
            """.trimIndent(),
        )

        assertContains(transformed.fragment, "uniform mat4 minosoftPlayerModelView;")
        assertContains(transformed.fragment, "uniform mat4 minosoftPlayerModelViewInverse;")
        assertContains(
            transformed.fragment,
            "minosoftPlayerModelViewInverse * minosoftPlayerModelView * vec4(1.0)",
        )
        assertFalse("gbufferModelView" in transformed.fragment)
    }

    @Test
    fun `modern fullscreen token rewrite stays bounded on large expanded stages`() {
        val vertex = buildString {
            appendLine("#version 330 core")
            repeat(12_000) {
                appendLine("// keep_gl_Vertex_suffix ftransformValue gl_TextureMatrixExtra varyingValue")
            }
            appendLine("varying vec4 color;")
            appendLine("attribute vec4 legacyPosition;")
            appendLine("void main() {")
            appendLine("    color = gl_Color;")
            appendLine("    vec4 coordinates = gl_Vertex + gl_MultiTexCoord0 + gl_MultiTexCoord1;")
            appendLine("    gl_Position = gl_TextureMatrix [ 12 ] * ftransform ( );")
            appendLine("}")
        }

        val transformed = IrisLegacyShaderTransformer.transform(
            "composite",
            ShaderProgramPhase.COMPOSITE,
            vertex,
            "#version 330 core\nvoid main() {}",
        ).vertex

        assertContains(transformed, "keep_gl_Vertex_suffix")
        assertContains(transformed, "ftransformValue")
        assertContains(transformed, "gl_TextureMatrixExtra")
        assertContains(transformed, "varyingValue")
        assertContains(transformed, "out vec4 color;")
        assertContains(transformed, "in vec4 legacyPosition;")
        assertContains(transformed, "vec4(minosoftFullscreenPosition, 0.0, 1.0)")
        assertContains(transformed, "vec4(minosoftFullscreenUv, 0.0, 1.0)")
        assertContains(transformed, "mat4(1.0)")
        assertFalse(Regex("""\bgl_Vertex\b""").containsMatchIn(transformed))
        assertFalse(Regex("""\bftransform\s*\(""").containsMatchIn(transformed))
    }

    @Test
    fun `legacy composite preserves pack samplers and draw buffers while bridging fixed function inputs`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "composite",
            ShaderProgramPhase.COMPOSITE,
            """
                #version 120
                varying vec2 uv;
                void main() {
                    gl_Position = ftransform();
                    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D colortex3;
                varying vec2 uv;
                void main() {
                    gl_FragData[0] = texture2D(colortex3, uv);
                    gl_FragData[2] = texelFetch2D(colortex3, ivec2(uv), 0);
                }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "#version 330 core")
        assertContains(transformed.vertex, "out vec2 uv;")
        assertContains(transformed.vertex, "layout (location = 0) in vec2 minosoftFullscreenPosition")
        assertContains(transformed.fragment, "uniform sampler2D colortex3;")
        assertContains(transformed.fragment, "in vec2 uv;")
        assertContains(transformed.fragment, "texture(colortex3, uv)")
        assertContains(transformed.fragment, "texelFetch(colortex3, ivec2(uv), 0)")
        assertContains(transformed.fragment, "layout (location = 0) out vec4 minosoftFragmentColor0;")
        assertContains(transformed.fragment, "layout (location = 2) out vec4 minosoftFragmentColor2;")
        assertFalse("ftransform" in transformed.vertex)
        assertFalse("gl_TextureMatrix" in transformed.vertex)
        assertFalse("gl_FragData" in transformed.fragment)
    }

    @Test
    fun `fullscreen vertices deterministically initialize pack outputs before authored writes`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "deferred",
            ShaderProgramPhase.DEFERRED,
            """
                #version 120
                flat varying vec2 tempOffsets;
                varying vec3 authored;
                #ifdef END_SHADER
                flat varying float endOnly;
                #endif
                void main() {
                    gl_Position = ftransform();
                    authored = vec3(1.0);
                }
            """.trimIndent(),
            """
                #version 120
                flat varying vec2 tempOffsets;
                varying vec3 authored;
                void main() {
                    gl_FragData[0] = vec4(tempOffsets, authored.x, 1.0);
                }
            """.trimIndent(),
        )

        assertContains(transformed.vertex, "tempOffsets = vec2(0);")
        assertContains(transformed.vertex, "authored = vec3(0);")
        assertFalse("endOnly = float(0);" in transformed.vertex)
        assertTrue(transformed.vertex.indexOf("authored = vec3(0);") < transformed.vertex.indexOf("authored = vec3(1.0);"))
    }

    @Test
    fun `modern fullscreen vertex declares referenced core fog uniforms`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "deferred1",
            ShaderProgramPhase.DEFERRED,
            """
                #version 330 core
                uniform float far;
                void main() {
                    bool distantFog = fogStart / far > 0.5;
                    gl_Position = ftransform();
                }
            """.trimIndent(),
            "#version 330 core\nvoid main() {}",
        )

        assertContains(transformed.vertex, "uniform float fogStart;")
        assertEquals(1, Regex("""uniform\s+float\s+fogStart\s*;""").findAll(transformed.vertex).count())
    }

    @Test
    fun `legacy cloud and textured sky receive exact scene bridges`() {
        val cloud = IrisLegacyShaderTransformer.transform(
            "gbuffers_clouds",
            ShaderProgramPhase.SKY,
            LEGACY_SCENE_VERTEX,
            LEGACY_SCENE_FRAGMENT,
        )
        val sky = IrisLegacyShaderTransformer.transform(
            "gbuffers_skytextured",
            ShaderProgramPhase.SKY,
            LEGACY_SCENE_VERTEX,
            LEGACY_SCENE_FRAGMENT,
        )

        assertContains(cloud.vertex, "minosoft:scene_bridge CLOUD CLOUD")
        assertContains(cloud.fragment, "minosoftCloudTexture(")
        assertContains(sky.vertex, "minosoft:scene_bridge SKY_TEXTURE SKY_TEXTURE")
        assertContains(sky.vertex, "minosoft:scene_bridge PLANET PLANET")
        assertContains(sky.fragment, "uniform sampler2DArray uTextures[16]")

        val vertex = IrisLegacyShaderTransformer.specializeTextureArrays(
            sky.vertex,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(
            vertex,
            "coord0 = minosoftMaterialLogicalUv(vinUV, minosoftSceneTextureArray);",
        )
        assertContains(vertex, "case 3u: return minosoftStaticLogicalUv(uv);")
        assertContains(vertex, "case 7u: return minosoftDynamicLogicalUv(uv);")
        assertFalse("minosoftMaterialLogicalUvCase" in vertex)

        val fragment = IrisLegacyShaderTransformer.specializeTextureArrays(
            sky.fragment,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(
            fragment,
            "case 3u: return texture(uTextures[0], " +
                "minosoftStaticTextureCoordinate(coordinate));",
        )
        assertContains(
            fragment,
            "case 7u: return texture(uTextures[1], " +
                "minosoftDynamicTextureCoordinate(coordinate));",
        )
    }

    @Test
    fun `explicit no-op sky programs retain exact scene suppression bridges`() {
        val clouds = requireNotNull(IrisLegacyShaderTransformer.suppressedScene("gbuffers_clouds"))
        val sky = requireNotNull(IrisLegacyShaderTransformer.suppressedScene("gbuffers_skybasic"))

        assertContains(
            clouds.vertex,
            "minosoft:scene_bridge CLOUD CLOUD fog,uCameraPosition,uCloudsColor,uOffset,uViewProjectionMatrix,uYOffset",
        )
        assertContains(
            sky.vertex,
            "minosoft:scene_bridge SKY_POSITION SKY_COLOR uSkyColor,uSkyViewProjectionMatrix",
        )
        assertContains(clouds.fragment, "discard;")
        assertNull(IrisLegacyShaderTransformer.suppressedScene("gbuffers_terrain"))
    }

    @Test
    fun `already adapted scene programs retain their authored bridges`() {
        val vertex = """
            // minosoft:scene_bridge POSITION_COLOR COLOR uViewProjectionMatrix
            #version 330 core
            void main() {
                gl_Position = vec4(0.0);
            }
        """.trimIndent()
        val fragment = """
            #version 330 core
            void main() {
            }
        """.trimIndent()

        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_armor_glint",
            ShaderProgramPhase.ENTITY,
            vertex,
            fragment,
        )

        assertEquals(vertex, transformed.vertex)
        assertEquals(fragment, transformed.fragment)
    }

    @Test
    fun `modern armor glint bridges player skeletal armor geometry`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_armor_glint",
            ShaderProgramPhase.ENTITY,
            """
                #version 330 compatibility
                void main() { gl_Position = ftransform(); }
            """.trimIndent(),
            """
                #version 330 compatibility
                uniform sampler2D gtexture;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture(gtexture, uv); }
            """.trimIndent(),
        )

        assertContains(
            transformed.vertex,
            "minosoft:scene_bridge PLAYER_SKELETAL PLAYER",
        )
        assertContains(transformed.vertex, "defined(MINOSOFT_STATE_ABI_PLAYER)")
        assertContains(transformed.vertex, "layout (location = 2) in float vinPartTransformNormal")
        assertContains(transformed.vertex, "minosoftSceneTextureArray = uGlintTexture >> 28u")
        assertContains(transformed.vertex, "decodeNormal(bits & 0xFFFu) * uInflate")
        assertContains(transformed.vertex, "skinPart != uFeaturePart")
        assertContains(transformed.vertex, "out vec2 texcoord;")
        assertContains(transformed.vertex, "flat out float exposure;")
        assertFalse(Regex("""\bvarying\b""").containsMatchIn(transformed.fragment))
    }

    @Test
    fun `legacy basic covers color light and sky scene layouts`() {
        val basic = IrisLegacyShaderTransformer.transform(
            "gbuffers_basic",
            ShaderProgramPhase.BASIC,
            LEGACY_SCENE_VERTEX,
            """
                #version 120
                varying vec4 color;
                void main() {
                    gl_FragData[0] = color;
                }
            """.trimIndent(),
        )

        assertContains(basic.vertex, "minosoft:scene_bridge POSITION_COLOR COLOR")
        assertContains(basic.vertex, "minosoft:scene_bridge POSITION_COLOR_LIGHT LIGHT_COLOR")
        assertContains(basic.vertex, "minosoft:scene_bridge SKY_POSITION SKY_COLOR")
        assertContains(basic.vertex, "uniform vec4 uSkyColor")
        assertContains(basic.fragment, "layout (location = 0) out vec4 minosoftFragmentColor")
    }

    @Test
    fun `modern basic retains the color light leash scene bridge`() {
        val basic = IrisLegacyShaderTransformer.transform(
            "gbuffers_basic",
            ShaderProgramPhase.BASIC,
            """
                #version 330 compatibility
                void main() {
                    gl_Position = ftransform();
                }
            """.trimIndent(),
            """
                #version 330 compatibility
                void main() {
                    gl_FragData[0] = vec4(1.0);
                }
            """.trimIndent(),
        )

        assertContains(basic.vertex, "minosoft:scene_bridge POSITION_COLOR COLOR")
        assertContains(basic.vertex, "minosoft:scene_bridge POSITION_COLOR_LIGHT LIGHT_COLOR")
        assertContains(basic.vertex, "layout (location = 2) in float vinLight")
        assertContains(basic.vertex, "layout (location = 3) in vec3 vinNormal")
        assertContains(basic.vertex, "normal = normalize(vinNormal)")
        assertContains(basic.vertex, "uniform uLightMapBuffer")
        assertContains(basic.fragment, "layout (location = 0) out vec4 minosoftFragmentColor0")
        assertFalse("gl_FragData" in basic.fragment)
    }

    @Test
    fun `legacy particle fallback expands retained points and leaves draw state to the selected route`() {
        val terrain = IrisLegacyShaderTransformer.transform(
            "gbuffers_textured",
            ShaderProgramPhase.BASIC,
            LEGACY_SCENE_VERTEX,
            LEGACY_SCENE_FRAGMENT,
        )
        val particle = IrisLegacyShaderTransformer.particleFallback(terrain.fragment)

        assertContains(particle.vertex, "minosoft:scene_bridge PARTICLE_POINT PARTICLE")
        assertContains(particle.vertex, "layout (location = 6) in float vinLight")
        assertContains(requireNotNull(particle.geometry), "layout (points) in")
        assertContains(requireNotNull(particle.geometry), "layout (triangle_strip, max_vertices = 4) out")
        assertContains(requireNotNull(particle.geometry), "vec3 particleTangent = normalize(uCameraRight);")
        assertContains(requireNotNull(particle.geometry), "vec3 particleBitangent = normalize(-uCameraUp);")
        assertContains(
            requireNotNull(particle.geometry),
            "vec3 particleNormal = normalize(cross(uCameraRight, uCameraUp));",
        )
        assertContains(
            requireNotNull(particle.geometry),
            "tbn = mat3(particleTangent, particleBitangent, particleNormal);",
        )
        assertFalse("normal = vec3(0.0, 1.0, 0.0);" in requireNotNull(particle.geometry))
        assertContains(particle.fragment, "defined(MINOSOFT_STATE_ABI_PARTICLE)")
        assertFalse("minosoftAlphaTestPackMain" in particle.fragment)

        val geometry = IrisLegacyShaderTransformer.specializeTextureArrays(
            requireNotNull(particle.geometry),
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(3),
        )
        assertContains(
            geometry,
            "vec2 logicalUv = minosoftMaterialLogicalUv(particleUv, minosoftTextureArray);",
        )
        assertContains(geometry, "case 3u: return minosoftStaticLogicalUv(uv);")
        assertContains(geometry, "case 7u: return minosoftDynamicLogicalUv(uv);")
        assertFalse("minosoftMaterialLogicalUvCase" in geometry)
    }

    @Test
    fun `alpha test evaluates the authored attachment zero output after pack main`() {
        val fragment = """
            #version 330 core
            layout (location = 0) out vec4 minosoftFragmentColor0;
            layout (location = 1) out vec4 minosoftFragmentColor1;
            void main() {
                minosoftFragmentColor0 = vec4(0.25);
                minosoftFragmentColor1 = vec4(1.0);
            }
        """.trimIndent()

        val transformed = IrisLegacyShaderTransformer.alphaTest(
            fragment,
            IrisAlphaTest(IrisAlphaTestFunction.GREATER, 0.1f),
        )

        assertContains(transformed, "void minosoftAlphaTestPackMain()")
        assertContains(transformed, "minosoftAlphaTestPackMain();")
        assertContains(transformed, "if (!(minosoftFragmentColor0.a > 0.1)) { discard; }")
        assertFalse("minosoftFragmentColor1.a >" in transformed)
    }

    @Test
    fun `alpha test honors never always and an unlocated single output`() {
        val fragment = """
            #version 330 core
            out lowp vec4 color;
            void main() { color = vec4(1.0); }
        """.trimIndent()

        val never = IrisLegacyShaderTransformer.alphaTest(
            fragment,
            IrisAlphaTest(IrisAlphaTestFunction.NEVER, 0.0f),
        )

        assertContains(never, "minosoftAlphaTestPackMain();")
        assertContains(never, "// minosoft:alpha_test NEVER 0.0")
        assertContains(never, "discard;")
        assertEquals(fragment, IrisLegacyShaderTransformer.alphaTest(fragment, IrisAlphaTest.ALWAYS))
        assertEquals(fragment, IrisLegacyShaderTransformer.alphaTest(fragment, null))
    }

    @Test
    fun `unconditional extensions relocate ahead of injected declarations`() {
        val source = """
            #version 400 core
            layout (location = 0) in vec3 position;
            #extension GL_ARB_shader_image_load_store : enable
            #ifdef OPTIONAL_FEATURE
            #extension GL_ARB_gpu_shader5 : enable
            #endif
            void main() {}
        """.trimIndent()

        val relocated = IrisLegacyShaderTransformer.relocateUnconditionalExtensions(source)

        assertEquals(
            "#extension GL_ARB_shader_image_load_store : enable",
            relocated.lineSequence().drop(1).first(),
        )
        assertTrue(
            relocated.indexOf("#extension GL_ARB_gpu_shader5") >
                relocated.indexOf("#ifdef OPTIONAL_FEATURE"),
        )
    }

    @Test
    fun `source native block atlas retains face array and layer metadata`() {
        val transformed = IrisLegacyShaderTransformer.transformSourceNativeBlockAtlas(
            IrisLegacyShaderTransformer.Stages(
                vertex = """
                    #version 330 core
                    $FACE_DATA_SOURCE
                    uint minosoftTextureArray;
                    float minosoftTextureLayer;
                    vec4 glColor;
                    vec2 lmCoordM;
                    void store() {
                        vec2 origin = vec2(0.0);
                        float textureRad = 0.5;
                        uvec4 packed = packFaceData(faceData(
                            glColor.rgb,
                            vec2(lmCoordM.x * 0.99 + 0.001, lmCoordM.y),
                            vec3(origin, textureRad)
                        ));
                    }
                """.trimIndent(),
                fragment = """
                    #version 330 core
                    uniform sampler2D textureAtlas;
                    uniform ivec2 atlasSize;
                    $FACE_DATA_SOURCE
                    ivec2 minosoftSampleTextureArraySize(uint textureArray, int lod) {
                        return ivec2(16);
                    }
                    vec4 minosoftSampleTextureArrayLod(
                        uint textureArray,
                        float textureLayer,
                        vec2 uv,
                        float lod
                    ) {
                        return vec4(uv, textureLayer, lod);
                    }
                    vec4 reflectFace(ivec3 voxelPos, vec3 normal) {
                        faceData faceData = getFaceData(voxelPos, normal);
                        vec2 size = textureSize(textureAtlas, 0);
                        vec2 textureCoord = faceData.textureBounds.xy;
                        float lod = 0.0;
                        return texture2DLod(textureAtlas, textureCoord, lod) +
                            vec4(size + atlasSize, 0.0, 0.0);
                    }
                """.trimIndent(),
            ),
            "textureAtlas",
        )

        assertContains(transformed.vertex, "uint minosoftTextureArray;")
        assertContains(transformed.vertex, "uint minosoftTextureLayer;")
        assertContains(transformed.vertex, "(textureLayer << 16u) | uv.z")
        assertContains(transformed.vertex, "float(textureArray) / 255.0")
        assertContains(transformed.vertex, "minosoftTextureArray,")
        assertContains(transformed.vertex, "uint(minosoftTextureLayer)")
        assertFalse("uniform sampler2D textureAtlas" in transformed.fragment)
        assertContains(
            transformed.fragment,
            "minosoftSampleTextureArraySize(faceData.minosoftTextureArray, 0)",
        )
        assertFalse("uniform ivec2 atlasSize" in transformed.fragment)
        assertFalse(Regex("""\batlasSize\b""").containsMatchIn(transformed.fragment))
        assertContains(
            transformed.fragment,
            "minosoftSampleTextureArrayLod(" +
                "faceData.minosoftTextureArray, float(faceData.minosoftTextureLayer), textureCoord, lod)",
        )
        assertContains(transformed.fragment, "data.y >> 16u")
        assertContains(transformed.fragment, "data.y & 65535u")
    }

    @Test
    fun `modern terrain anisotropic filtering samples the source native texture array`() {
        val transformed = IrisLegacyShaderTransformer.transform(
            "gbuffers_terrain",
            ShaderProgramPhase.TERRAIN,
            "#version 330 compatibility\nvoid main() { gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex; }",
            """
                #version 330 compatibility
                uniform sampler2D tex;
                vec4 textureAF(sampler2D texSampler, vec2 uv) {
                    return texture2DLod(texSampler, uv, 2.0);
                }
                void main() {
                    gl_FragData[0] = textureAF(tex, vec2(0.5));
                }
            """.trimIndent(),
        )

        assertContains(transformed.fragment, "vec4 textureAF( vec2 uv)")
        assertContains(transformed.fragment, "return minosoftSampleTextureLod( uv, 2.0)")
        assertContains(transformed.fragment, "minosoftFragmentColor0 = textureAF( vec2(0.5))")
        assertFalse(Regex("""\b(?:tex|texSampler)\b""").containsMatchIn(transformed.fragment))
    }

    @Test
    fun `source native block atlas injects compactable fullscreen array sampling`() {
        val transformed = IrisLegacyShaderTransformer.transformSourceNativeBlockAtlas(
            IrisLegacyShaderTransformer.Stages(
                vertex = """
                    #version 330 core
                    void main() { gl_Position = vec4(0.0); }
                """.trimIndent(),
                fragment = """
                    #version 330 core
                    uniform sampler2D textureAtlas;
                    uniform ivec2 atlasSize;
                    struct ReflectedFace {
                        uint minosoftTextureArray;
                        uint minosoftTextureLayer;
                    };
                    out vec4 color;
                    void main() {
                        ReflectedFace faceData = ReflectedFace(7u, 4u);
                        vec2 textureCoord = vec2(0.5);
                        float lod = 0.0;
                        color = texture2DLod(textureAtlas, textureCoord, lod) +
                            vec4(atlasSize, 0.0, 0.0);
                    }
                """.trimIndent(),
            ),
            "textureAtlas",
        )
        val specialized = IrisLegacyShaderTransformer.specializeTextureArrays(
            transformed.fragment,
            physicalSlots = listOf(3, 7),
            companionSlots = listOf(7),
        )

        assertContains(transformed.fragment, "uniform sampler2DArray uTextures[16];")
        assertContains(
            transformed.fragment,
            "minosoftSampleTextureArraySize(faceData.minosoftTextureArray, 0)",
        )
        assertContains(
            transformed.fragment,
            "minosoftSampleTextureArrayLod(" +
                "faceData.minosoftTextureArray, float(faceData.minosoftTextureLayer), textureCoord, lod)",
        )
        assertFalse(Regex("""\batlasSize\b""").containsMatchIn(transformed.fragment))
        assertContains(specialized, "uniform sampler2DArray uTextures[2];")
        assertContains(
            specialized,
            "case 7u: return textureLod(uTextures[1], minosoftStaticTextureCoordinate(coordinate), lod);",
        )
        assertContains(
            specialized,
            "case 7u: return minosoftStaticTextureSize(textureSize(uTextures[1], lod));",
        )
        assertFalse("case 0u:" in specialized)
    }

    private companion object {
        val FACE_DATA_SOURCE = """
            struct faceData {
                vec3 glColor;
                vec2 lightmap;
                vec3 textureBounds;
            };
            uvec4 blockData[1];
            int getFaceIndex(ivec3 voxelPos, vec3 normal) { return 0; }
            uvec4 packFaceData(faceData data) {
                uvec3 uv = uvec3(clamp(data.textureBounds * 65536.0, 0.0, 65535.0));
                uvec2 lightmap = uvec2(clamp(data.lightmap * 65536.0, 0.0, 65535.0));
                return uvec4(
                    (uv.x << 16u) | uv.y,
                    uv.z,
                    packUnorm4x8(vec4(data.glColor, 0.0)),
                    (lightmap.x << 16u) | lightmap.y
                );
            }
            faceData getFaceData(ivec3 voxelPos, vec3 normal) {
                uvec4 data = blockData[getFaceIndex(voxelPos, normal)];
                return faceData(
                    unpackUnorm4x8(data.z).xyz,
                    vec2(data.w >> 16u, data.w & 65535u) / 65536.0,
                    vec3(data.x >> 16u, data.x & 65535u, data.y) / 65536.0
                );
            }
        """.trimIndent()
        val LEGACY_SCENE_VERTEX = """
            #version 120
            varying vec2 uv;
            void main() {
                gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                uv = gl_MultiTexCoord0.xy;
            }
        """.trimIndent()
        val LEGACY_SCENE_FRAGMENT = """
            #version 120
            uniform sampler2D texture;
            varying vec2 uv;
            void main() {
                gl_FragData[0] = texture2D(texture, uv);
            }
        """.trimIndent()
    }
}
