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
 * Converts the bounded GLSL 1.20 fixed-function surface used by classic
 * OptiFine/Iris packs into Minosoft's core-profile terrain and presentation
 * ABIs. Modern or already-adapted sources are returned unchanged.
 *
 * Scene programs need a distinct adapter for every physical host vertex ABI;
 * this transformer deliberately does not invent those bridges.
 */
internal object IrisLegacyShaderTransformer {
    private val VERSION = Regex("""(?m)^\s*#version\s+(\d+)[^\r\n]*$""")
    private val MAIN = Regex("""\bvoid\s+main\s*\(\s*\)\s*\{""")
    private val RESOURCE_SAMPLER_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val MC_ENTITY = Regex(
        """(?m)^\s*(?:attribute|in)\s+(?:vec[234]|float)\s+mc_Entity\s*;\s*$""",
    )
    private val DIFFUSE_SAMPLER = Regex("""(?m)^\s*uniform\s+sampler2D\s+texture\s*;\s*$""")
    private val LIGHTMAP_SAMPLER = Regex("""(?m)^\s*uniform\s+sampler2D\s+lightmap\s*;\s*$""")
    private val SODIUM_COLOR = Regex(
        """(?m)^\s*(?:layout\s*\([^)]*\)\s*)?(?:in|attribute)\s+(?:lowp\s+|mediump\s+|highp\s+)?vec4\s+vaColor\s*;\s*$""",
    )
    private val SODIUM_LIGHT = Regex(
        """(?m)^\s*(?:layout\s*\([^)]*\)\s*)?(?:in|attribute)\s+(?:lowp\s+|mediump\s+|highp\s+)?([iu]?vec2)\s+(vaUV2|a_LightCoord)\s*;\s*$""",
    )
    private val MODERN_TERRAIN_ATTRIBUTE = Regex(
        """(?m)^\s*(?:attribute|in)\s+(?:vec[234]|float)\s+""" +
            """(at_tangent|at_midBlock|mc_Entity|mc_midTexCoord)\s*;\s*$""",
    )
    private val MODERN_DIFFUSE_SAMPLER = Regex(
        """(?m)^\s*uniform\s+sampler2D\s+(gtexture|tex|texture)\s*;\s*$""",
    )
    private val MODERN_SPECULAR_SAMPLER = Regex(
        """(?m)^\s*uniform\s+sampler2D\s+specular\s*;\s*$""",
    )
    private val MODERN_NORMAL_SAMPLER = Regex(
        """(?m)^\s*uniform\s+sampler2D\s+normals\s*;\s*$""",
    )
    private val FRAGMENT_OUTPUT = Regex(
        """(?m)^\s*layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*out\s+""" +
            """(?:(?:lowp|mediump|highp)\s+)?vec4\s+([A-Za-z_][A-Za-z0-9_]*)\s*;\s*$""",
    )
    private val UNLOCATED_FRAGMENT_OUTPUT = Regex(
        """(?m)^\s*out\s+(?:(?:lowp|mediump|highp)\s+)?vec4\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)\s*;\s*$""",
    )
    private val HOST_TEXTURE_CASE = Regex(
        """\s*case\s+(\d+)u:\s*return\s+.*uTextures\[\d+].*;\s*""",
    )
    private val HOST_COMPANION_TEXTURE_CASE = Regex(
        """(\s*)case\s+(\d+)u:\s*return\s+minosoftCompanion(Normal|Specular)\((.*uTextures\[\d+].*)\);\s*""",
    )
    private val HOST_MATERIAL_SIZE_CASE = Regex(
        """(\s*)case\s+(\d+)u:\s*return\s+minosoftMaterialTextureSize\(textureSize\(uTextures\[\d+],\s*lod\)\);\s*""",
    )
    private val HOST_LOGICAL_UV_CASE = Regex(
        """(\s*)case\s+(\d+)u:\s*return\s+minosoftMaterialLogicalUvCase\(uv\);\s*""",
    )
    private val HOST_TESSELLATION_VARYING = Regex(
        """(?m)^\s*(flat\s+)?out\s+([A-Za-z_][A-Za-z0-9_]*)\s+""" +
            """(minosoft[A-Za-z0-9_]*)\s*;\s*$""",
    )
    private val FULLSCREEN_OUTPUT = Regex(
        """(?m)^\s*(?:(?:flat|smooth|noperspective|centroid)\s+)*out\s+""" +
            """(float|int|uint|bool|[biud]?vec[234]|mat[234](?:x[234])?)\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)\s*;\s*$""",
    )
    private val FULLSCREEN_PHASES = setOf(
        ShaderProgramPhase.BEGIN,
        ShaderProgramPhase.SHADOW_COMPOSITE,
        ShaderProgramPhase.PREPARE,
        ShaderProgramPhase.DEFERRED,
        ShaderProgramPhase.COMPOSITE,
        ShaderProgramPhase.FINAL,
    )
    private const val TEXTURE_ARRAY_INDEX_MARKER = "minosoft:texture_array_index"
    private const val MODERN_SHADOW_SCENE_CONDITION =
        "defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || " +
            "defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP) || " +
            "defined(MINOSOFT_STATE_ABI_PLAYER) || " +
            "defined(MINOSOFT_STATE_ABI_BLOCK) || " +
            "defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK) || " +
            "defined(MINOSOFT_STATE_ABI_ENTITY_FLAME)"
    private const val MODERN_TEXTURED_SCENE_CONDITION =
        "defined(MINOSOFT_STATE_ABI_GENERIC_TEXTURE_2D) || " +
            "defined(MINOSOFT_STATE_ABI_WORLD_BORDER)"

    data class Stages(
        val vertex: String,
        val fragment: String,
        val geometry: String? = null,
        val tessellationControl: String? = null,
        val tessellationEvaluation: String? = null,
    )

    private data class TessellationVarying(
        val flat: Boolean,
        val type: String,
        val name: String,
    ) {
        val controlName: String = "minosoftTessellation${name.removePrefix("minosoft")}"
    }

    /**
     * Adapts Iris's managed block-atlas alias to Minosoft's source-native
     * texture arrays for Complementary-style world-space reflection data.
     *
     * The pack already serializes per-face UV bounds into an SSBO. Its four
     * words leave the high half of data.y and packed-color alpha unused, so
     * retain the 16-bit layer and 8-bit array there. Reflected fragments can
     * then select the same array layer that the shadow producer rendered.
     */
    fun transformSourceNativeBlockAtlas(
        stages: Stages,
        sampler: String,
        activeStages: Stages = stages,
    ): Stages {
        require(RESOURCE_SAMPLER_NAME.matches(sampler)) { "Invalid source-native atlas sampler $sampler" }
        fun faceData(source: String, activeSource: String): String {
            if ("struct faceData" !in activeSource || "textureBounds" !in activeSource) return source
            return transformSourceNativeFaceData(source)
        }
        fun atlasSampling(source: String, activeSource: String): String {
            val declaration = Regex(
                """(?m)^\s*uniform\s+sampler2D\s+${Regex.escape(sampler)}\s*;\s*$""",
            )
            val withoutDeclaration = declaration.replace(activeSource, "")
            if (!Regex("""\b${Regex.escape(sampler)}\b""").containsMatchIn(withoutDeclaration)) return source
            return transformSourceNativeAtlasSampling(source, sampler)
        }
        val fragment = faceData(stages.fragment, activeStages.fragment)
        val sourceNativeFragment = atlasSampling(fragment, activeStages.fragment)
        val drawSamplerDeclaration = Regex("""(?m)^\s*uniform\s+sampler2D\s+tex\s*;\s*$""")
        val activeDrawSampler = Regex(
            """\btexture(?:2D)?(?:Lod)?\s*\(\s*tex\s*,""",
        ).containsMatchIn(drawSamplerDeclaration.replace(activeStages.fragment, ""))
        val sourceNativeDrawSampler =
            "struct faceData" in activeStages.fragment &&
                "textureBounds" in activeStages.fragment &&
                TEXTURE_ARRAY_INDEX_MARKER !in stages.fragment &&
                activeDrawSampler
        return Stages(
            vertex = faceData(stages.vertex, activeStages.vertex),
            fragment = if (sourceNativeDrawSampler) {
                transformSourceNativeAtlasSampling(
                    sourceNativeFragment,
                    "tex",
                    transformTextureSize = false,
                    requireFullyTransformed = false,
                )
            } else {
                drawSamplerDeclaration.replace(sourceNativeFragment, "")
            },
            geometry = stages.geometry?.let { source ->
                faceData(source, activeStages.geometry.orEmpty())
            },
            tessellationControl = stages.tessellationControl?.let { source ->
                faceData(source, activeStages.tessellationControl.orEmpty())
            },
            tessellationEvaluation = stages.tessellationEvaluation?.let { source ->
                faceData(source, activeStages.tessellationEvaluation.orEmpty())
            },
        )
    }

    private fun transformSourceNativeFaceData(source: String): String {
        if ("struct faceData" !in source || "textureBounds" !in source) return source
        if (
            Regex(
                """(?s)\bvec3\s+textureBounds\s*;\s*uint\s+minosoftTextureArray\s*;\s*""" +
                    """uint\s+minosoftTextureLayer\s*;""",
            ).containsMatchIn(source)
        ) return source

        var transformed = Regex("""\bvec3\s+textureBounds\s*;""").replaceFirst(
            source,
            """
                vec3 textureBounds;
                uint minosoftTextureArray;
                uint minosoftTextureLayer;
            """.trimIndent(),
        )
        val pack = Regex(
            """(?s)\buvec4\s+packFaceData\s*\(\s*faceData\s+data\s*\)\s*\{.*?\n\s*}""",
        )
        require(pack.containsMatchIn(transformed)) {
            "Source-native block atlas faceData is missing packFaceData"
        }
        transformed = pack.replaceFirst(
            transformed,
            """
                uvec4 packFaceData(faceData data) {
                    uvec3 uv = uvec3(clamp(data.textureBounds * 65536.0, 0.0, 65535.0));
                    uvec2 lightmap = uvec2(clamp(data.lightmap * 65536.0, 0.0, 65535.0));
                    uint textureArray = min(data.minosoftTextureArray, 255u);
                    uint textureLayer = min(data.minosoftTextureLayer, 65535u);
                    return uvec4(
                        (uv.x << 16u) | uv.y,
                        (textureLayer << 16u) | uv.z,
                        packUnorm4x8(vec4(data.glColor, float(textureArray) / 255.0)),
                        (lightmap.x << 16u) | lightmap.y
                    );
                }
            """.trimIndent(),
        )
        val unpack = Regex(
            """(?s)\bfaceData\s+getFaceData\s*\(\s*ivec3\s+voxelPos\s*,\s*vec3\s+normal\s*\)\s*\{.*?\n\s*}""",
        )
        require(unpack.containsMatchIn(transformed)) {
            "Source-native block atlas faceData is missing getFaceData"
        }
        transformed = unpack.replaceFirst(
            transformed,
            """
                faceData getFaceData(ivec3 voxelPos, vec3 normal) {
                    uvec4 data = blockDataSSBO.data[getFaceIndex(voxelPos, normal)];
                    vec4 packedColor = unpackUnorm4x8(data.z);
                    return faceData(
                        packedColor.xyz,
                        vec2(data.w >> 16u, data.w & 65535u) / 65536.0,
                        vec3(data.x >> 16u, data.x & 65535u, data.y & 65535u) / 65536.0,
                        uint(round(packedColor.w * 255.0)),
                        data.y >> 16u
                    );
                }
            """.trimIndent(),
        )
        val storedFace = Regex(
            """faceData\s*\(\s*glColor\.rgb\s*,\s*""" +
                """vec2\s*\(\s*lmCoordM\.x\s*\*\s*0\.99\s*\+\s*0\.001\s*,\s*lmCoordM\.y\s*\)\s*,\s*""" +
                """vec3\s*\(\s*origin\s*,\s*textureRad\s*\)\s*\)""",
        )
        require("void storeFaceData" !in transformed || storedFace.containsMatchIn(transformed)) {
            "Source-native block atlas faceData uses an unsupported storeFaceData constructor"
        }
        if (storedFace.containsMatchIn(transformed)) {
            transformed = storedFace.replaceFirst(
                transformed,
                """
                    faceData(
                        glColor.rgb,
                        vec2(lmCoordM.x * 0.99 + 0.001, lmCoordM.y),
                        vec3(origin, textureRad),
                        minosoftTextureArray,
                        uint(minosoftTextureLayer)
                    )
                """.trimIndent(),
            )
        }
        return transformed
    }

    private fun transformSourceNativeAtlasSampling(
        source: String,
        sampler: String,
        transformTextureSize: Boolean = true,
        requireFullyTransformed: Boolean = true,
    ): String {
        val declaration = Regex(
            """(?m)^\s*uniform\s+sampler2D\s+${Regex.escape(sampler)}\s*;\s*$""",
        )
        if (!declaration.containsMatchIn(source)) return source
        var transformed = declaration.replace(source, "")
        val textureSizeDeclaration = Regex(
            """(?m)^\s*uniform\s+(?:(?:lowp|mediump|highp)\s+)?ivec2\s+""" +
                """(atlasSize|gtextureSize)\s*;\s*$""",
        )
        val textureSizeNames = textureSizeDeclaration.findAll(transformed)
            .mapTo(linkedSetOf()) { it.groupValues[1] }
        transformed = textureSizeDeclaration.replace(transformed, "")
        textureSizeNames.forEach { name ->
            transformed = Regex("""\b${Regex.escape(name)}\b""").replace(
                transformed,
                "minosoftSampleTextureArraySize(faceData.minosoftTextureArray, 0)",
            )
        }
        if (transformTextureSize) {
            transformed = Regex(
                """\btextureSize\s*\(\s*${Regex.escape(sampler)}\s*,\s*([^)\r\n]+)\)""",
            ).replace(transformed) {
                "minosoftSampleTextureArraySize(faceData.minosoftTextureArray, ${it.groupValues[1]})"
            }
        }
        transformed = Regex(
            """\btexture(?:2D)?Lod\s*\(\s*${Regex.escape(sampler)}\s*,[ \t]*""",
        ).replace(transformed) {
            "minosoftSampleTextureArrayLod(" +
                "faceData.minosoftTextureArray, float(faceData.minosoftTextureLayer), "
        }
        transformed = Regex(
            """\btexture(?:2D)?\s*\(\s*${Regex.escape(sampler)}\s*,[ \t]*""",
        ).replace(transformed) {
            "minosoftSampleTextureArray(" +
                "faceData.minosoftTextureArray, float(faceData.minosoftTextureLayer), "
        }
        val uncommented = Regex("""(?s)/\*.*?\*/""")
            .replace(Regex("""(?m)//.*$""").replace(transformed, ""), "")
        if (requireFullyTransformed) {
            val unsupported = uncommented.lineSequence()
                .filter { Regex("""\b${Regex.escape(sampler)}\b""").containsMatchIn(it) }
                .take(5)
                .map(String::trim)
                .toList()
            require(unsupported.isEmpty()) {
                "Source-native block atlas sampler $sampler uses an unsupported sampling form: " +
                    unsupported.joinToString(" | ")
            }
        }
        if (
            !Regex("""\bvec4\s+minosoftSampleTextureArrayLod\s*\(""")
                .containsMatchIn(transformed)
        ) {
            require("uTextures[16]" !in transformed) {
                "Source-native block atlas stage declares uTextures without its sampling bridge"
            }
            transformed = insertAfterVersion(transformed, SOURCE_NATIVE_BLOCK_ATLAS_FRAGMENT_HEADER)
        }
        return transformed
    }

    fun suppressedScene(name: String): Stages? {
        val bridge = when (name) {
            "gbuffers_clouds" ->
                "CLOUD CLOUD fog,uCameraPosition,uCloudsColor,uOffset,uViewProjectionMatrix,uYOffset"
            "gbuffers_skybasic" ->
                "SKY_POSITION SKY_COLOR uSkyColor,uSkyViewProjectionMatrix"
            else -> return null
        }
        return Stages(
            """
                #version 330 core
                // minosoft:scene_bridge $bridge
                void main() {
                    gl_Position = vec4(-1.0);
                }
            """.trimIndent(),
            """
                #version 330 core
                void main() {
                    discard;
                }
            """.trimIndent(),
        )
    }

    fun transform(
        name: String,
        phase: ShaderProgramPhase,
        vertex: String,
        fragment: String,
        tessellationControl: String? = null,
        tessellationEvaluation: String? = null,
    ): Stages {
        require((tessellationControl == null) == (tessellationEvaluation == null)) {
            "Iris tessellation control and evaluation stages must be paired"
        }
        val transformed = transformBase(name, phase, vertex, fragment)
        val bridge = tessellationBridge(transformed.vertex, transformed.fragment)
        return transformed.copy(
            tessellationControl = tessellationControl?.let {
                transformTessellationControl(it, bridge)
            },
            tessellationEvaluation = tessellationEvaluation?.let {
                transformTessellationEvaluation(it, bridge)
            },
        )
    }

    private fun transformBase(name: String, phase: ShaderProgramPhase, vertex: String, fragment: String): Stages {
        if ("// minosoft:scene_bridge" in vertex) {
            return Stages(vertex, fragment)
        }
        if (name == "dh_terrain" || name == "dh_water" || name == "dh_shadow") {
            return transformDistantTerrain(name, vertex, fragment)
        }
        if (!isLegacy(vertex) && !isLegacy(fragment)) {
            if (phase in FULLSCREEN_PHASES) {
                return Stages(
                    transformModernFullscreenVertex(vertex),
                    transformModernFullscreenFragment(fragment),
                )
            }
            if (name == "gbuffers_clouds") {
                return Stages(
                    transformCloudVertex(vertex),
                    transformCloudFragment(fragment),
                )
            }
            if (name == "gbuffers_skybasic") {
                return Stages(
                    transformModernSkyBasicVertex(vertex),
                    transformModernSkyBasicFragment(fragment),
                )
            }
            if (name == "gbuffers_weather") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_WEATHER_VERTEX_BODY),
                    transformModernSceneFragment(fragment, MODERN_WEATHER_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_skytextured") {
                return Stages(
                    transformModernSkyVertex(vertex),
                    transformModernSceneFragment(fragment, MODERN_SKY_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_damagedblock") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_DAMAGED_BLOCK_VERTEX_BODY),
                    transformModernSceneFragment(fragment, MODERN_DAMAGED_BLOCK_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_basic" || name == "gbuffers_line") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_LINE_VERTEX_BODY),
                    transformCoreFragmentOutputs(
                        insertAfterVersion(
                            transformNeutralMaterial(
                                transformNeutralDiffuse(
                                    core(fragment).replace(Regex("""\bvarying\b"""), "in"),
                                ),
                            ),
                            NEUTRAL_MATERIAL_FUNCTIONS,
                        ),
                    ),
                )
            }
            if (
                name == "gbuffers_entities" ||
                name == "gbuffers_entities_translucent" ||
                name == "gbuffers_entities_glowing" ||
                name == "gbuffers_spidereyes"
            ) {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_ENTITY_VERTEX_BODY),
                    transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_block" || name == "gbuffers_block_translucent") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_BLOCK_VERTEX_BODY),
                    transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_beaconbeam") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_BEACON_VERTEX_BODY),
                    transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_hand" || name == "gbuffers_hand_water") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_HAND_VERTEX_BODY),
                    transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
                )
            }
            if (name == "gbuffers_armor_glint") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_GLINT_VERTEX_BODY),
                    transformModernSceneFragment(
                        fragment,
                        MODERN_GLINT_FRAGMENT_HEADER,
                        bridgeLegacyColor = false,
                    ),
                )
            }
            if (name == "gbuffers_lightning") {
                return Stages(
                    replaceAfterVersion(vertex, MODERN_LIGHTNING_VERTEX_BODY),
                    transformModernLightningFragment(fragment),
                )
            }
            val terrain = phase == ShaderProgramPhase.TERRAIN ||
                phase == ShaderProgramPhase.SHADOW ||
                phase == ShaderProgramPhase.DISTANT_HORIZONS ||
                name in TERRAIN_FALLBACKS
            if (terrain && usesCompatibilityTerrain(vertex, fragment)) {
                val terrainVertex = transformModernTerrainVertex(vertex, phase == ShaderProgramPhase.SHADOW)
                return Stages(
                    if (phase == ShaderProgramPhase.SHADOW) {
                        wrapAfterVersion(
                            terrainVertex,
                            MODERN_SHADOW_SCENE_CONDITION,
                            modernShadowSceneVertexBody(vertex),
                        )
                    } else if (name == "gbuffers_textured") {
                        wrapAfterVersion(
                            terrainVertex,
                            MODERN_TEXTURED_SCENE_CONDITION,
                            MODERN_TEXTURED_SCENE_VERTEX_BODY,
                        )
                    } else {
                        terrainVertex
                    },
                    transformModernTerrainFragment(fragment),
                )
            }
            return Stages(if (terrain) bridgePackedTerrainInputs(vertex) else vertex, fragment)
        }
        return when {
            phase == ShaderProgramPhase.FINAL -> Stages(
                transformFinalVertex(vertex),
                transformFinalFragment(fragment),
            )
            phase in FULLSCREEN_PHASES -> Stages(
                transformModernFullscreenVertex(vertex),
                transformModernFullscreenFragment(fragment),
            )
            phase == ShaderProgramPhase.SHADOW -> {
                val terrainVertex = transformModernTerrainVertex(vertex, shadow = true)
                Stages(
                    wrapAfterVersion(
                        terrainVertex,
                        MODERN_SHADOW_SCENE_CONDITION,
                        modernShadowSceneVertexBody(vertex),
                    ),
                    transformModernTerrainFragment(fragment),
                )
            }
            name == "gbuffers_clouds" -> Stages(
                transformCloudVertex(vertex),
                transformCloudFragment(fragment),
            )
            name == "gbuffers_skybasic" -> Stages(
                transformModernSkyBasicVertex(vertex),
                transformModernSkyBasicFragment(fragment),
            )
            name == "gbuffers_weather" -> Stages(
                replaceAfterVersion(vertex, MODERN_WEATHER_VERTEX_BODY),
                transformModernSceneFragment(fragment, MODERN_WEATHER_FRAGMENT_HEADER),
            )
            name == "gbuffers_skytextured" -> Stages(
                transformSkyTextureVertex(vertex),
                transformSceneTextureFragment(fragment),
            )
            name == "gbuffers_damagedblock" -> Stages(
                replaceAfterVersion(vertex, MODERN_DAMAGED_BLOCK_VERTEX_BODY),
                transformModernSceneFragment(fragment, MODERN_DAMAGED_BLOCK_FRAGMENT_HEADER),
            )
            name == "gbuffers_basic" || name == "gbuffers_line" -> Stages(
                replaceAfterVersion(vertex, MODERN_LINE_VERTEX_BODY),
                transformCoreFragmentOutputs(
                    insertAfterVersion(
                        transformNeutralMaterial(
                            transformNeutralDiffuse(
                                core(fragment).replace(Regex("""\bvarying\b"""), "in"),
                            ),
                        ),
                        NEUTRAL_MATERIAL_FUNCTIONS,
                    ),
                ),
            )
            name == "gbuffers_entities" ||
                name == "gbuffers_entities_translucent" ||
                name == "gbuffers_entities_glowing" ||
                name == "gbuffers_spidereyes" -> Stages(
                replaceAfterVersion(vertex, MODERN_ENTITY_VERTEX_BODY),
                transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
            )
            name == "gbuffers_block" || name == "gbuffers_block_translucent" -> Stages(
                replaceAfterVersion(vertex, MODERN_BLOCK_VERTEX_BODY),
                transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
            )
            name == "gbuffers_beaconbeam" -> Stages(
                replaceAfterVersion(vertex, MODERN_BEACON_VERTEX_BODY),
                transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
            )
            name == "gbuffers_hand" || name == "gbuffers_hand_water" -> Stages(
                replaceAfterVersion(vertex, MODERN_HAND_VERTEX_BODY),
                transformModernSceneFragment(fragment, MODERN_ENTITY_FRAGMENT_HEADER),
            )
            name == "gbuffers_armor_glint" -> Stages(
                replaceAfterVersion(vertex, MODERN_GLINT_VERTEX_BODY),
                transformModernSceneFragment(
                    fragment,
                    MODERN_GLINT_FRAGMENT_HEADER,
                    bridgeLegacyColor = false,
                ),
            )
            name == "gbuffers_lightning" -> Stages(
                replaceAfterVersion(vertex, MODERN_LIGHTNING_VERTEX_BODY),
                transformModernLightningFragment(fragment),
            )
            name in TERRAIN_FALLBACKS -> Stages(
                transformTerrainVertex(vertex),
                transformTerrainFragment(fragment),
            )
            else -> Stages(vertex, fragment)
        }
    }

    /**
     * Adapts Iris's Distant Horizons fixed-function surface to Minosoft's
     * compact detached-tile ABI. The packed normal/material word is deliberately
     * decoded here instead of leaking a provider-specific vertex layout into
     * the graph producer.
     */
    private fun transformDistantTerrain(name: String, vertex: String, fragment: String): Stages {
        val modelView = if (name == "dh_shadow") "shadowModelView" else "gbufferModelView"
        val projection = if (name == "dh_shadow") "shadowProjection" else "dhProjection"
        var transformedVertex = transformNeutralMaterial(core(vertex))
            .replace(
                Regex(
                    """(?m)^\s*uniform\s+mat4\s+""" +
                        """(?:${Regex.escape(modelView)}|${Regex.escape(projection)})\s*;\s*$""",
                ),
                "",
            )
        transformedVertex = transformShaderTokens(
            source = transformedVertex,
            replacements = mapOf(
                "attribute" to "in",
                "varying" to "out",
                "gl_Vertex" to "vec4(vinPosition + uPageOffset, 1.0)",
                "gl_Color" to "minosoftDhColor()",
                "gl_NormalMatrix" to "mat3($modelView)",
                "gl_Normal" to "minosoftDhNormal()",
                "gl_ModelViewMatrix" to modelView,
                "gl_ProjectionMatrix" to projection,
                "gl_MultiTexCoord0" to "vec4(0.0, 0.0, 0.0, 1.0)",
                "gl_MultiTexCoord1" to "vec4(minosoftDhLight(), 0.0, 1.0)",
                "dhMaterialId" to "minosoftDhMaterialId()",
            ),
            indexedArrayReplacements = mapOf("gl_TextureMatrix" to "mat4(1.0)"),
        )
        transformedVertex = renameMain(transformedVertex, "minosoftDhPackMain")
        transformedVertex = insertAfterVersion(
            transformedVertex,
            DISTANT_TERRAIN_VERTEX_HEADER +
                "\nuniform mat4 $modelView;\nuniform mat4 $projection;\n$NEUTRAL_MATERIAL_FUNCTIONS",
        )
        transformedVertex += """

            void main() {
                minosoftDhVertexColor = minosoftDhColor();
                minosoftDhPackMain();
            }
        """.trimIndent().prependIndent("\n")

        val transformedFragment = transformCoreFragmentOutputs(
            insertAfterVersion(
                transformNeutralMaterial(
                    core(fragment)
                        .replace(Regex("""\bvarying\b"""), "in")
                        .replace(MODERN_DIFFUSE_SAMPLER, "")
                        .replace(
                            Regex("""\btexture\s*\(\s*tex\s*,[^)]*\)"""),
                            "minosoftDhVertexColor",
                        ),
                ),
                "in vec4 minosoftDhVertexColor;\n$NEUTRAL_MATERIAL_FUNCTIONS",
            ),
        )
        return Stages(transformedVertex, transformedFragment)
    }

    /**
     * Iris/Sodium packs describe color and light as separate logical inputs.
     * Minosoft retains both in one lossless uint for the current terrain ABI,
     * so unpack those logical inputs in GLSL and make the bridge explicit to
     * semantic validation.
     */
    private fun bridgePackedTerrainInputs(source: String): String {
        val hasColor = SODIUM_COLOR.containsMatchIn(source)
        val light = SODIUM_LIGHT.find(source)
        if (!hasColor && light == null) return source

        var transformed = source
        if (hasColor) {
            transformed = SODIUM_COLOR.replace(transformed, "")
                .replace(Regex("""\bvaColor\b"""), "minosoftPackedColor()")
        }
        if (light != null) {
            val type = light.groupValues[1]
            val name = light.groupValues[2]
            transformed = SODIUM_LIGHT.replace(transformed, "")
                .replace(Regex("""\b${Regex.escape(name)}\b"""), "minosoftPackedLight()")
            val constructor = if (type == "ivec2") "ivec2" else if (type == "uvec2") "uvec2" else "vec2"
            transformed = insertAfterVersion(
                transformed,
                packedTerrainHeader(hasColor, constructor),
            )
        } else {
            transformed = insertAfterVersion(transformed, packedTerrainHeader(hasColor, null))
        }
        return transformed
    }

    private fun packedTerrainHeader(color: Boolean, lightType: String?): String = buildString {
        appendLine("// minosoft:terrain_bridge packed-color-light")
        appendLine("layout (location = 3) in float vinLightTint;")
        appendLine("uint minosoftPackedLightTint() { return floatBitsToUint(vinLightTint); }")
        if (color) {
            appendLine(
                """
                    vec4 minosoftPackedColor() {
                        uint packed = minosoftPackedLightTint();
                        return vec4(
                            float((packed >> 16u) & 0xFFu),
                            float((packed >> 8u) & 0xFFu),
                            float(packed & 0xFFu),
                            255.0
                        ) / 255.0;
                    }
                """.trimIndent(),
            )
        }
        if (lightType != null) {
            appendLine(
                """
                    $lightType minosoftPackedLight() {
                        uint light = minosoftPackedLightTint() >> 24u;
                        return $lightType((light & 0xFu) * 16u, (light >> 4u) * 16u);
                    }
                """.trimIndent(),
            )
        }
    }.trimEnd()

    /**
     * Preserves the selected legacy pack's fragment behavior while adapting
     * Minosoft's retained point-particle ABI into the quads classic packs
     * receive from Minecraft.
     */
    fun particleFallback(fragment: String): Stages {
        require("minosoftSampleTexture" in fragment) {
            "Particle fallback fragment does not expose the retained texture-array sampler"
        }
        return Stages(
            vertex = PARTICLE_VERTEX_BODY,
            fragment = fragment,
            geometry = PARTICLE_GEOMETRY_BODY,
        )
    }

    /**
     * Mirrors Iris' CommonTransformer alpha-test injection: execute the pack
     * fragment first, then test the final color written to attachment zero.
     * An explicit ALWAYS override is intentionally distinct from no override,
     * because it suppresses a route's vanilla alpha-test default.
     */
    fun alphaTest(fragment: String, test: IrisAlphaTest?): String {
        if (test == null || test.function == IrisAlphaTestFunction.ALWAYS) return fragment
        val output = fragmentOutputZero(fragment) ?: return fragment
        val packFragment = renameMain(fragment, "minosoftAlphaTestPackMain")
        val body = when (test.function) {
            IrisAlphaTestFunction.ALWAYS -> return fragment
            IrisAlphaTestFunction.NEVER -> "discard;"
            else -> {
                val operator = requireNotNull(test.function.operator)
                val reference = test.reference.toString()
                "if (!($output.a $operator $reference)) { discard; }"
            }
        }
        return packFragment + """

            void main() {
                minosoftAlphaTestPackMain();
                // minosoft:alpha_test ${test.function.name} ${test.reference}
                $body
            }
        """.trimIndent().prependIndent("\n")
    }

    private fun fragmentOutputZero(fragment: String): String? {
        val explicit = FRAGMENT_OUTPUT.findAll(fragment)
            .firstOrNull { match -> match.groupValues[1].toIntOrNull() == 0 }
            ?.groupValues
            ?.get(2)
        if (explicit != null) return explicit

        val unlocated = UNLOCATED_FRAGMENT_OUTPUT.findAll(fragment)
            .map { it.groupValues[1] }
            .toList()
        return unlocated.singleOrNull()
    }

    fun specializeTextureArrays(source: String, count: Int): String {
        require(count in 1..16) { "Iris host texture-array count must be between 1 and 16" }
        val slots = (0 until count).toList()
        return specializeTextureArrays(source, slots, slots)
    }

    /**
     * Compacts sparse physical texture-array units for one program. Generated
     * bridge switches continue to branch on the global array index carried by
     * the mesh, but address a dense sampler declaration at link time.
     */
    fun specializeTextureArrays(
        source: String,
        physicalSlots: List<Int>,
        companionSlots: List<Int> = physicalSlots,
    ): String {
        require(physicalSlots.isNotEmpty()) { "Iris host texture-array slots must not be empty" }
        require(physicalSlots == physicalSlots.distinct().sorted()) {
            "Iris host texture-array slots must be sorted and unique: $physicalSlots"
        }
        require(physicalSlots.all { it in 0 until 16 }) {
            "Iris host texture-array slots must be between 0 and 15: $physicalSlots"
        }
        require(companionSlots == companionSlots.distinct().sorted()) {
            "Iris companion texture-array slots must be sorted and unique: $companionSlots"
        }
        require(companionSlots.all { it in physicalSlots }) {
            "Iris companion texture-array slots must be a subset of host slots: $companionSlots"
        }
        if ("uTextures[16]" !in source && "minosoftMaterialLogicalUvCase" !in source) return source
        val compactByPhysical = physicalSlots.withIndex().associate { (compact, physical) ->
            physical to compact
        }
        return source
            .replace("uTextures[16]", "uTextures[${physicalSlots.size}]")
            .splitToSequence('\n')
            .mapNotNull { line ->
                val logicalUv = HOST_LOGICAL_UV_CASE.matchEntire(line)
                if (logicalUv != null) {
                    val indentation = logicalUv.groupValues[1]
                    val physical = logicalUv.groupValues[2].toInt()
                    if (physical !in physicalSlots) return@mapNotNull null
                    val function = if (physical in companionSlots) {
                        "minosoftStaticLogicalUv"
                    } else {
                        "minosoftDynamicLogicalUv"
                    }
                    return@mapNotNull "${indentation}case ${physical}u: return $function(uv);"
                }
                val materialSize = HOST_MATERIAL_SIZE_CASE.matchEntire(line)
                if (materialSize != null) {
                    val indentation = materialSize.groupValues[1]
                    val physical = materialSize.groupValues[2].toInt()
                    if (physical !in physicalSlots) return@mapNotNull null
                    val compact = compactByPhysical.getValue(physical)
                    val function = if (physical in companionSlots) {
                        "minosoftStaticTextureSize"
                    } else {
                        "minosoftDynamicTextureSize"
                    }
                    return@mapNotNull "${indentation}case ${physical}u: return " +
                        "$function(textureSize(uTextures[$compact], lod));"
                }
                val companion = HOST_COMPANION_TEXTURE_CASE.matchEntire(line)
                if (companion != null) {
                    val indentation = companion.groupValues[1]
                    val physical = companion.groupValues[2].toInt()
                    if (physical !in physicalSlots) return@mapNotNull null
                    if (physical !in companionSlots) {
                        val neutral = when (companion.groupValues[3]) {
                            "Normal" -> "vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0)"
                            "Specular" -> "vec4(0.0, 0.0, 0.0, 1.0)"
                            else -> error("Unknown Iris companion kind")
                        }
                        return@mapNotNull "${indentation}case ${physical}u: return $neutral;"
                    }
                    val compact = compactByPhysical.getValue(physical)
                    val expression = companion.groupValues[4]
                        .replace("uTextures[$physical]", "uTextures[$compact]")
                    return@mapNotNull "${indentation}case ${physical}u: return $expression;"
                }
                val match = HOST_TEXTURE_CASE.matchEntire(line) ?: return@mapNotNull line
                val physical = match.groupValues[1].toInt()
                val compact = compactByPhysical[physical] ?: return@mapNotNull null
                val coordinate = if (physical in companionSlots) {
                    "minosoftStaticTextureCoordinate"
                } else {
                    "minosoftDynamicTextureCoordinate"
                }
                val gradient = if (physical in companionSlots) {
                    "minosoftStaticTextureGradient"
                } else {
                    "minosoftDynamicTextureGradient"
                }
                line
                    .replace("uTextures[$physical]", "uTextures[$compact]")
                    .replace("minosoftMaterialTextureCoordinate", coordinate)
                    .replace("minosoftMaterialTextureGradient", gradient)
            }
            .joinToString("\n")
    }

    /**
     * GLSL requires active extension directives before declarations. Iris
     * performs this relocation after include expansion; Minosoft's ABI bridge
     * can otherwise appear before a pack's original second-line directive.
     * Conditional directives stay in place because moving them would change
     * their preprocessor semantics.
     */
    fun relocateUnconditionalExtensions(source: String): String {
        val lines = source.lines().toMutableList()
        val extensions = mutableListOf<String>()
        var conditionalDepth = 0
        for (index in lines.indices) {
            val trimmed = lines[index].trimStart()
            when {
                trimmed.startsWith("#if ") ||
                    trimmed.startsWith("#if(") ||
                    trimmed.startsWith("#ifdef ") ||
                    trimmed.startsWith("#ifndef ") -> conditionalDepth++

                trimmed.startsWith("#endif") -> conditionalDepth = (conditionalDepth - 1).coerceAtLeast(0)
                conditionalDepth == 0 && trimmed.startsWith("#extension ") -> {
                    extensions += lines[index]
                    lines[index] = ""
                }
            }
        }
        if (extensions.isEmpty()) return source
        val versionIndex = lines.indexOfFirst { it.trimStart().startsWith("#version ") }
        if (versionIndex < 0) return source
        lines.addAll(versionIndex + 1, extensions.distinct())
        return lines.joinToString("\n")
    }

    private fun isLegacy(source: String): Boolean =
        VERSION.find(source)?.groupValues?.get(1)?.toIntOrNull()?.let { it < 130 } == true

    private fun usesCompatibilityTerrain(vertex: String, fragment: String): Boolean {
        return Regex("""\bgl_(?:Vertex|Color|MultiTexCoord[01]|ModelViewMatrix|ProjectionMatrix)\b""")
            .containsMatchIn(vertex) &&
            MODERN_DIFFUSE_SAMPLER.containsMatchIn(fragment)
    }

    private fun transformModernFullscreenVertex(source: String): String {
        var transformed = transformModernFullscreenTokens(core(source))
        transformed = insertAfterVersion(
            transformed,
            """
                // minosoft:fullscreen_bridge position-uv
                layout (location = 0) in vec2 minosoftFullscreenPosition;
                layout (location = 1) in vec2 minosoftFullscreenUv;
            """.trimIndent(),
        )
        return ensureCoreFogUniforms(initializeFullscreenOutputs(transformed))
    }

    /**
     * Rewrites the fixed-function fullscreen vocabulary in one bounded pass.
     * Expanded community-pack stages can be close to the source-size limit;
     * applying a separate global regular expression for every token made the
     * first render frame scale with the token catalog rather than source size.
     */
    private fun transformModernFullscreenTokens(source: String): String {
        return transformShaderTokens(
            source = source,
            replacements = mapOf(
                "varying" to "out",
                "attribute" to "in",
                "gl_Vertex" to "vec4(minosoftFullscreenPosition, 0.0, 1.0)",
                "gl_MultiTexCoord0" to "vec4(minosoftFullscreenUv, 0.0, 1.0)",
                "gl_MultiTexCoord1" to "vec4(1.0)",
                "gl_Color" to "vec4(1.0)",
            ),
            emptyCallReplacements = mapOf(
                "ftransform" to "vec4(minosoftFullscreenPosition, 0.0, 1.0)",
            ),
            indexedArrayReplacements = mapOf("gl_TextureMatrix" to "mat4(1.0)"),
        )
    }

    private fun transformShaderTokens(
        source: String,
        replacements: Map<String, String>,
        emptyCallReplacements: Map<String, String> = emptyMap(),
        indexedArrayReplacements: Map<String, String> = emptyMap(),
    ): String {
        val replacementsByLength = replacements.keys.groupBy(String::length)
        val emptyCallsByLength = emptyCallReplacements.keys.groupBy(String::length)
        val indexedArraysByLength = indexedArrayReplacements.keys.groupBy(String::length)
        val output = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            if (!source[cursor].isShaderIdentifierStart()) {
                output.append(source[cursor++])
                continue
            }

            val start = cursor++
            while (cursor < source.length && source[cursor].isShaderIdentifierPart()) cursor++
            val end = cursor
            val tokenLength = end - start
            val replacementName = replacementsByLength[tokenLength]?.firstOrNull { candidate ->
                source.regionMatches(start, candidate, 0, candidate.length)
            }
            if (replacementName != null) {
                output.append(replacements.getValue(replacementName))
                continue
            }

            val emptyCallName = emptyCallsByLength[tokenLength]?.firstOrNull { candidate ->
                source.regionMatches(start, candidate, 0, candidate.length)
            }
            if (emptyCallName != null) {
                var suffix = source.skipShaderWhitespace(end)
                if (suffix < source.length && source[suffix] == '(') {
                    suffix = source.skipShaderWhitespace(suffix + 1)
                    if (suffix < source.length && source[suffix] == ')') {
                        output.append(emptyCallReplacements.getValue(emptyCallName))
                        cursor = suffix + 1
                        continue
                    }
                }
            }

            val indexedArrayName = indexedArraysByLength[tokenLength]?.firstOrNull { candidate ->
                source.regionMatches(start, candidate, 0, candidate.length)
            }
            if (indexedArrayName != null) {
                var suffix = source.skipShaderWhitespace(end)
                if (suffix < source.length && source[suffix] == '[') {
                    suffix = source.skipShaderWhitespace(suffix + 1)
                    val digitsStart = suffix
                    while (suffix < source.length && source[suffix] in '0'..'9') suffix++
                    if (suffix > digitsStart) {
                        suffix = source.skipShaderWhitespace(suffix)
                        if (suffix < source.length && source[suffix] == ']') {
                            output.append(indexedArrayReplacements.getValue(indexedArrayName))
                            cursor = suffix + 1
                            continue
                        }
                    }
                }
            }

            output.append(source, start, end)
        }
        return output.toString()
    }

    private fun String.skipShaderWhitespace(start: Int): Int {
        var cursor = start
        while (cursor < length && this[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun transformModernFullscreenFragment(source: String): String =
        transformCoreFragmentOutputs(core(source).replace(Regex("""\bvarying\b"""), "in"))

    private fun initializeFullscreenOutputs(source: String): String {
        var conditionalDepth = 0
        val initializers = source.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("#if ") ||
                        trimmed.startsWith("#if(") ||
                        trimmed.startsWith("#ifdef ") ||
                        trimmed.startsWith("#ifndef ") -> {
                        conditionalDepth++
                        return@mapNotNull null
                    }

                    trimmed.startsWith("#endif") -> {
                        conditionalDepth = (conditionalDepth - 1).coerceAtLeast(0)
                        return@mapNotNull null
                    }
                }
                if (conditionalDepth != 0) return@mapNotNull null
                val match = FULLSCREEN_OUTPUT.matchEntire(line) ?: return@mapNotNull null
                val type = match.groupValues[1]
                val name = match.groupValues[2]
                "$name = $type(0);"
            }
            .distinct()
            .toList()
        if (initializers.isEmpty()) return source
        val main = Regex("""\bvoid\s+main\s*\(\s*\)\s*\{""").find(source) ?: return source
        val insertion = main.range.last + 1
        return source.substring(0, insertion) +
            "\n" + initializers.joinToString("\n") +
            source.substring(insertion)
    }

    private fun transformModernTerrainVertex(source: String, shadow: Boolean): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "out")
            .replace(Regex("""\battribute\b"""), "in")
            .replace(MODERN_TERRAIN_ATTRIBUTE, "")
            .replace(MODERN_DIFFUSE_SAMPLER, "")
            .replace(MODERN_SPECULAR_SAMPLER, "")
            .replace(MODERN_NORMAL_SAMPLER, "")
            .replace(
                Regex("""\bftransform\s*\(\s*\)"""),
                if (shadow) {
                    "shadowProjection * shadowModelView * vec4(vinPosition, 1.0)"
                } else {
                    "gbufferProjection * gbufferModelView * vec4(vinPosition, 1.0)"
                },
            )
            .replace(Regex("""\bmc_Entity\b"""), "minosoftMcEntity")
            .replace(Regex("""\bat_midBlock\b"""), "minosoftMidBlock.xyz")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(vinPosition, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "minosoftTerrainColor()")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(gbufferModelView)")
            .replace(Regex("""\bgl_Normal\b"""), "vaNormal")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "gbufferModelView")
            .replace(
                Regex("""\bgl_ProjectionMatrix\b"""),
                if (shadow) "shadowProjection" else "gbufferProjection",
            )
            .replace(
                Regex("""gl_TextureMatrix\s*\[\s*[01]\s*]\s*\*\s*mc_midTexCoord"""),
                "vec4(mc_midTexCoord, 0.0, 1.0)",
            )
            .replace(Regex("""gl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(minosoftTerrainUv(), 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(minosoftTerrainLightUv(), 0.0, 1.0)")
        transformed = renameMain(transformed, "minosoftPackMain")
        transformed = insertAfterVersion(
            transformed,
            MATERIAL_LOGICAL_UV_FUNCTIONS + "\n" +
                if (shadow) MODERN_SHADOW_TERRAIN_VERTEX_HEADER else MODERN_TERRAIN_VERTEX_HEADER,
        )
        return transformed + """

            void main() {
                uint minosoftTextureBits = floatBitsToUint(vinTexture);
                minosoftTextureArray = minosoftTextureBits >> 28u;
                minosoftTextureLayer = float((minosoftTextureBits >> 12u) & 0xFFFFu);
                minosoftPackMain();
            }
        """.trimIndent().prependIndent("\n")
    }

    private fun transformModernTerrainFragment(source: String): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(MODERN_DIFFUSE_SAMPLER, "")
            .replace(Regex("""\buniform\s+sampler2D\s+(?:gtexture|tex|texture)\s*;"""), "")
            .replace(
                Regex("""\bvec4\s+textureAF\s*\(\s*sampler2D\s+texSampler\s*,"""),
                "vec4 textureAF(",
            )
            .replace(
                Regex("""\btextureAF\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "textureAF(",
            )
            .replace(
                Regex("""\btexture(?:2D)?Lod\s*\(\s*texSampler\s*,"""),
                "minosoftSampleTextureLod(",
            )
            .replace(Regex("""\bread_tex\s*\(\s*(?:gtexture|tex|texture)\s*\)"""), "minosoftSampleTexture(uv, lod_bias)")
            .replace(
                Regex("""\btextureGrad\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftSampleTextureGrad(",
            )
            .replace(
                Regex("""\btextureLod\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftSampleTextureLod(",
            )
            .replace(
                Regex("""\btexture\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftSampleTexture(",
            )
        transformed = transformModernTextureFunctions(
            transformed,
            "minosoftSampleTexture",
            "minosoftSampleTextureLod",
            "minosoftSampleTextureGrad",
            "minosoftSampleTextureFetch",
            "minosoftSampleTextureSize",
        )
        transformed = transformMaterialCompanions(transformed)
        transformed = insertAfterVersion(
            transformed,
            MODERN_TERRAIN_FRAGMENT_HEADER + "\n" + MODERN_TERRAIN_MATERIAL_FUNCTIONS,
        )
        return transformCoreFragmentOutputs(transformed)
    }

    private fun transformModernSkyVertex(source: String): String {
        var transformed = core(source)
            .replace(MODERN_TERRAIN_ATTRIBUTE, "")
            .replace(MODERN_SPECULAR_SAMPLER, "")
            .replace(MODERN_NORMAL_SAMPLER, "")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(minosoftSkyPosition(), 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "minosoftSkyColor()")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "mat4(1.0)")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "minosoftSkyMatrix()")
            .replace(
                Regex("""\bftransform\s*\(\s*\)"""),
                "minosoftSkyMatrix() * vec4(minosoftSkyPosition(), 1.0)",
            )
            .replace(Regex("""gl_TextureMatrix\s*\[\s*\d+\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(minosoftSkyUv(), 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(1.0)")
        transformed = renameMain(transformed, "minosoftPackMain")
        transformed = insertAfterVersion(
            transformed,
            MATERIAL_LOGICAL_UV_FUNCTIONS + "\n" + MODERN_SKY_VERTEX_HEADER,
        )
        return transformed + """

            void main() {
                minosoftPrepareSkyTexture();
                minosoftPackMain();
            }
        """.trimIndent().prependIndent("\n")
    }

    private fun transformModernSceneFragment(
        source: String,
        header: String,
        bridgeLegacyColor: Boolean = true,
    ): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(MODERN_DIFFUSE_SAMPLER, "")
            .replace(
                Regex("""\bread_tex\s*\(\s*(?:gtexture|tex|texture)\s*\)"""),
                "minosoftSampleSceneTexture(uv, lod_bias)",
            )
            .replace(
                Regex("""\btextureGrad\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftSampleSceneTextureGrad(",
            )
            .replace(
                Regex("""\btextureLod\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftSampleSceneTextureLod(",
            )
            .replace(
                Regex("""\btexture\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftSampleSceneTexture(",
            )
        transformed = transformLegacySceneInputs(transformed, bridgeLegacyColor)
        transformed = transformLegacyMaterialSelector(transformed, scene = true)
        transformed = transformModernTextureFunctions(
            transformed,
            "minosoftSampleSceneTexture",
            "minosoftSampleSceneTextureLod",
            "minosoftSampleSceneTextureGrad",
            "minosoftSampleSceneTextureFetch",
            "minosoftSampleSceneTextureSize",
        )
        transformed = transformMaterialCompanions(transformed, scene = true)
        transformed = insertAfterVersion(
            transformed,
            header + "\n" +
                MODERN_SCENE_TEXTURE_FUNCTIONS + "\n" +
                MODERN_SCENE_MATERIAL_FUNCTIONS + "\n" +
                LEGACY_SCENE_MATERIAL_SELECTOR_FUNCTIONS,
        )
        return transformCoreFragmentOutputs(transformed)
    }

    private fun transformLegacySceneInputs(source: String, bridgeLegacyColor: Boolean): String {
        var transformed = source
            .replace(
            Regex("""(?m)^\s*flat\s+in\s+float\s+(HELD_ITEM_BRIGHTNESS|exposure)\s*;\s*$"""),
            "const float $1 = 0.0;",
            )
            .replace(
                Regex(
                    """(?m)^\s*(?:flat\s+)?in\s+float\s+""" +
                        """(VanillaAO|blockID|SSSAMOUNT|EMISSIVE)\s*;\s*$""",
                ),
                "const float $1 = 0.0;",
            )
            .replace(
                Regex(
                    """(?m)^\s*flat\s+in\s+int\s+""" +
                        """(NameTags|LIGHTNING|PORTAL|SIGN)\s*;\s*$""",
                ),
                "const int $1 = 0;",
            )
            .replace(
            Regex(
                """(?m)^\s*flat\s+in\s+vec3\s+""" +
                    """(averageSkyCol_Clouds|WsunVec|WsunVec2)\s*;\s*$""",
            ),
            "const vec3 $1 = vec3(0.0);",
            )
            .replace(
            Regex(
                """(?m)^\s*flat\s+in\s+vec4\s+""" +
                    """(lightCol|dailyWeatherParams0|dailyWeatherParams1)\s*;\s*$""",
            ),
            "const vec4 $1 = vec4(0.0);",
            )

        val bridge = linkedSetOf<String>()
        fun alias(declaration: Regex, define: String, vararg inputs: String) {
            if (!declaration.containsMatchIn(transformed)) return
            transformed = transformed.replace(declaration, "")
            bridge += inputs
            bridge += define
        }
        alias(
            Regex("""(?m)^\s*in\s+vec2\s+texcoord\s*;\s*$"""),
            "#define texcoord texCoord",
            "in vec2 texCoord;",
        )
        if (bridgeLegacyColor) {
            alias(
                Regex("""(?m)^\s*in\s+vec4\s+color\s*;\s*$"""),
                "#define color tint",
                "in vec4 tint;",
            )
        }
        alias(
            Regex("""(?m)^\s*in\s+vec4\s+lmtexcoord\s*;\s*$"""),
            "#define lmtexcoord vec4(uv, lmCoord)",
            "in vec2 uv;",
            "in vec2 lmCoord;",
        )
        alias(
            Regex("""(?m)^\s*in\s+vec4\s+vtexcoord\s*;\s*(?://[^\r\n]*)?$"""),
            "#define vtexcoord vec4(uv, 0.0, 0.0)",
            "in vec2 uv;",
        )
        alias(
            Regex("""(?m)^\s*in\s+vec4\s+vtexcoordam\s*;\s*(?://[^\r\n]*)?$"""),
            "#define vtexcoordam vec4(0.0, 0.0, 1.0, 1.0)",
        )
        alias(
            Regex("""(?m)^\s*in\s+vec4\s+normalMat\s*;\s*$"""),
            "#define normalMat vec4(normal, 1.0)",
            "in vec3 normal;",
        )
        for (name in listOf("FlatNormals", "flatnormal", "shitnormal")) {
            alias(
                Regex("""(?m)^\s*in\s+vec3\s+$name\s*;\s*$"""),
                "#define $name normal",
                "in vec3 normal;",
            )
        }
        alias(
            Regex("""(?m)^\s*in\s+vec3\s+binormal\s*;\s*$"""),
            "#define binormal (normalize(cross(normal, tangent.xyz)) * tangent.w)",
            "in vec3 normal;",
            "in vec4 tangent;",
        )
        val retainedBridge = bridge.filterNot { line ->
            line.startsWith("in ") &&
                Regex("""(?m)^\s*${Regex.escape(line)}\s*$""").containsMatchIn(transformed)
        }
        return if (retainedBridge.isEmpty()) transformed else insertAfterVersion(
            transformed,
            retainedBridge.joinToString("\n"),
        )
    }

    private fun transformModernLightningFragment(source: String): String {
        var transformed = core(source)
            .replace(MODERN_DIFFUSE_SAMPLER, "")
            .replace(Regex("""\bread_tex\s*\(\s*(?:gtexture|tex|texture)\s*\)"""), "minosoftLightningTexture(uv)")
            .replace(
                Regex("""\btextureGrad\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftLightningTextureGrad(",
            )
            .replace(
                Regex("""\btextureLod\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftLightningTexture(",
            )
            .replace(
                Regex("""\btexture\s*\(\s*(?:gtexture|tex|texture)\s*,"""),
                "minosoftLightningTexture(",
            )
        transformed = transformModernTextureFunctions(
            transformed,
            "minosoftLightningTexture",
            "minosoftLightningTexture",
            "minosoftLightningTextureGrad",
            "minosoftLightningTextureFetch",
            "minosoftLightningTextureSize",
        )
        transformed = transformNeutralMaterial(transformed)
        transformed = insertAfterVersion(
            transformed,
            MODERN_LIGHTNING_FRAGMENT_HEADER + "\n" + NEUTRAL_MATERIAL_FUNCTIONS,
        )
        return transformCoreFragmentOutputs(transformed)
    }

    private fun transformModernTextureFunctions(
        source: String,
        sample: String,
        lod: String,
        grad: String,
        fetch: String,
        size: String,
    ): String = transformTextureCalls(
        source = source,
        samplers = setOf("gtexture", "tex", "texture"),
        functions = mapOf(
            "texture2DLod" to lod,
            "textureLod" to lod,
            "texture2DGradARB" to grad,
            "textureGrad" to grad,
            "texture2D" to sample,
            "texture" to sample,
            "texelFetch" to fetch,
            "textureSize" to size,
        ),
    )

    private fun transformMaterialCompanions(source: String, scene: Boolean = false): String {
        val prefix = if (scene) "minosoftSampleScene" else "minosoftSample"
        return transformMaterialSampler(source, "normals", "${prefix}Normal")
            .let { transformMaterialSampler(it, "specular", "${prefix}Specular") }
    }

    private fun transformMaterialSampler(source: String, sampler: String, function: String): String =
        transformTextureCalls(
            source = removeSamplerDeclaration(source, sampler),
            samplers = setOf(sampler),
            functions = mapOf(
                "texture2DLod" to "${function}Lod",
                "textureLod" to "${function}Lod",
                "texture2DGradARB" to "${function}Grad",
                "textureGrad" to "${function}Grad",
                "texture2D" to function,
                "texture" to function,
                "texelFetch" to "${function}Fetch",
                "textureSize" to "${function}Size",
            ),
        )

    private fun transformNeutralMaterial(source: String): String =
        transformNeutralSampler(source, "normals", "minosoftNeutralNormal")
            .let { transformNeutralSampler(it, "specular", "minosoftNeutralSpecular") }

    private fun transformNeutralDiffuse(source: String): String =
        transformModernTextureFunctions(
            source.replace(DIFFUSE_SAMPLER, "").replace(MODERN_DIFFUSE_SAMPLER, ""),
            "minosoftNeutralDiffuse",
            "minosoftNeutralDiffuse",
            "minosoftNeutralDiffuse",
            "minosoftNeutralDiffuse",
            "minosoftNeutralDiffuseSize",
        )

    private fun transformNeutralSampler(source: String, sampler: String, function: String): String =
        transformTextureCalls(
            source = removeSamplerDeclaration(source, sampler),
            samplers = setOf(sampler),
            functions = mapOf(
                "texture2DLod" to function,
                "textureLod" to function,
                "texture2DGradARB" to function,
                "textureGrad" to function,
                "texture2D" to function,
                "texture" to function,
                "texelFetch" to function,
                "textureSize" to "${function}Size",
            ),
        )

    private fun transformTextureCalls(
        source: String,
        samplers: Set<String>,
        functions: Map<String, String>,
    ): String {
        val functionsByLength = functions.keys.groupBy(String::length)
        val samplersByLength = samplers.groupBy(String::length)
        val output = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            if (!source[cursor].isShaderIdentifierStart()) {
                output.append(source[cursor++])
                continue
            }
            val functionStart = cursor++
            while (cursor < source.length && source[cursor].isShaderIdentifierPart()) cursor++
            val functionEnd = cursor
            val functionName = functionsByLength[functionEnd - functionStart]?.firstOrNull { candidate ->
                source.regionMatches(functionStart, candidate, 0, candidate.length)
            }
            if (functionName == null) {
                output.append(source, functionStart, functionEnd)
                continue
            }

            var suffix = source.skipShaderWhitespace(functionEnd)
            if (suffix >= source.length || source[suffix] != '(') {
                output.append(source, functionStart, functionEnd)
                continue
            }
            suffix = source.skipShaderWhitespace(suffix + 1)
            if (suffix >= source.length || !source[suffix].isShaderIdentifierStart()) {
                output.append(source, functionStart, functionEnd)
                continue
            }
            val samplerStart = suffix++
            while (suffix < source.length && source[suffix].isShaderIdentifierPart()) suffix++
            val samplerName = samplersByLength[suffix - samplerStart]?.firstOrNull { candidate ->
                source.regionMatches(samplerStart, candidate, 0, candidate.length)
            }
            if (samplerName == null) {
                output.append(source, functionStart, functionEnd)
                continue
            }
            suffix = source.skipShaderWhitespace(suffix)
            if (suffix >= source.length || source[suffix] != ',') {
                output.append(source, functionStart, functionEnd)
                continue
            }
            output.append(functions.getValue(functionName)).append('(')
            cursor = suffix + 1
        }
        return output.toString()
    }

    private fun removeSamplerDeclaration(source: String, sampler: String): String {
        val output = StringBuilder(source.length)
        var lineStart = 0
        while (lineStart < source.length) {
            val newline = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            val contentEnd = if (newline > lineStart && source[newline - 1] == '\r') newline - 1 else newline
            if (!source.isSamplerDeclarationLine(lineStart, contentEnd, sampler)) {
                output.append(source, lineStart, newline)
            }
            if (newline < source.length) output.append('\n')
            lineStart = newline + 1
        }
        return output.toString()
    }

    private fun String.isSamplerDeclarationLine(start: Int, end: Int, sampler: String): Boolean {
        var cursor = start
        while (cursor < end && this[cursor].isWhitespace()) cursor++
        val uniformEnd = cursor + "uniform".length
        if (uniformEnd > end || !regionMatches(cursor, "uniform", 0, "uniform".length)) return false
        cursor = uniformEnd
        if (cursor >= end || !this[cursor].isWhitespace()) return false
        while (cursor < end && this[cursor].isWhitespace()) cursor++
        val typeEnd = cursor + "sampler2D".length
        if (typeEnd > end || !regionMatches(cursor, "sampler2D", 0, "sampler2D".length)) return false
        cursor = typeEnd
        if (cursor >= end || !this[cursor].isWhitespace()) return false
        while (cursor < end && this[cursor].isWhitespace()) cursor++
        val samplerEnd = cursor + sampler.length
        if (samplerEnd > end || !regionMatches(cursor, sampler, 0, sampler.length)) return false
        cursor = samplerEnd
        while (cursor < end && this[cursor].isWhitespace()) cursor++
        if (cursor >= end || this[cursor++] != ';') return false
        while (cursor < end && this[cursor].isWhitespace()) cursor++
        return cursor == end
    }

    private fun transformModernSkyBasicVertex(source: String): String {
        var transformed = core(source)
            .replace(MODERN_SPECULAR_SAMPLER, "")
            .replace(MODERN_NORMAL_SAMPLER, "")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "minosoftSkyBasicPosition()")
            .replace(Regex("""\bgl_Color\b"""), "minosoftSkyBasicColor()")
            .replace(Regex("""gl_TextureMatrix\s*\[\s*\d+\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(0.0, 0.0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(1.0)")
        transformed = insertAfterVersion(transformed, MODERN_SKY_BASIC_VERTEX_HEADER)
        return transformed
    }

    private fun transformModernSkyBasicFragment(source: String): String {
        val transformed = transformCoreFragmentOutputs(core(source))
        return wrapAfterVersion(
            transformed,
            "defined(MINOSOFT_STATE_ABI_SUN_SCATTER)",
            "void main() { discard; }",
        )
    }

    private fun core(source: String): String =
        transformLegacyShadowLookups(VERSION.replaceFirst(source, "#version 330 core"))
            // GLSL 1.20 shadow2D returns a vec4, while core texture on a
            // sampler2DShadow returns a float. Preserve legacy component
            // swizzles such as shadow2D(...).z after moving to GLSL 330.
            .replace(Regex("""\btexture2DLod\s*\("""), "textureLod(")
            .replace(Regex("""\btexture2DGradARB\s*\("""), "textureGrad(")
            .replace(Regex("""\btexture2D\s*\("""), "texture(")
            .replace(Regex("""\btexelFetch2D\s*\("""), "texelFetch(")
            .replace(Regex("""gl_Fog\s*\.\s*density"""), "fogDensity")
            .replace(Regex("""gl_Fog\s*\.\s*start"""), "fogStart")
            .replace(
                Regex("""gl_Fog\s*\.\s*scale"""),
                "(1.0 / max(fogEnd - fogStart, 0.00001))",
            )
            .replace(Regex("""gl_Fog\s*\.\s*color"""), "iris_FogColor")

    /**
     * Rewrites legacy shadow calls without applying a backtracking expression
     * to pack-controlled source. Calls are deliberately kept on one statement,
     * matching the former transform, while balanced nested arguments are
     * consumed in one forward pass.
     */
    private fun transformLegacyShadowLookups(source: String): String {
        val function = "shadow2D"
        var searchFrom = 0
        var copyFrom = 0
        var transformed: StringBuilder? = null

        while (searchFrom < source.length) {
            val start = source.indexOf(function, searchFrom)
            if (start < 0) break
            val nameEnd = start + function.length
            if ((start > 0 && source[start - 1].isShaderIdentifierPart()) ||
                (nameEnd < source.length && source[nameEnd].isShaderIdentifierPart())
            ) {
                searchFrom = nameEnd
                continue
            }

            var open = nameEnd
            while (open < source.length && source[open].isWhitespace()) open++
            if (open >= source.length || source[open] != '(') {
                searchFrom = nameEnd
                continue
            }

            var cursor = open + 1
            var depth = 1
            while (cursor < source.length && depth > 0) {
                when (source[cursor]) {
                    '\r', '\n', ';' -> break
                    '(' -> depth++
                    ')' -> depth--
                }
                cursor++
            }
            if (depth != 0) {
                if (cursor >= source.length) break
                searchFrom = cursor + 1
                continue
            }

            val close = cursor - 1
            var suffix = cursor
            while (suffix < source.length && source[suffix].isWhitespace()) suffix++
            val directScalar = suffix + 2 <= source.length &&
                source[suffix] == '.' && source[suffix + 1] == 'x' &&
                (suffix + 2 == source.length || !source[suffix + 2].isShaderIdentifierPart())
            val consumedEnd = if (directScalar) suffix + 2 else cursor

            val output = transformed ?: StringBuilder(source.length).also { transformed = it }
            output.append(source, copyFrom, start)
            if (directScalar) {
                output.append("texture(")
            } else {
                output.append("vec4(texture(")
            }
            output.append(source, open + 1, close)
            output.append(if (directScalar) ')' else "))")
            copyFrom = consumedEnd
            searchFrom = consumedEnd
        }

        return transformed?.append(source, copyFrom, source.length)?.toString() ?: source
    }

    private fun Char.isShaderIdentifierStart(): Boolean = this == '_' || isLetter()

    private fun Char.isShaderIdentifierPart(): Boolean = isShaderIdentifierStart() || isDigit()

    private fun tessellationBridge(vertex: String, fragment: String): List<TessellationVarying> =
        HOST_TESSELLATION_VARYING.findAll(vertex).mapNotNull { match ->
            val varying = TessellationVarying(
                flat = match.groupValues[1].isNotEmpty(),
                type = match.groupValues[2],
                name = match.groupValues[3],
            )
            val qualifier = if (varying.flat) """flat\s+""" else ""
            val fragmentInput = Regex(
                """(?m)^\s*${qualifier}in\s+${Regex.escape(varying.type)}\s+""" +
                    """${Regex.escape(varying.name)}\s*;\s*$""",
            )
            varying.takeIf { fragmentInput.containsMatchIn(fragment) }
        }.distinctBy(TessellationVarying::name).toList()

    private fun transformTessellationControl(
        source: String,
        bridge: List<TessellationVarying>,
    ): String {
        var transformed = tessellationCore(source)
        if (bridge.isEmpty()) return transformed

        transformed = renameMain(transformed, "minosoftTessellationControlMain")
        val declarations = bridge.joinToString("\n") { varying ->
            val qualifier = "flat ".takeIf { varying.flat }.orEmpty()
            "${qualifier}in ${varying.type} ${varying.name}[];\n" +
                "${qualifier}out ${varying.type} ${varying.controlName}[];"
        }
        transformed = insertAfterVersion(transformed, declarations)
        val copies = bridge.joinToString("\n") { varying ->
            "${varying.controlName}[gl_InvocationID] = ${varying.name}[gl_InvocationID];"
        }
        return transformed + """

            void main() {
                minosoftTessellationControlMain();
                $copies
            }
        """.trimIndent().prependIndent("\n")
    }

    private fun transformTessellationEvaluation(
        source: String,
        bridge: List<TessellationVarying>,
    ): String {
        var transformed = tessellationCore(source)
        if (bridge.isEmpty()) return transformed
        require(Regex("""\blayout\s*\([^)]*\btriangles\b[^)]*\)\s*in\s*;""").containsMatchIn(transformed)) {
            "Minosoft tessellation varying bridge currently requires a triangle evaluation domain"
        }

        transformed = renameMain(transformed, "minosoftTessellationEvaluationMain")
        val declarations = bridge.joinToString("\n") { varying ->
            val qualifier = "flat ".takeIf { varying.flat }.orEmpty()
            "${qualifier}in ${varying.type} ${varying.controlName}[];\n" +
                "${qualifier}out ${varying.type} ${varying.name};"
        }
        transformed = insertAfterVersion(transformed, declarations)
        val copies = bridge.joinToString("\n") { varying ->
            val value = if (varying.flat) {
                "${varying.controlName}[0]"
            } else {
                "gl_TessCoord.x * ${varying.controlName}[0] + " +
                    "gl_TessCoord.y * ${varying.controlName}[1] + " +
                    "gl_TessCoord.z * ${varying.controlName}[2]"
            }
            "${varying.name} = $value;"
        }
        return transformed + """

            void main() {
                minosoftTessellationEvaluationMain();
                $copies
            }
        """.trimIndent().prependIndent("\n")
    }

    /**
     * Tessellation stages require GLSL 4.00 while sharing Iris's common
     * compatibility aliases with the adjacent graphics stages. Preserve the
     * authored stage interfaces and tessellation layouts; the OpenGL linker is
     * the final authority for cross-stage type and array compatibility.
     */
    private fun tessellationCore(source: String): String {
        val version = requireNotNull(VERSION.find(source)) {
            "Iris tessellation stage is missing a GLSL version directive"
        }.groupValues[1].toInt()
        val compatibilityUniforms = buildList {
            if (
                ("gl_ModelViewMatrix" in source || "gl_NormalMatrix" in source) &&
                !Regex("""\buniform\s+mat4\s+gbufferModelView\s*;""").containsMatchIn(source)
            ) {
                add("uniform mat4 gbufferModelView;")
            }
            if (
                "gl_ProjectionMatrix" in source &&
                !Regex("""\buniform\s+mat4\s+gbufferProjection\s*;""").containsMatchIn(source)
            ) {
                add("uniform mat4 gbufferProjection;")
            }
            if (
                "gl_Fog.density" in source &&
                !Regex("""\buniform\s+float\s+fogDensity\s*;""").containsMatchIn(source)
            ) {
                add("uniform float fogDensity;")
            }
            if (
                ("gl_Fog.start" in source || "gl_Fog.scale" in source) &&
                !Regex("""\buniform\s+float\s+fogStart\s*;""").containsMatchIn(source)
            ) {
                add("uniform float fogStart;")
            }
            if (
                "gl_Fog.scale" in source &&
                !Regex("""\buniform\s+float\s+fogEnd\s*;""").containsMatchIn(source)
            ) {
                add("uniform float fogEnd;")
            }
            if (
                "gl_Fog.color" in source &&
                !Regex("""\buniform\s+vec4\s+iris_FogColor\s*;""").containsMatchIn(source)
            ) {
                add("uniform vec4 iris_FogColor;")
            }
        }
        var transformed = core(source)
            .replaceFirst("#version 330 core", "#version ${maxOf(version, 400)} core")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "gbufferModelView")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "gbufferProjection")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(gbufferModelView)")
            .replace(Regex("""gl_TextureMatrix\s*\[\s*\d+\s*]"""), "mat4(1.0)")
        if (compatibilityUniforms.isNotEmpty()) {
            transformed = insertAfterVersion(transformed, compatibilityUniforms.joinToString("\n"))
        }
        return transformed
    }

    private fun String.markMaterialPhysicalCoordinates(): String = this
        .replace(
            ", coordinate);",
            ", minosoftMaterialTextureCoordinate(coordinate));",
        )
        .replace(
            ", coordinate, bias);",
            ", minosoftMaterialTextureCoordinate(coordinate), bias);",
        )
        .replace(
            ", coordinate, lod);",
            ", minosoftMaterialTextureCoordinate(coordinate), lod);",
        )
        .replace(
            ", coordinate, dx, dy);",
            ", minosoftMaterialTextureCoordinate(coordinate), " +
                "minosoftMaterialTextureGradient(dx), minosoftMaterialTextureGradient(dy));",
        )

    private fun transformCoreFragmentOutputs(source: String): String {
        var transformed = ensureCoreFogUniforms(source)
        val indexed = Regex("""gl_FragData\s*\[\s*(\d+)\s*]""")
            .findAll(transformed)
            .map { it.groupValues[1].toInt() }
            .toSet()
        val usesColor = Regex("""\bgl_FragColor\b""").containsMatchIn(transformed)
        if (indexed.isEmpty() && !usesColor) return transformed
        require(indexed.all { it in 0..15 }) { "Iris fragment output index exceeds the retained draw-buffer limit" }
        transformed = indexed.fold(transformed) { current, index ->
            Regex("""gl_FragData\s*\[\s*$index\s*]""").replace(current, "minosoftFragmentColor$index")
        }
        if (usesColor) {
            transformed = Regex("""\bgl_FragColor\b""").replace(transformed, "minosoftFragmentColor0")
        }
        val locations = indexed + if (usesColor) setOf(0) else emptySet()
        val declarations = locations.sorted().joinToString("\n") { index ->
            "layout (location = $index) out vec4 minosoftFragmentColor$index;"
        }
        return insertAfterVersion(transformed, declarations)
    }

    private fun ensureCoreFogUniforms(source: String): String {
        val uniforms = listOf(
            "fogDensity" to "float",
            "fogStart" to "float",
            "fogEnd" to "float",
            "iris_FogColor" to "vec4",
        ).filter { (name, _) ->
            Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source) &&
                !Regex("""\buniform\s+\w+\s+${Regex.escape(name)}\s*;""").containsMatchIn(source)
        }
        if (uniforms.isEmpty()) return source
        return insertAfterVersion(
            source,
            uniforms.joinToString("\n") { (name, type) -> "uniform $type $name;" },
        )
    }

    private fun transformFinalVertex(source: String): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "out")
            .replace(Regex("""\battribute\b"""), "in")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "vec4(minosoftPosition, 0.0, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "vec4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(minosoftUv, 0.0, 1.0)")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(minosoftPosition, 0.0, 1.0)")
        transformed = insertAfterVersion(
            transformed,
            """
                layout (location = 0) in vec2 minosoftPosition;
                layout (location = 1) in vec2 minosoftUv;
            """.trimIndent(),
        )
        return transformed
    }

    private fun transformFinalFragment(source: String): String {
        val transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(DIFFUSE_SAMPLER, "uniform sampler2D colortex0;")
            .replace(Regex("""\btexture2D\s*\(\s*texture\b"""), "texture(colortex0")
            .replace(Regex("""\btexture\s*\(\s*texture\b"""), "texture(colortex0")
        return transformCoreFragmentOutputs(transformed)
    }

    private fun transformTerrainVertex(source: String): String {
        var transformed = core(source)
            .replace(MC_ENTITY, "")
            .replace(Regex("""\bvarying\b"""), "out")
            .replace(Regex("""\battribute\b"""), "in")
            .replace(
                Regex("""\bftransform\s*\(\s*\)"""),
                "gbufferProjection * gbufferModelView * vec4(vinPosition, 1.0)",
            )
            .replace(Regex("""\bmc_Entity\b(?:\s*\.\s*x)?"""), "minosoftMcEntity.x")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(vinPosition, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "minosoftLegacyColor()")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(gbufferModelView)")
            .replace(Regex("""\bgl_Normal\b"""), "vaNormal")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "gbufferModelView")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "gbufferProjection")
            .replace(Regex("""gl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(minosoftLegacyUv(), 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(minosoftLegacyLightUv(), 0.0, 1.0)")
            .replace(Regex("""\bgl_FogFragCoord\b"""), "minosoftFogFragCoord")
        transformed = renameMain(transformed, "minosoftLegacyMain")
        transformed = insertAfterVersion(
            transformed,
            MATERIAL_LOGICAL_UV_FUNCTIONS + "\n" + TERRAIN_VERTEX_HEADER,
        )
        val terrain = transformed + """

            void main() {
                uint minosoftTextureBits = floatBitsToUint(vinTexture);
                minosoftTextureArray = minosoftTextureBits >> 28u;
                minosoftTextureLayer = float((minosoftTextureBits >> 12u) & 0xFFFFu);
                minosoftLightIndex = floatBitsToUint(vinLightTint) >> 24u;
                minosoftLegacyMain();
            }
        """.trimIndent().prependIndent("\n")
        return wrapAfterVersion(terrain, LEGACY_TEXTURED_SCENE_CONDITION, TEXTURED_SCENE_VERTEX_BODY)
    }

    private fun transformTerrainFragment(source: String): String {
        var transformed = core(source)
            .replace(DIFFUSE_SAMPLER, "")
            .replace(MODERN_DIFFUSE_SAMPLER, "")
            .replace(LIGHTMAP_SAMPLER, "")
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(Regex("""\btexture\s*\(\s*lightmap\s*,"""), "minosoftSampleLightmap(")
            .replace(Regex("""\bgl_FogFragCoord\b"""), "minosoftFogFragCoord")
            .replace(Regex("""gl_Fog\s*\.\s*density"""), "fogDensity")
            .replace(Regex("""gl_Fog\s*\.\s*start"""), "fogStart")
            .replace(
                Regex("""gl_Fog\s*\.\s*scale"""),
                "(1.0 / max(fogEnd - fogStart, 0.00001))",
            )
            .replace(Regex("""gl_Fog\s*\.\s*color"""), "iris_FogColor")
        transformed = transformed.replace(
            Regex("""(?m)^\s*flat\s+in\s+float\s+HELD_ITEM_BRIGHTNESS\s*;\s*$"""),
            """
                #if $LEGACY_TEXTURED_SCENE_CONDITION
                const float HELD_ITEM_BRIGHTNESS = 0.0;
                #else
                flat in float HELD_ITEM_BRIGHTNESS;
                #endif
            """.trimIndent(),
        )
        transformed = transformLegacyTexturedSceneInputs(transformed)
        transformed = transformLegacyMaterialSelector(transformed)
        transformed = transformModernTextureFunctions(
            transformed,
            "minosoftSampleTexture",
            "minosoftSampleTextureLod",
            "minosoftSampleTextureGrad",
            "minosoftSampleTextureFetch",
            "minosoftSampleTextureSize",
        )
        transformed = transformMaterialCompanions(transformed)
        transformed = insertAfterVersion(
            transformed,
            MODERN_TERRAIN_FRAGMENT_HEADER + "\n" +
                MODERN_TERRAIN_MATERIAL_FUNCTIONS + "\n" +
                LEGACY_TERRAIN_FRAGMENT_HEADER + "\n" +
                LEGACY_MATERIAL_SELECTOR_FUNCTIONS,
        )
        return transformCoreFragmentOutputs(transformed)
    }

    private fun transformLegacyTexturedSceneInputs(source: String): String {
        var transformed = source
        fun conditionalInput(regex: Regex, scene: String, authored: String) {
            if (!regex.containsMatchIn(transformed)) return
            transformed = transformed.replace(
                regex,
                """
                    #if $LEGACY_TEXTURED_SCENE_CONDITION
                    $scene
                    #else
                    $authored
                    #endif
                """.trimIndent(),
            )
        }
        conditionalInput(
            Regex("""(?m)^\s*in\s+vec4\s+vtexcoord\s*;\s*(?://[^\r\n]*)?$"""),
            "in vec2 coord0;\n#define vtexcoord vec4(coord0, 0.0, 0.0)",
            "in vec4 vtexcoord;",
        )
        conditionalInput(
            Regex("""(?m)^\s*in\s+vec4\s+vtexcoordam\s*;\s*(?://[^\r\n]*)?$"""),
            "#define vtexcoordam vec4(0.0, 0.0, 1.0, 1.0)",
            "in vec4 vtexcoordam;",
        )
        for (name in listOf("averageSkyCol_Clouds", "WsunVec", "WsunVec2")) {
            conditionalInput(
                Regex("""(?m)^\s*flat\s+in\s+vec3\s+$name\s*;\s*$"""),
                "const vec3 $name = vec3(0.0);",
                "flat in vec3 $name;",
            )
        }
        for (name in listOf("lightCol", "dailyWeatherParams0", "dailyWeatherParams1")) {
            conditionalInput(
                Regex("""(?m)^\s*flat\s+in\s+vec4\s+$name\s*;\s*$"""),
                "const vec4 $name = vec4(0.0);",
                "flat in vec4 $name;",
            )
        }
        return transformed
    }

    private fun transformLegacyMaterialSelector(source: String, scene: Boolean = false): String {
        val declaration = Regex(
            """\btexture2D_POMSwitch\s*\(\s*sampler2D\s+sampler\s*,""",
        )
        val sample = if (scene) {
            "minosoftSampleSelectedSceneMaterial"
        } else {
            "minosoftSampleSelectedMaterial"
        }
        val grad = if (scene) {
            "minosoftSampleSelectedSceneMaterialGrad"
        } else {
            "minosoftSampleSelectedMaterialGrad"
        }
        var transformed = source
            .replace(
                Regex("""\btexture2D_POMSwitch\s*\(\s*(?:texture|gtexture|tex)\s*,"""),
                "texture2D_POMSwitch(0,",
            )
            .replace(
                Regex("""\btexture2D_POMSwitch\s*\(\s*normals\s*,"""),
                "texture2D_POMSwitch(1,",
            )
            .replace(
                Regex("""\btexture2D_POMSwitch\s*\(\s*specular\s*,"""),
                "texture2D_POMSwitch(2,",
            )
        val match = declaration.find(transformed) ?: return transformed
        val replacement = "texture2D_POMSwitch(int minosoftSampler,"
        transformed = transformed.replaceRange(match.range, replacement)
        val bodyStart = transformed.indexOf('{', match.range.first + replacement.length)
        if (bodyStart < 0) return transformed
        var depth = 0
        var bodyEnd = -1
        for (index in bodyStart until transformed.length) {
            when (transformed[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        bodyEnd = index
                        break
                    }
                }
            }
        }
        if (bodyEnd < 0) return transformed
        val body = transformed.substring(bodyStart + 1, bodyEnd)
            .replace(
                Regex("""\btextureGrad\s*\(\s*sampler\s*,"""),
                "$grad(minosoftSampler,",
            )
            .replace(
                Regex("""\btexture\s*\(\s*sampler\s*,"""),
                "$sample(minosoftSampler,",
            )
        return transformed.replaceRange(bodyStart + 1, bodyEnd, body)
    }

    private fun transformCloudVertex(source: String): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "out")
            .replace(Regex("""\battribute\b"""), "in")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(minosoftCloudPosition(), 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "minosoftCloudColor()")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "gbufferModelView")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "gbufferProjection")
            .replace(Regex("""gbufferProjection\s*\*\s*gbufferModelView"""), "uViewProjectionMatrix")
            .replace(Regex("""gl_TextureMatrix\s*\[\s*\d+\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(0.0, 0.0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(1.0)")
            .replace(Regex("""\bgl_FogFragCoord\b"""), "minosoftFogFragCoord")
        transformed = renameMain(transformed, "minosoftLegacyMain")
        transformed = insertAfterVersion(transformed, CLOUD_VERTEX_HEADER)
        return transformed + """

            void main() {
                lmCoord = vec2(1.0);
                minosoftLegacyMain();
                minosoftFogFragCoord = length(minosoftCloudPosition() - uCameraPosition);
            }
        """.trimIndent().prependIndent("\n")
    }

    private fun transformCloudFragment(source: String): String {
        var transformed = core(source)
            .replace(DIFFUSE_SAMPLER, "")
            .replace(MODERN_DIFFUSE_SAMPLER, "")
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(Regex("""\btexture\s*\(\s*texture\s*,"""), "minosoftCloudTexture(")
            .replace(Regex("""\btexture2D\s*\(\s*(?:texture|tex)\s*,"""), "minosoftCloudTexture(")
            .replace(Regex("""\btexture\s*\(\s*tex\s*,"""), "minosoftCloudTexture(")
            .replace(Regex("""\bgl_FogFragCoord\b"""), "minosoftFogFragCoord")
            .replace(Regex("""gl_FragData\s*\[\s*0\s*]"""), "minosoftFragmentColor")
            .replace(Regex("""gl_Fog\s*\.\s*density"""), "0.0")
            .replace(Regex("""gl_Fog\s*\.\s*start"""), "sqrt(max(uFogStart, 0.0))")
            .replace(
                Regex("""gl_Fog\s*\.\s*scale"""),
                "(float(uFogFlags != 0u) / max(sqrt(max(uFogStart + uFogDistance, 0.0)) - " +
                    "sqrt(max(uFogStart, 0.0)), 0.00001))",
            )
            .replace(Regex("""gl_Fog\s*\.\s*color"""), "uFogColor")
        transformed = insertAfterVersion(transformed, CLOUD_FRAGMENT_HEADER)
        return transformed
    }

    private fun transformSkyTextureVertex(source: String): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "out")
            .replace(Regex("""\battribute\b"""), "in")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(vinPosition, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "uTintColor")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "gbufferModelView")
            .replace(Regex("""gl_ProjectionMatrix\s*\*\s*gbufferModelView"""), "uSkyViewProjectionMatrix")
            .replace(
                Regex("""\bftransform\s*\(\s*\)"""),
                "uSkyViewProjectionMatrix * vec4(vinPosition, 1.0)",
            )
            .replace(Regex("""gl_TextureMatrix\s*\[\s*0\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(minosoftSkyUv(), 0.0, 1.0)")
        transformed = renameMain(transformed, "minosoftLegacyMain")
        transformed = insertAfterVersion(
            transformed,
            MATERIAL_LOGICAL_UV_FUNCTIONS + "\n" + SKY_TEXTURE_VERTEX_HEADER,
        )
        val legacySky = transformed + """

            void main() {
                minosoftSceneTextureArray = uTexture >> 28u;
                minosoftSceneTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
                minosoftLegacyMain();
            }
        """.trimIndent().prependIndent("\n")
        return wrapAfterVersion(
            legacySky,
            "defined(MINOSOFT_STATE_ABI_PLANET)",
            PLANET_VERTEX_BODY,
        )
    }

    private fun transformSceneTextureFragment(source: String): String {
        var transformed = core(source)
            .replace(DIFFUSE_SAMPLER, "")
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(Regex("""\btexture\s*\(\s*texture\s*,"""), "minosoftSampleSceneTexture(")
            .replace(Regex("""gl_FragData\s*\[\s*0\s*]"""), "minosoftFragmentColor")
        transformed = insertAfterVersion(transformed, SCENE_TEXTURE_FRAGMENT_HEADER)
        return transformed
    }

    private fun transformBasicVertex(source: String): String {
        val transformed = core(source)
        val version = requireNotNull(VERSION.find(transformed))
        return transformed.substring(0, version.range.last + 1) + "\n" +
            BASIC_VERTEX_BODY + "\n"
    }

    private fun transformBasicFragment(source: String): String {
        var transformed = core(source)
            .replace(Regex("""\bvarying\b"""), "in")
            .replace(Regex("""\bgl_FogFragCoord\b"""), "minosoftFogFragCoord")
            .replace(Regex("""gl_FragData\s*\[\s*0\s*]"""), "minosoftFragmentColor")
            .replace(Regex("""gl_Fog\s*\.\s*density"""), "fogDensity")
            .replace(Regex("""gl_Fog\s*\.\s*start"""), "fogStart")
            .replace(
                Regex("""gl_Fog\s*\.\s*scale"""),
                "(1.0 / max(fogEnd - fogStart, 0.00001))",
            )
            .replace(Regex("""gl_Fog\s*\.\s*color"""), "iris_FogColor")
        transformed = insertAfterVersion(transformed, BASIC_FRAGMENT_HEADER)
        return transformed
    }

    private fun renameMain(source: String, replacement: String): String {
        require(MAIN.containsMatchIn(source)) { "Legacy Iris shader stage has no main function" }
        // Shared Iris includes can retain inactive vertex and fragment main
        // functions in the same resolved source. Rename every candidate; the
        // GLSL preprocessor still selects exactly one authored stage main.
        return MAIN.replace(source, "void $replacement() {")
    }

    private fun insertAfterVersion(source: String, insertion: String): String {
        val version = requireNotNull(VERSION.find(source)) { "Legacy Iris shader stage has no version directive" }
        val offset = version.range.last + 1
        return source.substring(0, offset) + "\n" + insertion + "\n" + source.substring(offset)
    }

    private fun replaceAfterVersion(source: String, replacementBody: String): String {
        val transformed = core(source)
        val version = requireNotNull(VERSION.find(transformed)) { "Iris shader stage has no version directive" }
        val body = if (
            "flat out uint minosoftSceneTextureArray" in replacementBody ||
            "flat out uint minosoftTextureArray" in replacementBody
        ) {
            MATERIAL_LOGICAL_UV_FUNCTIONS + "\n" + replacementBody
        } else {
            replacementBody
        }
        return transformed.substring(0, version.range.last + 1) + "\n" + body + "\n"
    }

    private fun wrapAfterVersion(source: String, condition: String, replacementBody: String): String {
        val version = requireNotNull(VERSION.find(source)) { "Legacy Iris shader stage has no version directive" }
        val offset = version.range.last + 1
        val originalBody = source.substring(offset)
        val body = if (
            "flat out uint minosoftSceneTextureArray" in replacementBody ||
            "flat out uint minosoftTextureArray" in replacementBody
        ) {
            MATERIAL_LOGICAL_UV_FUNCTIONS + "\n" + replacementBody
        } else {
            replacementBody
        }
        return source.substring(0, offset) +
            "\n#if $condition\n$body\n#else\n$originalBody\n#endif\n"
    }

    private val TERRAIN_FALLBACKS = setOf(
        "gbuffers_terrain_solid",
        "gbuffers_terrain_cutout",
        "gbuffers_water",
        "gbuffers_terrain",
        "gbuffers_textured_lit",
        "gbuffers_textured",
        "gbuffers_basic",
    )

    private val MODERN_TERRAIN_VERTEX_HEADER = modernTerrainVertexHeader(shadow = false)
    private val MODERN_SHADOW_TERRAIN_VERTEX_HEADER = modernTerrainVertexHeader(shadow = true)

    private val MATERIAL_LOGICAL_UV_FUNCTIONS = buildString {
        appendLine("vec2 minosoftStaticLogicalUv(vec2 uv) { return vec2(uv.x, uv.y * 3.0); }")
        appendLine("vec2 minosoftDynamicLogicalUv(vec2 uv) { return uv; }")
        appendLine("vec2 minosoftMaterialLogicalUv(vec2 uv, uint textureArray) {")
        appendLine("    switch (textureArray) {")
        for (index in 0 until 16) {
            appendLine("        case ${index}u: return minosoftMaterialLogicalUvCase(uv);")
        }
        appendLine("        default: return uv;")
        appendLine("    }")
        appendLine("}")
    }.trimEnd()

    private val DISTANT_TERRAIN_VERTEX_HEADER = """
        // minosoft:scene_bridge DISTANT_TERRAIN DISTANT_TERRAIN uViewProjectionMatrix,uPageOffset
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinTintColor;
        layout (location = 2) in float vinLight;
        layout (location = 3) in float vinNormalMaterial;
        uniform mat4 uViewProjectionMatrix;
        uniform vec3 uPageOffset;
        out vec4 minosoftDhVertexColor;

        uint minosoftDhNormalMaterial() {
            return floatBitsToUint(vinNormalMaterial);
        }

        int minosoftDhMaterialId() {
            return int((minosoftDhNormalMaterial() >> 3u) & 0xFFu);
        }

        vec3 minosoftDhNormal() {
            uint normal = minosoftDhNormalMaterial() & 0x7u;
            if (normal == 0u) return vec3(0.0, -1.0, 0.0);
            if (normal == 1u) return vec3(0.0, 1.0, 0.0);
            if (normal == 2u) return vec3(0.0, 0.0, -1.0);
            if (normal == 3u) return vec3(0.0, 0.0, 1.0);
            if (normal == 4u) return vec3(-1.0, 0.0, 0.0);
            return vec3(1.0, 0.0, 0.0);
        }

        vec4 minosoftDhColor() {
            uint color = floatBitsToUint(vinTintColor);
            return vec4(
                float((color >> 24u) & 0xFFu),
                float((color >> 16u) & 0xFFu),
                float((color >> 8u) & 0xFFu),
                float(color & 0xFFu)
            ) / 255.0;
        }

        vec2 minosoftDhLight() {
            uint light = floatBitsToUint(vinLight);
            return (vec2(float(light & 0xFu), float((light >> 4u) & 0xFu)) + 0.5) / 16.0;
        }
    """.trimIndent()

    private val MODERN_WEATHER_VERTEX_BODY = """
        // minosoft:scene_bridge WEATHER WEATHER uTextures,uIntensity,uOffset,uTexture
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinOffset;
        layout (location = 3) in float vinOffsetMultiplicator;
        layout (location = 4) in float vinAlphaMultiplicator;
        out vec2 uv;
        out vec2 texCoord;
        out vec4 lmtexcoord;
        out vec4 color;
        flat out vec2 lmCoord;
        out float lPos;
        out vec4 tint;
        flat out vec4 glColor;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform float uIntensity;
        uniform float uOffset;
        uniform uint uTexture;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        void main() {
            float offset = vinOffset + uOffset * vinOffsetMultiplicator;
            minosoftSceneTextureArray = uTexture >> 28u;
            minosoftSceneTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
            uv = minosoftMaterialLogicalUv(vinUV + vec2(0.0, offset), minosoftSceneTextureArray);
            texCoord = uv;
            lmCoord = vec2(1.0);
            lmtexcoord = vec4(uv, lmCoord);
            lPos = length(vinPosition);
            tint = vec4(1.0, 1.0, 1.0, uIntensity * vinAlphaMultiplicator);
            color = tint;
            glColor = tint;
            exposure = 0.0;
            HELD_ITEM_BRIGHTNESS = 0.0;
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            gl_Position = vec4(vinPosition, 1.0);
        }
    """.trimIndent()

    private val MODERN_WEATHER_FRAGMENT_HEADER = """
        flat in uint minosoftSceneTextureArray;
        in float minosoftSceneTextureLayer;
        uniform sampler2DArray uTextures[16];
    """.trimIndent()

    private val MODERN_DAMAGED_BLOCK_VERTEX_BODY = """
        // minosoft:scene_bridge DAMAGED_BLOCK DAMAGED_BLOCK uTextures,uLightMapBuffer,uViewProjectionMatrix,uCameraPosition,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius,fog,uTexture
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinUV;
        out vec2 uv;
        out vec2 texCoord;
        out vec2 lmCoord;
        out vec4 lmtexcoord;
        out vec4 color;
        flat out vec4 glColor;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uViewProjectionMatrix;
        uniform uint uTexture;
        vec2 minosoftUnpackUv(uint packed) {
            return vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
        }
        void main() {
            minosoftSceneTextureArray = uTexture >> 28u;
            minosoftSceneTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
            uv = minosoftMaterialLogicalUv(
                minosoftUnpackUv(floatBitsToUint(vinUV)),
                minosoftSceneTextureArray
            );
            texCoord = uv;
            glColor = vec4(1.0);
            lmCoord = vec2(1.0);
            lmtexcoord = vec4(uv, lmCoord);
            color = glColor;
            exposure = 0.0;
            HELD_ITEM_BRIGHTNESS = 0.0;
            gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
        }
    """.trimIndent()

    private val MODERN_DAMAGED_BLOCK_FRAGMENT_HEADER = """
        flat in uint minosoftSceneTextureArray;
        in float minosoftSceneTextureLayer;
        uniform sampler2DArray uTextures[16];
    """.trimIndent()

    private val MODERN_ENTITY_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_TEXTURE GENERIC_TEXTURE uTextures,uViewProjectionMatrix
        // minosoft:scene_bridge POSITION_TEXTURE ENTITY_FLAME uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix
        // minosoft:scene_bridge BLOCK_FEATURE BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor
        // minosoft:scene_bridge BILLBOARD_TEXT BILLBOARD_TEXT uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor
        // minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uTintColor,uOutlineColor
        // minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uLight,uLightMapBuffer,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius
        // minosoft:scene_bridge PLAYER_SKELETAL PLAYER uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uIndexLayer,uTintColor,uSkinParts,uInflate,uHideBase,uFeaturePart,uAllowBaseTransparency,uGlint,uGlintTexture,uGlintTime
        out vec2 uv;
        out vec2 texCoord;
        out vec2 texcoord;
        out vec2 lmCoord;
        out vec3 scene_pos;
        out vec3 normal;
        out vec4 tint;
        out vec4 glColor;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 binormal;
        flat out vec3 tangent;
        out vec3 viewVector;
        out vec4 vTexCoordAM;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        out vec2 light_levels;
        out vec2 uv_local;
        out vec2 signMidCoordPos;
        flat out vec2 absMidCoordPos;
        flat out vec2 midCoord;
        // minosoft:texture_array_index minosoftSceneTextureArray
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uViewProjectionMatrix;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        uniform vec3 uCameraPosition;
        uniform int entityId;
        uniform int currentRenderedItemId;
        void minosoftPrepareEntityUv(vec2 coordinate, vec2 faceMidpoint) {
            coordinate = minosoftMaterialLogicalUv(coordinate, minosoftSceneTextureArray);
            faceMidpoint = minosoftMaterialLogicalUv(faceMidpoint, minosoftSceneTextureArray);
            uv = coordinate;
            uv_local = coordinate;
            texCoord = coordinate;
            texcoord = coordinate;
            midCoord = faceMidpoint;
            vec2 offset = coordinate - faceMidpoint;
            signMidCoordPos = sign(offset);
            absMidCoordPos = abs(offset);
        }
        void minosoftPrepareEntityPbr(vec3 position, vec3 surfaceNormal, vec4 surfaceTangent) {
            tangent = normalize(surfaceTangent.xyz);
            binormal = normalize(cross(surfaceNormal, tangent)) * surfaceTangent.w;
            tbn = mat3(tangent, binormal, surfaceNormal);
            mat3 worldToTangent = mat3(
                tangent.x, binormal.x, surfaceNormal.x,
                tangent.y, binormal.y, surfaceNormal.y,
                tangent.z, binormal.z, surfaceNormal.z
            );
            viewVector = worldToTangent * (gbufferModelView * vec4(position, 1.0)).xyz;
            vec2 radius = absMidCoordPos;
            if (max(radius.x, radius.y) <= 0.000001) {
                vTexCoordAM = vec4(vec2(0.0), vec2(1.0));
            } else {
                vTexCoordAM.zw = radius * 2.0;
                vTexCoordAM.xy = min(texCoord, midCoord - (texCoord - midCoord));
            }
        }
        void minosoftPrepareEntityPbr(vec3 position, vec3 surfaceNormal) {
            vec3 reference = abs(surfaceNormal.y) < 0.999
                ? vec3(0.0, 1.0, 0.0)
                : vec3(0.0, 0.0, 1.0);
            minosoftPrepareEntityPbr(
                position,
                surfaceNormal,
                vec4(normalize(cross(reference, surfaceNormal)), 1.0)
            );
        }
        #if defined(MINOSOFT_STATE_ABI_GENERIC_TEXTURE)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        layout (location = 4) in vec3 vinNormal;
        layout (location = 5) in vec4 vinTangent;
        layout (location = 6) in vec2 vinMidUV;
        vec4 minosoftDecodeEntityColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftPrepareEntityUv(vinUV, vinMidUV);
            lmCoord = vec2(1.0);
            scene_pos = vinPosition;
            normal = normalize(vinNormal);
            vec3 minosoftTangent = normalize(vinTangent.xyz);
            tint = minosoftDecodeEntityColor(floatBitsToUint(vinTintColor));
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = uint(max(entityId - 10000, 0));
            tbn = mat3(1.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareEntityPbr(
                scene_pos,
                normal,
                vec4(minosoftTangent, vinTangent.w)
            );
            gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
        }
        #elif defined(MINOSOFT_STATE_ABI_BLOCK)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        layout (location = 4) in vec2 vinMidUV;
        layout (location = 5) in vec3 vinNormal;
        layout (location = 6) in vec4 vinTangent;
        uniform mat4 uMatrix;
        uniform vec4 uTintColor;
        uniform vec4 uOutlineColor;
        vec2 minosoftDecodeItemUv(uint packed) {
            return vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
        }
        vec4 minosoftDecodeItemColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            vec4 worldPosition = uMatrix * vec4(vinPosition, 1.0);
            vec3 itemNormal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);
            vec3 itemTangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            vec2 logicalUv = minosoftMaterialLogicalUv(
                minosoftDecodeItemUv(floatBitsToUint(vinUV)),
                minosoftSceneTextureArray
            );
            vec2 logicalMidUv = minosoftMaterialLogicalUv(vinMidUV, minosoftSceneTextureArray);
            uv = logicalUv;
            uv_local = logicalUv;
            texCoord = logicalUv;
            midCoord = logicalMidUv;
            vec2 midOffset = logicalUv - logicalMidUv;
            signMidCoordPos = sign(midOffset);
            absMidCoordPos = abs(midOffset);
            lmCoord = vec2(1.0);
            scene_pos = worldPosition.xyz;
            normal = itemNormal;
            tint = minosoftDecodeItemColor(floatBitsToUint(vinTintColor)) * uTintColor;
            if (uOutlineColor.a > 0.0) tint = uOutlineColor;
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = uint(max(entityId - 10000, 0));
            tbn = mat3(1.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareEntityPbr(scene_pos, normal, vec4(itemTangent, vinTangent.w));
            gl_Position = uViewProjectionMatrix * worldPosition;
        }
        #elif defined(MINOSOFT_STATE_ABI_ENTITY_FLAME)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 4) in vec3 vinNormal;
        layout (location = 5) in vec4 vinTangent;
        layout (location = 6) in vec2 vinMidUV;
        uniform mat4 uMatrix;
        void main() {
            vec4 worldPosition = uMatrix * vec4(vinPosition, 1.0);
            vec3 minosoftNormal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);
            vec3 minosoftTangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftPrepareEntityUv(vinUV, vinMidUV);
            lmCoord = vec2(1.0);
            scene_pos = worldPosition.xyz;
            normal = minosoftNormal;
            tint = vec4(1.0);
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = uint(max(entityId - 10000, 0));
            tbn = mat3(1.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareEntityPbr(
                scene_pos,
                normal,
                vec4(minosoftTangent, vinTangent.w)
            );
            gl_Position = uViewProjectionMatrix * worldPosition;
        }
        #elif defined(MINOSOFT_STATE_ABI_BILLBOARD_TEXT)
        layout (location = 0) in vec2 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        uniform mat4 uMatrix;
        uniform vec4 uTintColor;
        uniform vec4 uOutlineColor;
        vec4 minosoftDecodeTextColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            vec4 worldPosition = uMatrix * vec4(vinPosition, 0.0, 1.0);
            vec3 textTangent = normalize((uMatrix * vec4(1.0, 0.0, 0.0, 0.0)).xyz);
            vec3 textBitangent = normalize((uMatrix * vec4(0.0, 1.0, 0.0, 0.0)).xyz);
            vec3 textNormal = normalize(cross(textBitangent, textTangent));
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftPrepareEntityUv(vinUV, vinUV);
            lmCoord = vec2(1.0);
            scene_pos = worldPosition.xyz;
            normal = textNormal;
            tint = minosoftDecodeTextColor(floatBitsToUint(vinTintColor)) * uTintColor;
            if (uOutlineColor.a > 0.0) tint = uOutlineColor;
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = uint(max(entityId - 10000, 0));
            tbn = mat3(1.0);
            tbn[2] = normal;
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareEntityPbr(scene_pos, normal, vec4(textTangent, -1.0));
            gl_Position = uViewProjectionMatrix * worldPosition;
        }
        #elif defined(MINOSOFT_STATE_ABI_PLAYER)
        #define POSITIVE_INFINITY (1.0 / 0.0)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinPartTransformNormal;
        layout (location = 3) in vec2 vinMidUV;
        layout (location = 4) in vec4 vinTangent;
        uniform uint uIndexLayer;
        uniform vec4 uTintColor;
        uniform uint uSkinParts;
        uniform float uInflate;
        uniform bool uHideBase;
        uniform uint uFeaturePart;
        uniform bool uAllowBaseTransparency;
        uniform bool uGlint;
        uniform uint uGlintTexture;
        uniform float uGlintTime;
        #include "minosoft:skeletal/buffer"
        #include "minosoft:skeletal/shade"
        void main() {
            uint packed = floatBitsToUint(vinPartTransformNormal);
            uint skinPart = (packed >> 19u) & 0xFFu;
            bool hiddenFeature = skinPart >= 0xF0u && skinPart != uFeaturePart;
            bool hiddenBase = skinPart == 0u && uHideBase;
            bool hiddenSkinPart =
                skinPart > 0u && skinPart < 0xF0u &&
                ((1u << (skinPart - 1u)) & uSkinParts) == 0u;
            if (hiddenFeature || hiddenBase || hiddenSkinPart) {
                gl_Position = vec4(POSITIVE_INFINITY);
                return;
            }
            mat4 transform = uSkeletalTransforms[(packed >> 12u) & 0x7Fu];
            vec3 decodedNormal = decodeNormal(packed & 0xFFFu);
            vec3 inflatedPosition = vinPosition + decodedNormal * uInflate;
            vec4 position = transform * vec4(inflatedPosition, 1.0);
            vec3 minosoftNormal = transformNormal(decodedNormal, transform);
            vec3 minosoftTangent = normalize((transform * vec4(vinTangent.xyz, 0.0)).xyz);
            minosoftSceneTextureArray = uIndexLayer >> 28u;
            minosoftSceneTextureLayer = float((uIndexLayer >> 12u) & 0xFFFFu);
            minosoftPrepareEntityUv(vinUV, vinMidUV);
            lmCoord = vec2(1.0);
            scene_pos = position.xyz;
            normal = minosoftNormal;
            tint = vec4(vec3(getShade(minosoftNormal)), 1.0) * uTintColor;
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = uint(max(entityId - 10000, 0));
            tbn = mat3(1.0);
            tbn[2] = minosoftNormal;
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareEntityPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w));
            gl_Position = uViewProjectionMatrix * position;
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTransformNormal;
        layout (location = 3) in float vinTexture;
        layout (location = 4) in vec2 vinMidUV;
        layout (location = 5) in vec4 vinTangent;
        uniform vec4 uTintColor;
        uniform vec4 uOutlineColor;
        #if defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
        uniform uint uLight;
        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftEntityLightMap[256];
        };
        #endif
        #include "minosoft:skeletal/buffer"
        #include "minosoft:skeletal/shade"
        void main() {
            uint transformNormalBits = floatBitsToUint(vinTransformNormal);
            mat4 transform = uSkeletalTransforms[(transformNormalBits >> 12u) & 0x7Fu];
            vec4 position = transform * vec4(vinPosition, 1.0);
            vec3 minosoftNormal = transformNormal(decodeNormal(transformNormalBits & 0xFFFu), transform);
            vec3 minosoftTangent = normalize((transform * vec4(vinTangent.xyz, 0.0)).xyz);
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftPrepareEntityUv(vinUV, vinMidUV);
            scene_pos = position.xyz;
            normal = minosoftNormal;
            tint = vec4(vec3(getShade(minosoftNormal)), 1.0);
            #if defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
            uint light = uLight & 0xFFu;
            tint *= minosoftEntityLightMap[light];
            light_levels = vec2(float(light & 0xFu), float(light >> 4u)) / 15.0;
            #else
            tint *= uTintColor;
            if (uOutlineColor.a > 0.0) tint = uOutlineColor;
            light_levels = vec2(1.0);
            #endif
            lmCoord = light_levels;
            glColor = tint;
            material_mask = uint(max(entityId - 10000, 0));
            tbn = mat3(1.0);
            tbn[2] = minosoftNormal;
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareEntityPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w));
            gl_Position = uViewProjectionMatrix * position;
        }
        #endif
    """.trimIndent()

    private val MODERN_ENTITY_FRAGMENT_HEADER = """
        // minosoft:texture_array_index minosoftSceneTextureArray
        flat in uint minosoftSceneTextureArray;
        in float minosoftSceneTextureLayer;
        uniform sampler2DArray uTextures[16];
    """.trimIndent()

    private val MODERN_BLOCK_VERTEX_BODY = """
        // minosoft:scene_bridge BLOCK_FEATURE BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor
        // minosoft:scene_bridge BLOCK_FEATURE FLASHING_BLOCK uTextures,uViewProjectionMatrix,uCameraPosition,fog,uMatrix,uTintColor,uOutlineColor,uFlashColor,uFlashProgress
        // minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uTintColor,uOutlineColor
        // minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uLight,uLightMapBuffer,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius
        #if defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTransformNormal;
        layout (location = 3) in float vinTexture;
        layout (location = 4) in vec2 vinMidUV;
        layout (location = 5) in vec4 vinTangent;
        out vec2 uv;
        out vec2 texCoord;
        out vec2 texcoord;
        out vec2 lmCoord;
        out vec3 scene_pos;
        out vec3 normal;
        out vec4 tint;
        out vec4 glColor;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 binormal;
        flat out vec3 tangent;
        out vec3 viewVector;
        out vec4 vTexCoordAM;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        out vec2 light_levels;
        out vec2 uv_local;
        out vec2 signMidCoordPos;
        flat out vec2 absMidCoordPos;
        flat out vec2 midCoord;
        // minosoft:texture_array_index minosoftSceneTextureArray
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uViewProjectionMatrix;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        uniform int blockEntityId;
        uniform vec4 uTintColor;
        uniform vec4 uOutlineColor;
        #if defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
        uniform uint uLight;
        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftBlockEntityLightMap[256];
        };
        #endif
        #include "minosoft:skeletal/buffer"
        #include "minosoft:skeletal/shade"
        void minosoftPrepareBlockPbr(vec3 position, vec3 surfaceNormal, vec4 surfaceTangent) {
            tangent = normalize(surfaceTangent.xyz);
            binormal = normalize(cross(surfaceNormal, tangent)) * surfaceTangent.w;
            tbn = mat3(tangent, binormal, surfaceNormal);
            mat3 worldToTangent = mat3(
                tangent.x, binormal.x, surfaceNormal.x,
                tangent.y, binormal.y, surfaceNormal.y,
                tangent.z, binormal.z, surfaceNormal.z
            );
            viewVector = worldToTangent * (gbufferModelView * vec4(position, 1.0)).xyz;
            vec2 radius = absMidCoordPos;
            if (max(radius.x, radius.y) <= 0.000001) {
                vTexCoordAM = vec4(vec2(0.0), vec2(1.0));
            } else {
                vTexCoordAM.zw = radius * 2.0;
                vTexCoordAM.xy = min(texCoord, midCoord - (texCoord - midCoord));
            }
        }
        void minosoftPrepareBlockPbr(vec3 position, vec3 surfaceNormal) {
            vec3 reference = abs(surfaceNormal.y) < 0.999
                ? vec3(0.0, 1.0, 0.0)
                : vec3(0.0, 0.0, 1.0);
            minosoftPrepareBlockPbr(
                position,
                surfaceNormal,
                vec4(normalize(cross(reference, surfaceNormal)), 1.0)
            );
        }
        void main() {
            uint transformNormalBits = floatBitsToUint(vinTransformNormal);
            mat4 transform = uSkeletalTransforms[(transformNormalBits >> 12u) & 0x7Fu];
            vec4 position = transform * vec4(vinPosition, 1.0);
            vec3 minosoftNormal = transformNormal(decodeNormal(transformNormalBits & 0xFFFu), transform);
            vec3 minosoftTangent = normalize((transform * vec4(vinTangent.xyz, 0.0)).xyz);
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            vec2 logicalUv = minosoftMaterialLogicalUv(vinUV, minosoftSceneTextureArray);
            vec2 logicalMidUv = minosoftMaterialLogicalUv(vinMidUV, minosoftSceneTextureArray);
            uv = logicalUv;
            uv_local = logicalUv;
            texCoord = logicalUv;
            midCoord = logicalMidUv;
            vec2 midOffset = logicalUv - logicalMidUv;
            signMidCoordPos = sign(midOffset);
            absMidCoordPos = abs(midOffset);
            scene_pos = position.xyz;
            normal = minosoftNormal;
            tint = vec4(vec3(getShade(minosoftNormal)), 1.0);
            #if defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
            uint light = uLight & 0xFFu;
            tint *= minosoftBlockEntityLightMap[light];
            light_levels = vec2(float(light & 0xFu), float(light >> 4u)) / 15.0;
            #else
            tint *= uTintColor;
            if (uOutlineColor.a > 0.0) tint = uOutlineColor;
            light_levels = vec2(1.0);
            #endif
            lmCoord = light_levels;
            glColor = tint;
            material_mask = uint(max(blockEntityId - 10000, 0));
            tbn = mat3(1.0);
            tbn[2] = minosoftNormal;
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareBlockPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w));
            gl_Position = uViewProjectionMatrix * position;
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        layout (location = 4) in vec2 vinMidUV;
        layout (location = 5) in vec3 vinNormal;
        layout (location = 6) in vec4 vinTangent;
        out vec2 uv;
        out vec2 texCoord;
        out vec2 texcoord;
        out vec2 lmCoord;
        out vec3 scene_pos;
        out vec3 normal;
        out vec4 tint;
        out vec4 glColor;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 binormal;
        flat out vec3 tangent;
        out vec3 viewVector;
        out vec4 vTexCoordAM;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        out vec2 light_levels;
        out vec2 signMidCoordPos;
        flat out vec2 absMidCoordPos;
        flat out vec2 midCoord;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uViewProjectionMatrix;
        uniform mat4 uMatrix;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        uniform vec4 uTintColor;
        uniform vec4 uOutlineColor;
        #ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
        uniform vec4 uFlashColor;
        uniform float uFlashProgress;
        #endif
        uniform int blockEntityId;
        vec2 minosoftBlockUv(uint packed) {
            return vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
        }
        vec4 minosoftBlockColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void minosoftPrepareBlockPbr(vec3 position, vec3 surfaceNormal, vec4 surfaceTangent) {
            tangent = normalize(surfaceTangent.xyz);
            binormal = normalize(cross(surfaceNormal, tangent)) * surfaceTangent.w;
            tbn = mat3(tangent, binormal, surfaceNormal);
            mat3 worldToTangent = mat3(
                tangent.x, binormal.x, surfaceNormal.x,
                tangent.y, binormal.y, surfaceNormal.y,
                tangent.z, binormal.z, surfaceNormal.z
            );
            viewVector = worldToTangent * (gbufferModelView * vec4(position, 1.0)).xyz;
            vec2 radius = absMidCoordPos;
            if (max(radius.x, radius.y) <= 0.000001) {
                vTexCoordAM = vec4(vec2(0.0), vec2(1.0));
            } else {
                vTexCoordAM.zw = radius * 2.0;
                vTexCoordAM.xy = min(texCoord, midCoord - (texCoord - midCoord));
            }
        }
        void minosoftPrepareBlockPbr(vec3 position, vec3 surfaceNormal) {
            vec3 reference = abs(surfaceNormal.y) < 0.999
                ? vec3(0.0, 1.0, 0.0)
                : vec3(0.0, 0.0, 1.0);
            minosoftPrepareBlockPbr(
                position,
                surfaceNormal,
                vec4(normalize(cross(reference, surfaceNormal)), 1.0)
            );
        }
        void main() {
            vec4 position = uMatrix * vec4(vinPosition, 1.0);
            uint textureBits = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = textureBits >> 28u;
            minosoftSceneTextureLayer = float((textureBits >> 12u) & 0xFFFFu);
            uv = minosoftMaterialLogicalUv(
                minosoftBlockUv(floatBitsToUint(vinUV)),
                minosoftSceneTextureArray
            );
            texCoord = uv;
            midCoord = minosoftMaterialLogicalUv(vinMidUV, minosoftSceneTextureArray);
            vec2 midOffset = uv - midCoord;
            signMidCoordPos = sign(midOffset);
            absMidCoordPos = abs(midOffset);
            lmCoord = vec2(1.0);
            scene_pos = position.xyz;
            normal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);
            vec3 minosoftTangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);
            tint = minosoftBlockColor(floatBitsToUint(vinTintColor)) * uTintColor;
            if (uOutlineColor.a > 0.0) tint = uOutlineColor;
            #ifdef MINOSOFT_STATE_ABI_FLASHING_BLOCK
            tint = mix(tint, uFlashColor, uFlashProgress);
            #endif
            glColor = tint;
            material_mask = uint(max(blockEntityId - 10000, 0));
            tbn = mat3(1.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            light_levels = vec2(1.0);
            minosoftPrepareBlockPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w));
            gl_Position = uViewProjectionMatrix * position;
        }
        #endif
    """.trimIndent()

    private val MODERN_BEACON_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_TEXTURE BEACON_BEAM uTextures,uViewProjectionMatrix,uMatrix,uTextureOffset
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        layout (location = 4) in vec3 vinNormal;
        layout (location = 5) in vec4 vinTangent;
        layout (location = 6) in vec2 vinMidUV;
        out vec2 uv;
        out vec2 texCoord;
        out vec2 texcoord;
        out vec3 scene_pos;
        out vec3 normal;
        out vec4 tint;
        out vec4 color;
        flat out float exposure;
        out vec4 glColor;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 binormal;
        flat out vec3 tangent;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        out vec2 light_levels;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uViewProjectionMatrix;
        uniform mat4 uMatrix;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        uniform sampler2D colortex4;
        uniform float uTextureOffset;
        vec4 minosoftBeaconColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            vec4 position = uMatrix * vec4(vinPosition, 1.0);
            normal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);
            tangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);
            binormal = normalize(cross(normal, tangent)) * vinTangent.w;
            uint textureBits = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = textureBits >> 28u;
            minosoftSceneTextureLayer = float((textureBits >> 12u) & 0xFFFFu);
            uv = minosoftMaterialLogicalUv(
                vinUV + vec2(0.0, uTextureOffset),
                minosoftSceneTextureArray
            );
            texCoord = uv;
            texcoord = uv;
            scene_pos = position.xyz;
            tint = minosoftBeaconColor(floatBitsToUint(vinTintColor));
            color = tint;
            exposure = texelFetch(colortex4, ivec2(10, 37), 0).r;
            glColor = tint;
            material_mask = 32u;
            tbn = mat3(tangent, binormal, normal);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            light_levels = vec2(1.0);
            gl_Position = uViewProjectionMatrix * position;
        }
    """.trimIndent()

    private val MODERN_HAND_VERTEX_BODY = """
        // minosoft:scene_bridge HELD_ITEM HELD_ITEM uTextures,uViewProjectionMatrix,uMatrix,uTintColor
        // minosoft:scene_bridge ARM_SKELETAL ARM uTextures,uTexture,uTintColor,uSkinParts,uTransform
        out vec2 uv;
        out vec2 uv_local;
        out vec2 texCoord;
        out vec2 texcoord;
        out vec2 lmCoord;
        out vec3 scene_pos;
        out vec3 position_view;
        out vec3 position_scene;
        out vec3 normal;
        out vec4 tint;
        out vec4 glColor;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 binormal;
        flat out vec3 tangent;
        out vec3 viewVector;
        out vec4 vTexCoordAM;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        out vec2 light_levels;
        out vec2 signMidCoordPos;
        flat out vec2 absMidCoordPos;
        flat out vec2 midCoord;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform vec4 uTintColor;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        uniform int currentRenderedItemId;
        void minosoftPrepareHandUv(vec2 coordinate, vec2 faceMidpoint) {
            coordinate = minosoftMaterialLogicalUv(coordinate, minosoftSceneTextureArray);
            faceMidpoint = minosoftMaterialLogicalUv(faceMidpoint, minosoftSceneTextureArray);
            uv = coordinate;
            uv_local = coordinate;
            texCoord = coordinate;
            texcoord = coordinate;
            midCoord = faceMidpoint;
            vec2 offset = coordinate - faceMidpoint;
            signMidCoordPos = sign(offset);
            absMidCoordPos = abs(offset);
        }
        void minosoftPrepareHandPbr(vec3 position, vec3 surfaceNormal, vec4 surfaceTangent) {
            tangent = normalize(surfaceTangent.xyz);
            binormal = normalize(cross(surfaceNormal, tangent)) * surfaceTangent.w;
            tbn = mat3(tangent, binormal, surfaceNormal);
            mat3 worldToTangent = mat3(
                tangent.x, binormal.x, surfaceNormal.x,
                tangent.y, binormal.y, surfaceNormal.y,
                tangent.z, binormal.z, surfaceNormal.z
            );
            viewVector = worldToTangent * (gbufferModelView * vec4(position, 1.0)).xyz;
            vec2 radius = absMidCoordPos;
            if (max(radius.x, radius.y) <= 0.000001) {
                vTexCoordAM = vec4(vec2(0.0), vec2(1.0));
            } else {
                vTexCoordAM.zw = radius * 2.0;
                vTexCoordAM.xy = min(texCoord, midCoord - (texCoord - midCoord));
            }
        }
        void minosoftPrepareHandPbr(vec3 position, vec3 surfaceNormal) {
            vec3 reference = abs(surfaceNormal.y) < 0.999
                ? vec3(0.0, 1.0, 0.0)
                : vec3(0.0, 0.0, 1.0);
            minosoftPrepareHandPbr(
                position,
                surfaceNormal,
                vec4(normalize(cross(reference, surfaceNormal)), 1.0)
            );
        }
        float minosoftDecodeNormalPart(uint data) {
            return data < 8u ? (float(data) / 8.0) - 1.0 : float(data - 8u) / 7.0;
        }
        vec3 minosoftDecodeNormal(uint packed) {
            return vec3(
                minosoftDecodeNormalPart(packed & 0x0Fu),
                minosoftDecodeNormalPart((packed >> 8u) & 0x0Fu),
                minosoftDecodeNormalPart((packed >> 4u) & 0x0Fu)
            );
        }
        #if defined(MINOSOFT_STATE_ABI_ARM)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinPartTransformNormal;
        layout (location = 3) in vec2 vinMidUV;
        layout (location = 4) in vec4 vinTangent;
        uniform uint uTexture;
        uniform uint uSkinParts;
        uniform mat4 uTransform;
        void main() {
            minosoftSceneTextureArray = uTexture >> 28u;
            minosoftSceneTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
            minosoftPrepareHandUv(vinUV, vinMidUV);
            lmCoord = vec2(1.0);
            scene_pos = vinPosition;
            position_view = vinPosition;
            position_scene = vinPosition;
            normal = normalize(minosoftDecodeNormal(floatBitsToUint(vinPartTransformNormal) & 0xFFFu));
            tint = uTintColor;
            glColor = tint;
            material_mask = uint(max(currentRenderedItemId - 10000, 0));
            tbn = mat3(1.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            light_levels = vec2(1.0);
            minosoftPrepareHandPbr(scene_pos, normal, vinTangent);
            gl_Position = uTransform * vec4(vinPosition, 1.0);
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        layout (location = 4) in vec2 vinMidUV;
        layout (location = 5) in vec3 vinNormal;
        layout (location = 6) in vec4 vinTangent;
        uniform mat4 uViewProjectionMatrix;
        uniform mat4 uMatrix;
        vec2 minosoftHandUv(uint packed) {
            return vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
        }
        vec4 minosoftHandColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            vec4 position = uMatrix * vec4(vinPosition, 1.0);
            uint textureBits = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = textureBits >> 28u;
            minosoftSceneTextureLayer = float((textureBits >> 12u) & 0xFFFFu);
            vec2 unpackedUv = minosoftHandUv(floatBitsToUint(vinUV));
            minosoftPrepareHandUv(unpackedUv, vinMidUV);
            lmCoord = vec2(1.0);
            scene_pos = position.xyz;
            position_view = position.xyz;
            position_scene = position.xyz;
            normal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);
            vec3 minosoftTangent = normalize((uMatrix * vec4(vinTangent.xyz, 0.0)).xyz);
            tint = minosoftHandColor(floatBitsToUint(vinTintColor)) * uTintColor;
            glColor = tint;
            material_mask = uint(max(currentRenderedItemId - 10000, 0));
            tbn = mat3(1.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            light_levels = vec2(1.0);
            minosoftPrepareHandPbr(scene_pos, normal, vec4(minosoftTangent, vinTangent.w));
            gl_Position = uViewProjectionMatrix * position;
        }
        #endif
    """.trimIndent()

    private val MODERN_GLINT_VERTEX_BODY = """
        // minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uTintColor,uOutlineColor
        // minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uLight,uLightMapBuffer,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius
        // minosoft:scene_bridge PLAYER_SKELETAL PLAYER uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uIndexLayer,uTintColor,uSkinParts,uInflate,uHideBase,uFeaturePart,uAllowBaseTransparency,uGlint,uGlintTexture,uGlintTime
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        #if defined(MINOSOFT_STATE_ABI_PLAYER)
        layout (location = 2) in float vinPartTransformNormal;
        #else
        layout (location = 2) in float vinTransformNormal;
        layout (location = 3) in float vinTexture;
        #endif
        out vec2 uv;
        out vec2 texCoord;
        out vec2 texcoord;
        out vec4 color;
        flat out float exposure;
        flat out vec4 glColor;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uViewProjectionMatrix;
        uniform sampler2D colortex4;
        #include "minosoft:skeletal/buffer"
        #if defined(MINOSOFT_STATE_ABI_PLAYER)
        uniform uint uSkinParts;
        uniform float uInflate;
        uniform bool uHideBase;
        uniform uint uFeaturePart;
        uniform uint uGlintTexture;
        uniform vec4 uTintColor;
        #include "minosoft:skeletal/shade"
        #define POSITIVE_INFINITY (1.0 / 0.0)
        #endif
        void main() {
            #if defined(MINOSOFT_STATE_ABI_PLAYER)
            uint bits = floatBitsToUint(vinPartTransformNormal);
            uint skinPart = (bits >> 19u) & 0xFFu;
            bool hiddenFeature = skinPart >= 0xF0u && skinPart != uFeaturePart;
            bool hiddenBase = skinPart == 0u && uHideBase;
            bool hiddenSkinPart =
                skinPart > 0u && skinPart < 0xF0u &&
                ((1u << (skinPart - 1u)) & uSkinParts) == 0u;
            if (hiddenFeature || hiddenBase || hiddenSkinPart) {
                gl_Position = vec4(POSITIVE_INFINITY);
                return;
            }
            mat4 transform = uSkeletalTransforms[(bits >> 12u) & 0x7Fu];
            vec3 inflatedPosition = vinPosition + decodeNormal(bits & 0xFFFu) * uInflate;
            minosoftSceneTextureArray = uGlintTexture >> 28u;
            minosoftSceneTextureLayer = float((uGlintTexture >> 12u) & 0xFFFFu);
            uv = minosoftMaterialLogicalUv(vinUV, minosoftSceneTextureArray);
            texCoord = uv;
            texcoord = uv;
            color = uTintColor;
            exposure = texelFetch(colortex4, ivec2(10, 37), 0).r;
            glColor = uTintColor;
            gl_Position = uViewProjectionMatrix * transform * vec4(inflatedPosition, 1.0);
            #else
            uint bits = floatBitsToUint(vinTransformNormal);
            mat4 transform = uSkeletalTransforms[(bits >> 12u) & 0x7Fu];
            uint textureBits = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = textureBits >> 28u;
            minosoftSceneTextureLayer = float((textureBits >> 12u) & 0xFFFFu);
            uv = minosoftMaterialLogicalUv(vinUV, minosoftSceneTextureArray);
            texCoord = uv;
            texcoord = uv;
            color = vec4(1.0);
            exposure = texelFetch(colortex4, ivec2(10, 37), 0).r;
            glColor = vec4(1.0);
            gl_Position = uViewProjectionMatrix * transform * vec4(vinPosition, 1.0);
            #endif
        }
    """.trimIndent()

    private val MODERN_GLINT_FRAGMENT_HEADER = """
        flat in uint minosoftSceneTextureArray;
        in float minosoftSceneTextureLayer;
        uniform sampler2DArray uTextures[16];
    """.trimIndent()

    private val MODERN_LIGHTNING_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_COLOR LIGHTNING uViewProjectionMatrix,uMatrix
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinTintColor;
        layout (location = 2) in vec3 vinNormal;
        out vec2 uv;
        out vec2 texCoord;
        out vec2 lmCoord;
        out vec4 lmtexcoord;
        out vec4 color;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        out vec3 position_view;
        out vec3 position_scene;
        out vec3 normal;
        out vec4 tint;
        out vec4 glColor;
        out vec2 light_levels;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        struct OverworldFogParameters {
            vec3 rayleigh_scattering_coeff;
            vec3 mie_scattering_coeff;
            vec3 mie_extinction_coeff;
        };
        flat out OverworldFogParameters fog_params;
        uniform mat4 uViewProjectionMatrix;
        uniform mat4 uMatrix;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;
        vec4 minosoftLightningColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            vec4 position = uMatrix * vec4(vinPosition, 1.0);
            uv = vec2(0.0);
            texCoord = uv;
            lmCoord = vec2(1.0);
            position_view = position.xyz;
            position_scene = position.xyz;
            normal = normalize((uMatrix * vec4(vinNormal, 0.0)).xyz);
            tint = minosoftLightningColor(floatBitsToUint(vinTintColor));
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = 33u;
            vec3 minosoftReference = abs(normal.y) < 0.999
                ? vec3(0.0, 1.0, 0.0)
                : vec3(0.0, 0.0, 1.0);
            vec3 minosoftTangent = normalize(cross(minosoftReference, normal));
            vec3 minosoftBinormal = normalize(cross(normal, minosoftTangent));
            tbn = mat3(minosoftTangent, minosoftBinormal, normal);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            fog_params.rayleigh_scattering_coeff = vec3(0.0);
            fog_params.mie_scattering_coeff = vec3(0.0);
            fog_params.mie_extinction_coeff = vec3(0.0);
            gl_Position = uViewProjectionMatrix * position;
        }
    """.trimIndent()

    private val MODERN_LIGHTNING_FRAGMENT_HEADER = """
        vec4 minosoftLightningTexture(vec2 uv) { return vec4(1.0); }
        vec4 minosoftLightningTexture(vec2 uv, float bias) { return vec4(1.0); }
        vec4 minosoftLightningTextureGrad(vec2 uv, vec2 dx, vec2 dy) { return vec4(1.0); }
    """.trimIndent()

    private val MODERN_LINE_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_COLOR COLOR uViewProjectionMatrix
        // minosoft:scene_bridge POSITION_COLOR_LIGHT LIGHT_COLOR uLightMapBuffer,uViewProjectionMatrix
        // minosoft:scene_bridge SKY_POSITION SKY_COLOR uSkyViewProjectionMatrix,uSkyColor
        flat out vec2 light_levels;
        flat out vec4 tint;
        flat out vec2 lmCoord;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        out vec3 normal;
        flat out vec4 glColor;
        out vec4 lmtexcoord;
        out vec4 color;
        flat out float exposure;
        flat out int SELECTION_BOX;
        flat out vec3 averageSkyCol_Clouds;
        flat out vec4 lightCol;
        flat out vec3 WsunVec;
        flat out vec4 dailyWeatherParams0;
        flat out vec4 dailyWeatherParams1;
        flat out float HELD_ITEM_BRIGHTNESS;
        uniform mat4 gbufferModelView;
        uniform mat4 gbufferModelViewInverse;
        uniform vec3 sunPosition;
        uniform sampler2D colortex4;
        void minosoftPrepareLineVaryings() {
            lmtexcoord = vec4(0.0, 0.0, light_levels);
            color = tint;
            exposure = texelFetch(colortex4, ivec2(10, 37), 0).r;
            SELECTION_BOX = dot(color.rgb, vec3(0.33333)) < 0.00001 ? 1 : 0;
            lightCol = vec4(
                texelFetch(colortex4, ivec2(6, 37), 0).rgb,
                1.0
            );
            averageSkyCol_Clouds = texelFetch(colortex4, ivec2(0, 37), 0).rgb;
            WsunVec = normalize(mat3(gbufferModelViewInverse) * sunPosition);
            dailyWeatherParams0 = vec4(
                texelFetch(colortex4, ivec2(1, 1), 0).rgb / 300.0,
                0.0
            );
            dailyWeatherParams1 = vec4(
                texelFetch(colortex4, ivec2(2, 1), 0).rgb / 300.0,
                0.0
            );
            HELD_ITEM_BRIGHTNESS = 0.0;
        }
        #if defined(MINOSOFT_STATE_ABI_SKY_COLOR)
        layout (location = 0) in vec3 vinPosition;
        uniform mat4 uSkyViewProjectionMatrix;
        uniform vec4 uSkyColor;
        void main() {
            light_levels = vec2(1.0);
            tint = uSkyColor;
            lmCoord = light_levels;
            glColor = tint;
            normal = vec3(0.0, 1.0, 0.0);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareLineVaryings();
            gl_Position = uSkyViewProjectionMatrix * vec4(vinPosition, 1.0);
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinTintColor;
        #if defined(MINOSOFT_STATE_ABI_LIGHT_COLOR)
        layout (location = 2) in float vinLight;
        layout (location = 3) in vec3 vinNormal;
        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftLineLightMap[256];
        };
        #endif
        uniform mat4 uViewProjectionMatrix;
        vec4 minosoftLineColor() {
            uint packed = floatBitsToUint(vinTintColor);
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void main() {
            tint = minosoftLineColor();
            #if defined(MINOSOFT_STATE_ABI_LIGHT_COLOR)
            uint light = floatBitsToUint(vinLight) & 0xFFu;
            tint *= minosoftLineLightMap[light];
            light_levels = vec2(float(light & 0xFu), float(light >> 4u)) / 15.0;
            #else
            light_levels = vec2(1.0);
            #endif
            lmCoord = light_levels;
            glColor = tint;
            #if defined(MINOSOFT_STATE_ABI_LIGHT_COLOR)
            normal = normalize(vinNormal);
            #else
            normal = vec3(0.0, 1.0, 0.0);
            #endif
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftPrepareLineVaryings();
            gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
        }
        #endif
    """.trimIndent()

    private val MODERN_SKY_VERTEX_HEADER = """
        // minosoft:scene_bridge SKY_TEXTURE SKY_TEXTURE uTextures,uSkyViewProjectionMatrix,uTexture,uTintColor
        // minosoft:scene_bridge PLANET PLANET uMatrix,uTintColor,uTextures
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        vec2 lmCoord;
        uniform vec4 uTintColor;
        #if defined(MINOSOFT_STATE_ABI_PLANET)
        layout (location = 0) in vec3 minosoftSkyVinPosition;
        layout (location = 1) in vec2 minosoftSkyVinUv;
        layout (location = 2) in float minosoftSkyVinTexture;
        uniform mat4 uMatrix;
        vec3 minosoftSkyPosition() { return minosoftSkyVinPosition; }
        vec2 minosoftSkyUv() {
            return minosoftMaterialLogicalUv(minosoftSkyVinUv, minosoftSceneTextureArray);
        }
        mat4 minosoftSkyMatrix() { return uMatrix; }
        void minosoftPrepareSkyTexture() {
            uint packed = floatBitsToUint(minosoftSkyVinTexture);
            minosoftSceneTextureArray = packed >> 28u;
            minosoftSceneTextureLayer = float((packed >> 12u) & 0xFFFFu);
        }
        #else
        layout (location = 0) in vec3 minosoftSkyVinPosition;
        layout (location = 1) in uint minosoftSkyUvIndex;
        uniform mat4 uSkyViewProjectionMatrix;
        uniform uint uTexture;
        const vec2 minosoftSkyUvCorners[4] = vec2[4](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 0.0),
            vec2(1.0, 1.0)
        );
        vec3 minosoftSkyPosition() { return minosoftSkyVinPosition; }
        vec2 minosoftSkyUv() {
            return minosoftMaterialLogicalUv(
                minosoftSkyUvCorners[minosoftSkyUvIndex] * 20.0,
                minosoftSceneTextureArray
            );
        }
        mat4 minosoftSkyMatrix() { return uSkyViewProjectionMatrix; }
        void minosoftPrepareSkyTexture() {
            minosoftSceneTextureArray = uTexture >> 28u;
            minosoftSceneTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
        }
        #endif
        vec4 minosoftSkyColor() { return uTintColor; }
    """.trimIndent()

    private val MODERN_SKY_FRAGMENT_HEADER = """
        // minosoft:texture_array_index minosoftSceneTextureArray
        flat in uint minosoftSceneTextureArray;
        in float minosoftSceneTextureLayer;
        uniform sampler2DArray uTextures[16];
    """.trimIndent()

    private val MODERN_SCENE_TEXTURE_FUNCTIONS = buildString {
        appendLine("vec3 minosoftStaticTextureCoordinate(vec3 coordinate) {")
        appendLine("    return vec3(coordinate.x, fract(coordinate.y) / 3.0, coordinate.z);")
        appendLine("}")
        appendLine("vec3 minosoftDynamicTextureCoordinate(vec3 coordinate) { return coordinate; }")
        appendLine("vec2 minosoftStaticTextureGradient(vec2 gradient) {")
        appendLine("    return vec2(gradient.x, gradient.y / 3.0);")
        appendLine("}")
        appendLine("vec2 minosoftDynamicTextureGradient(vec2 gradient) { return gradient; }")
        appendLine("vec4 minosoftSampleSceneTexture(vec2 uv, float bias) {")
        appendLine("    vec3 coordinate = vec3(uv, minosoftSceneTextureLayer);")
        appendLine("    switch (minosoftSceneTextureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return texture(uTextures[$index], " +
                    "minosoftMaterialTextureCoordinate(coordinate), bias);",
            )
        }
        appendLine("        default: return vec4(1.0);")
        appendLine("    }")
        appendLine("}")
        appendLine("vec4 minosoftSampleSceneTexture(vec2 uv) {")
        appendLine("    return minosoftSampleSceneTexture(uv, 0.0);")
        appendLine("}")
        appendLine("vec4 minosoftSampleSceneTextureLod(vec2 uv, float lod) {")
        appendLine("    vec3 coordinate = vec3(uv, minosoftSceneTextureLayer);")
        appendLine("    switch (minosoftSceneTextureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return textureLod(uTextures[$index], " +
                    "minosoftMaterialTextureCoordinate(coordinate), lod);",
            )
        }
        appendLine("        default: return vec4(1.0);")
        appendLine("    }")
        appendLine("}")
        appendLine("vec4 minosoftSampleSceneTextureGrad(vec2 uv, vec2 dx, vec2 dy) {")
        appendLine("    vec3 coordinate = vec3(uv, minosoftSceneTextureLayer);")
        appendLine("    switch (minosoftSceneTextureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return textureGrad(uTextures[$index], " +
                    "minosoftMaterialTextureCoordinate(coordinate), " +
                    "minosoftMaterialTextureGradient(dx), minosoftMaterialTextureGradient(dy));",
            )
        }
        appendLine("        default: return vec4(1.0);")
        appendLine("    }")
        appendLine("}")
        appendLine("vec4 minosoftSampleSceneTextureFetch(ivec2 coordinate, int lod) {")
        appendLine("    ivec3 layered = ivec3(coordinate, int(minosoftSceneTextureLayer));")
        appendLine("    switch (minosoftSceneTextureArray) {")
        for (index in 0 until 16) {
            appendLine("        case ${index}u: return texelFetch(uTextures[$index], layered, lod);")
        }
        appendLine("        default: return vec4(1.0);")
        appendLine("    }")
        appendLine("}")
        appendLine("ivec2 minosoftStaticTextureSize(ivec3 size) {")
        appendLine("    return ivec2(size.x, size.y / 3);")
        appendLine("}")
        appendLine("ivec2 minosoftDynamicTextureSize(ivec3 size) { return size.xy; }")
        appendLine("ivec2 minosoftSampleSceneTextureSize(int lod) {")
        appendLine("    switch (minosoftSceneTextureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return " +
                    "minosoftMaterialTextureSize(textureSize(uTextures[$index], lod));",
            )
        }
        appendLine("        default: return ivec2(1);")
        appendLine("    }")
        appendLine("}")
    }.trimEnd()

    private val MODERN_TERRAIN_MATERIAL_FUNCTIONS = materialCompanionFunctions(
        arrayVariable = "minosoftTextureArray",
        layerVariable = "minosoftTextureLayer",
        functionPrefix = "minosoftSample",
        diffuseSizeFunction = "minosoftSampleTextureSize",
    )

    private val MODERN_SCENE_MATERIAL_FUNCTIONS = materialCompanionFunctions(
        arrayVariable = "minosoftSceneTextureArray",
        layerVariable = "minosoftSceneTextureLayer",
        functionPrefix = "minosoftSampleScene",
        diffuseSizeFunction = "minosoftSampleSceneTextureSize",
    )

    private fun materialCompanionFunctions(
        arrayVariable: String,
        layerVariable: String,
        functionPrefix: String,
        diffuseSizeFunction: String,
    ) = buildString {
        val companions = listOf(
            Triple("Normal", 1, "vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0)"),
            Triple("Specular", 2, "vec4(0.0, 0.0, 0.0, 1.0)"),
        )
        for ((name, offset, neutral) in companions) {
            val function = "$functionPrefix$name"
            appendLine("vec4 $function(vec2 uv, float bias) {")
            appendLine(
                "    vec3 coordinate = vec3(uv.x, " +
                    "(fract(uv.y) + ${offset}.0) / 3.0, $layerVariable);",
            )
            appendLine("    switch ($arrayVariable) {")
            for (index in 0 until 16) {
                appendLine(
                    "        case ${index}u: return minosoftCompanion$name(" +
                        "texture(uTextures[$index], coordinate, bias));",
                )
            }
            appendLine("        default: return $neutral;")
            appendLine("    }")
            appendLine("}")
            appendLine("vec4 $function(vec2 uv) { return $function(uv, 0.0); }")
            appendLine("vec4 ${function}Lod(vec2 uv, float lod) {")
            appendLine(
                "    vec3 coordinate = vec3(uv.x, " +
                    "(fract(uv.y) + ${offset}.0) / 3.0, $layerVariable);",
            )
            appendLine("    switch ($arrayVariable) {")
            for (index in 0 until 16) {
                appendLine(
                    "        case ${index}u: return minosoftCompanion$name(" +
                        "textureLod(uTextures[$index], coordinate, lod));",
                )
            }
            appendLine("        default: return $neutral;")
            appendLine("    }")
            appendLine("}")
            appendLine("vec4 ${function}Grad(vec2 uv, vec2 dx, vec2 dy) {")
            appendLine(
                "    vec3 coordinate = vec3(uv.x, " +
                    "(fract(uv.y) + ${offset}.0) / 3.0, $layerVariable);",
            )
            appendLine("    switch ($arrayVariable) {")
            for (index in 0 until 16) {
                appendLine(
                    "        case ${index}u: return minosoftCompanion$name(" +
                        "textureGrad(uTextures[$index], coordinate, " +
                        "vec2(dx.x, dx.y / 3.0), vec2(dy.x, dy.y / 3.0)));",
                )
            }
            appendLine("        default: return $neutral;")
            appendLine("    }")
            appendLine("}")
            appendLine("vec4 ${function}Fetch(ivec2 coordinate, int lod) {")
            appendLine("    ivec2 pageSize = $diffuseSizeFunction(lod);")
            appendLine(
                "    ivec3 layered = ivec3(coordinate + ivec2(0, pageSize.y * $offset), int($layerVariable));",
            )
            appendLine("    switch ($arrayVariable) {")
            for (index in 0 until 16) {
                appendLine(
                    "        case ${index}u: return minosoftCompanion$name(" +
                        "texelFetch(uTextures[$index], layered, lod));",
                )
            }
            appendLine("        default: return $neutral;")
            appendLine("    }")
            appendLine("}")
            appendLine("ivec2 ${function}Size(int lod) { return $diffuseSizeFunction(lod); }")
        }
    }.trimEnd()

    private val NEUTRAL_MATERIAL_FUNCTIONS = """
        vec4 minosoftNeutralDiffuse(vec2 uv) { return vec4(1.0); }
        vec4 minosoftNeutralDiffuse(vec2 uv, float value) { return vec4(1.0); }
        vec4 minosoftNeutralDiffuse(vec2 uv, vec2 dx, vec2 dy) { return vec4(1.0); }
        vec4 minosoftNeutralDiffuse(ivec2 coordinate, int lod) { return vec4(1.0); }
        ivec2 minosoftNeutralDiffuseSize(int lod) { return ivec2(1); }
        vec4 minosoftNeutralNormal(vec2 uv) {
            return vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);
        }
        vec4 minosoftNeutralNormal(vec2 uv, float value) {
            return vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);
        }
        vec4 minosoftNeutralNormal(vec2 uv, vec2 dx, vec2 dy) {
            return vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);
        }
        vec4 minosoftNeutralNormal(ivec2 coordinate, int lod) {
            return vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);
        }
        ivec2 minosoftNeutralNormalSize(int lod) { return ivec2(1); }
        vec4 minosoftNeutralSpecular(vec2 uv) { return vec4(0.0, 0.0, 0.0, 1.0); }
        vec4 minosoftNeutralSpecular(vec2 uv, float value) { return vec4(0.0, 0.0, 0.0, 1.0); }
        vec4 minosoftNeutralSpecular(vec2 uv, vec2 dx, vec2 dy) { return vec4(0.0, 0.0, 0.0, 1.0); }
        vec4 minosoftNeutralSpecular(ivec2 coordinate, int lod) { return vec4(0.0, 0.0, 0.0, 1.0); }
        ivec2 minosoftNeutralSpecularSize(int lod) { return ivec2(1); }
    """.trimIndent()

    private val MODERN_SKY_BASIC_VERTEX_HEADER = """
        // minosoft:scene_bridge SKY_POSITION SKY_COLOR uSkyColor,uSkyViewProjectionMatrix
        // minosoft:scene_bridge SUN_SCATTER SUN_SCATTER uScatterMatrix,uSunPosition,uIntensity
        layout (location = 0) in vec3 vinPosition;
        vec2 lmCoord;
        #if defined(MINOSOFT_STATE_ABI_SUN_SCATTER)
        uniform mat4 uScatterMatrix;
        uniform vec3 uSunPosition;
        uniform float uIntensity;
        vec4 minosoftSkyBasicPosition() {
            return uScatterMatrix * vec4(vinPosition, 1.0);
        }
        vec4 minosoftSkyBasicColor() {
            return vec4(0.0);
        }
        #else
        uniform mat4 uSkyViewProjectionMatrix;
        uniform vec4 uSkyColor;
        vec4 minosoftSkyBasicPosition() {
            return uSkyViewProjectionMatrix * vec4(vinPosition, 1.0);
        }
        vec4 minosoftSkyBasicColor() {
            return uSkyColor;
        }
        #endif
    """.trimIndent()

    private fun modernTerrainVertexHeader(shadow: Boolean) = """
        // minosoft:terrain_bridge modern-compatibility
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinAmbientUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinLightTint;
        layout (location = 4) in vec2 minosoftMcEntity;
        layout (location = 5) in vec2 minosoftPhysicalMidTexCoord;
        layout (location = 6) in vec4 at_tangent;
        layout (location = 7) in vec3 vaNormal;
        layout (location = 8) in vec4 minosoftMidBlock;

        // minosoft:texture_array_index minosoftTextureArray
        flat out uint minosoftTextureArray;
        out float minosoftTextureLayer;

        vec2 minosoftTerrainUv() {
            uint packed = floatBitsToUint(vinAmbientUV);
            vec2 physical =
                vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
            return minosoftMaterialLogicalUv(physical, minosoftTextureArray);
        }
        #define mc_midTexCoord minosoftMaterialLogicalUv(minosoftPhysicalMidTexCoord, minosoftTextureArray)
        vec2 minosoftTerrainLightUv() {
            uint light = floatBitsToUint(vinLightTint) >> 24u;
            return vec2(float(light & 0xFu), float(light >> 4u)) * 16.0;
        }
        vec4 minosoftTerrainColor() {
            uint packed = floatBitsToUint(vinLightTint);
            vec3 color = vec3(
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
            const float ao[4] = float[4](1.0, 0.85, 0.75, 0.60);
            uint ambient = (floatBitsToUint(vinAmbientUV) >> 24u) & 0x3u;
            return vec4(color, ao[ambient]);
        }
    """.trimIndent()

    private fun modernShadowSceneVertexBody(source: String): String {
        val shadowDistance = Regex("""\bconst\s+float\s+shadowDistance\s*=\s*[^;]+;""")
            .find(source)?.value ?: "const float shadowDistance = 128.0;"
        val shadowMapBias = Regex("""\bconst\s+float\s+shadowMapBias\s*=\s*[^;]+;""")
            .find(source)?.value ?: "const float shadowMapBias = 0.8;"
        return """
            // minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uSkeletalBuffer
            // minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uSkeletalBuffer
            // minosoft:scene_bridge PLAYER_SKELETAL PLAYER uTextures,uSkeletalBuffer,uIndexLayer,uSkinParts,uInflate,uHideBase,uFeaturePart
            // minosoft:scene_bridge BLOCK_FEATURE BLOCK uTextures,uMatrix
            // minosoft:scene_bridge BLOCK_FEATURE FLASHING_BLOCK uTextures,uMatrix
            // minosoft:scene_bridge POSITION_TEXTURE ENTITY_FLAME uTextures,uMatrix

            flat out int mat;
            out vec2 texCoord;
            out vec2 texcoord;
            flat out vec3 sunVec;
            flat out vec3 upVec;
            out vec4 position;
            flat out vec4 glColor;
            out vec4 color;
            flat out uint minosoftTextureArray;
            out float minosoftTextureLayer;
            #ifdef CONNECTED_GLASS_EFFECT
            out vec2 signMidCoordPos;
            flat out vec2 absMidCoordPos;
            #endif

            uniform mat4 shadowProjection;
            uniform mat4 shadowModelView;
            uniform mat4 gbufferModelView;
            uniform vec3 sunPosition;
            uniform int entityId;
            uniform int blockEntityId;
            uniform int currentRenderedItemId;
            $shadowDistance
            $shadowMapBias

            int minosoftShadowMaterial() {
                if (currentRenderedItemId != 0) return currentRenderedItemId;
                if (blockEntityId != 0) return blockEntityId;
                return entityId;
            }

            void minosoftFinishShadow(vec4 worldPosition, vec2 uv, uint packedTexture) {
                minosoftTextureArray = packedTexture >> 28u;
                minosoftTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
                position = worldPosition;
                texCoord = minosoftMaterialLogicalUv(uv, minosoftTextureArray);
                texcoord = texCoord;
                glColor = vec4(1.0);
                color = glColor;
                mat = minosoftShadowMaterial();
                sunVec = normalize(sunPosition);
                upVec = normalize(gbufferModelView[1].xyz);
                #ifdef CONNECTED_GLASS_EFFECT
                signMidCoordPos = vec2(0.0);
                absMidCoordPos = vec2(0.0);
                #endif
                gl_Position = shadowProjection * shadowModelView * worldPosition;
                float radial = length(gl_Position.xy);
                float distortion = radial * shadowMapBias + (1.0 - shadowMapBias);
                gl_Position.xy /= distortion;
                gl_Position.z *= 0.2;
            }

            #if defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
            layout (location = 0) in vec3 vinPosition;
            layout (location = 1) in vec2 vinUV;
            layout (location = 2) in float vinTransformNormal;
            layout (location = 3) in float vinTexture;
            #include "minosoft:skeletal/buffer"
            void main() {
                uint packed = floatBitsToUint(vinTransformNormal);
                mat4 transform = uSkeletalTransforms[(packed >> 12u) & 0x7Fu];
                minosoftFinishShadow(
                    transform * vec4(vinPosition, 1.0),
                    vinUV,
                    floatBitsToUint(vinTexture)
                );
            }
            #elif defined(MINOSOFT_STATE_ABI_PLAYER)
            layout (location = 0) in vec3 vinPosition;
            layout (location = 1) in vec2 vinUV;
            layout (location = 2) in float vinPartTransformNormal;
            uniform uint uIndexLayer;
            uniform uint uSkinParts;
            uniform float uInflate;
            uniform bool uHideBase;
            uniform uint uFeaturePart;
            #include "minosoft:skeletal/buffer"
            float minosoftShadowNormalPart(uint data) {
                return data < 8u ? (float(data) / 8.0) - 1.0 : float(data - 8u) / 7.0;
            }
            vec3 minosoftShadowNormal(uint normal) {
                return vec3(
                    minosoftShadowNormalPart(normal & 0x0Fu),
                    minosoftShadowNormalPart((normal >> 8u) & 0x0Fu),
                    minosoftShadowNormalPart((normal >> 4u) & 0x0Fu)
                );
            }
            void main() {
                uint packed = floatBitsToUint(vinPartTransformNormal);
                uint skinPart = (packed >> 19u) & 0xFFu;
                bool hiddenFeature = skinPart >= 0xF0u && skinPart != uFeaturePart;
                bool hiddenBase = skinPart == 0u && uHideBase;
                bool hiddenSkinPart =
                    skinPart > 0u && skinPart < 0xF0u &&
                    ((1u << (skinPart - 1u)) & uSkinParts) == 0u;
                if (hiddenFeature || hiddenBase || hiddenSkinPart) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    return;
                }
                mat4 transform = uSkeletalTransforms[(packed >> 12u) & 0x7Fu];
                vec3 inflated = vinPosition + minosoftShadowNormal(packed & 0xFFFu) * uInflate;
                minosoftFinishShadow(transform * vec4(inflated, 1.0), vinUV, uIndexLayer);
            }
            #elif defined(MINOSOFT_STATE_ABI_BLOCK) || defined(MINOSOFT_STATE_ABI_FLASHING_BLOCK)
            layout (location = 0) in vec3 vinPosition;
            layout (location = 1) in float vinUV;
            layout (location = 2) in float vinTexture;
            layout (location = 3) in float vinTint;
            uniform mat4 uMatrix;
            vec2 minosoftShadowBlockUv(uint packed) {
                return vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
            }
            void main() {
                minosoftFinishShadow(
                    uMatrix * vec4(vinPosition, 1.0),
                    minosoftShadowBlockUv(floatBitsToUint(vinUV)),
                    floatBitsToUint(vinTexture)
                );
            }
            #else
            layout (location = 0) in vec3 vinPosition;
            layout (location = 1) in vec2 vinUV;
            layout (location = 2) in float vinTexture;
            uniform mat4 uMatrix;
            void main() {
                minosoftFinishShadow(
                    uMatrix * vec4(vinPosition, 1.0),
                    vinUV,
                    floatBitsToUint(vinTexture)
                );
            }
            #endif
        """.trimIndent()
    }

    private val MODERN_TEXTURED_SCENE_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_TEXTURE_2D GENERIC_TEXTURE_2D uTextures
        // minosoft:scene_bridge WORLD_BORDER WORLD_BORDER uTextures,uViewProjectionMatrix,uCameraPosition,fog,uTintColor,uTexture,uTextureOffset
        out vec2 texCoord;
        out vec2 lmCoord;
        out vec4 lmtexcoord;
        out vec4 color;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        out vec3 normal;
        flat out vec4 glColor;
        // minosoft:texture_array_index minosoftTextureArray
        flat out uint minosoftTextureArray;
        out float minosoftTextureLayer;
        uniform mat4 gbufferModelView;
        uniform vec3 sunPosition;

        vec4 minosoftTexturedColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        void minosoftFinishTextured(vec4 position, vec2 uv, uint texture, vec4 tintColor) {
            minosoftTextureArray = texture >> 28u;
            minosoftTextureLayer = float((texture >> 12u) & 0xFFFFu);
            gl_Position = position;
            texCoord = minosoftMaterialLogicalUv(uv, minosoftTextureArray);
            lmCoord = vec2(1.0);
            lmtexcoord = vec4(texCoord, lmCoord);
            color = tintColor;
            exposure = 0.0;
            HELD_ITEM_BRIGHTNESS = 0.0;
            normal = vec3(0.0, 0.0, 1.0);
            glColor = tintColor;
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
        }

        #if defined(MINOSOFT_STATE_ABI_GENERIC_TEXTURE_2D)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        void main() {
            minosoftFinishTextured(
                vec4(vinPosition, 1.0),
                vinUV,
                floatBitsToUint(vinTexture),
                minosoftTexturedColor(floatBitsToUint(vinTintColor))
            );
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinUVIndex;
        layout (location = 2) in float vinWidth;
        layout (location = 3) in vec3 vinNormal;
        uniform mat4 uViewProjectionMatrix;
        uniform vec3 uCameraPosition;
        uniform vec4 uTintColor;
        uniform uint uTexture;
        uniform float uTextureOffset;
        const vec2 minosoftBorderUv[4] = vec2[4](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 0.0),
            vec2(1.0, 1.0)
        );
        void main() {
            vec3 position = vinPosition;
            if (position.y < 0.0) {
                position.y = uCameraPosition.y - 150.0;
            } else if (position.y > 0.0) {
                position.y = uCameraPosition.y + 150.0;
            }
            vec2 uv = minosoftBorderUv[floatBitsToUint(vinUVIndex)];
            uv.x *= vinWidth;
            uv.y *= 150.0;
            uv += vec2(uTextureOffset);
            minosoftFinishTextured(
                uViewProjectionMatrix * vec4(position, 1.0),
                uv,
                uTexture,
                uTintColor
            );
            normal = normalize(vinNormal);
        }
        #endif
    """.trimIndent()

    private val MODERN_TERRAIN_FRAGMENT_HEADER = """
        // minosoft:texture_array_index minosoftTextureArray
        flat in uint minosoftTextureArray;
        in float minosoftTextureLayer;
        uniform sampler2DArray uTextures[16];

        vec3 minosoftStaticTextureCoordinate(vec3 coordinate) {
            return vec3(coordinate.x, fract(coordinate.y) / 3.0, coordinate.z);
        }
        vec3 minosoftDynamicTextureCoordinate(vec3 coordinate) { return coordinate; }
        vec2 minosoftStaticTextureGradient(vec2 gradient) {
            return vec2(gradient.x, gradient.y / 3.0);
        }
        vec2 minosoftDynamicTextureGradient(vec2 gradient) { return gradient; }

        vec4 minosoftTextureArraySample(vec2 uv, float bias) {
            vec3 coordinate = vec3(uv, minosoftTextureLayer);
            switch (minosoftTextureArray) {
                case 0u: return texture(uTextures[0], coordinate, bias);
                case 1u: return texture(uTextures[1], coordinate, bias);
                case 2u: return texture(uTextures[2], coordinate, bias);
                case 3u: return texture(uTextures[3], coordinate, bias);
                case 4u: return texture(uTextures[4], coordinate, bias);
                case 5u: return texture(uTextures[5], coordinate, bias);
                case 6u: return texture(uTextures[6], coordinate, bias);
                case 7u: return texture(uTextures[7], coordinate, bias);
                case 8u: return texture(uTextures[8], coordinate, bias);
                case 9u: return texture(uTextures[9], coordinate, bias);
                case 10u: return texture(uTextures[10], coordinate, bias);
                case 11u: return texture(uTextures[11], coordinate, bias);
                case 12u: return texture(uTextures[12], coordinate, bias);
                case 13u: return texture(uTextures[13], coordinate, bias);
                case 14u: return texture(uTextures[14], coordinate, bias);
                case 15u: return texture(uTextures[15], coordinate, bias);
                default: return vec4(1.0);
            }
        }
        vec4 minosoftSampleTexture(vec2 uv) {
            return minosoftTextureArraySample(uv, 0.0);
        }
        vec4 minosoftSampleTexture(vec2 uv, float bias) {
            return minosoftTextureArraySample(uv, bias);
        }
        vec4 minosoftSampleTextureArrayLod(
            uint textureArray,
            float textureLayer,
            vec2 uv,
            float lod
        ) {
            vec3 coordinate = vec3(uv, textureLayer);
            switch (textureArray) {
                case 0u: return textureLod(uTextures[0], coordinate, lod);
                case 1u: return textureLod(uTextures[1], coordinate, lod);
                case 2u: return textureLod(uTextures[2], coordinate, lod);
                case 3u: return textureLod(uTextures[3], coordinate, lod);
                case 4u: return textureLod(uTextures[4], coordinate, lod);
                case 5u: return textureLod(uTextures[5], coordinate, lod);
                case 6u: return textureLod(uTextures[6], coordinate, lod);
                case 7u: return textureLod(uTextures[7], coordinate, lod);
                case 8u: return textureLod(uTextures[8], coordinate, lod);
                case 9u: return textureLod(uTextures[9], coordinate, lod);
                case 10u: return textureLod(uTextures[10], coordinate, lod);
                case 11u: return textureLod(uTextures[11], coordinate, lod);
                case 12u: return textureLod(uTextures[12], coordinate, lod);
                case 13u: return textureLod(uTextures[13], coordinate, lod);
                case 14u: return textureLod(uTextures[14], coordinate, lod);
                case 15u: return textureLod(uTextures[15], coordinate, lod);
                default: return vec4(1.0);
            }
        }
        vec4 minosoftSampleTextureLod(vec2 uv, float lod) {
            return minosoftSampleTextureArrayLod(
                minosoftTextureArray,
                minosoftTextureLayer,
                uv,
                lod
            );
        }
        vec4 minosoftSampleTextureGrad(vec2 uv, vec2 dx, vec2 dy) {
            vec3 coordinate = vec3(uv, minosoftTextureLayer);
            switch (minosoftTextureArray) {
                case 0u: return textureGrad(uTextures[0], coordinate, dx, dy);
                case 1u: return textureGrad(uTextures[1], coordinate, dx, dy);
                case 2u: return textureGrad(uTextures[2], coordinate, dx, dy);
                case 3u: return textureGrad(uTextures[3], coordinate, dx, dy);
                case 4u: return textureGrad(uTextures[4], coordinate, dx, dy);
                case 5u: return textureGrad(uTextures[5], coordinate, dx, dy);
                case 6u: return textureGrad(uTextures[6], coordinate, dx, dy);
                case 7u: return textureGrad(uTextures[7], coordinate, dx, dy);
                case 8u: return textureGrad(uTextures[8], coordinate, dx, dy);
                case 9u: return textureGrad(uTextures[9], coordinate, dx, dy);
                case 10u: return textureGrad(uTextures[10], coordinate, dx, dy);
                case 11u: return textureGrad(uTextures[11], coordinate, dx, dy);
                case 12u: return textureGrad(uTextures[12], coordinate, dx, dy);
                case 13u: return textureGrad(uTextures[13], coordinate, dx, dy);
                case 14u: return textureGrad(uTextures[14], coordinate, dx, dy);
                case 15u: return textureGrad(uTextures[15], coordinate, dx, dy);
                default: return vec4(1.0);
            }
        }
        vec4 minosoftSampleTextureFetch(ivec2 coordinate, int lod) {
            ivec3 layered = ivec3(coordinate, int(minosoftTextureLayer));
            switch (minosoftTextureArray) {
                case 0u: return texelFetch(uTextures[0], layered, lod);
                case 1u: return texelFetch(uTextures[1], layered, lod);
                case 2u: return texelFetch(uTextures[2], layered, lod);
                case 3u: return texelFetch(uTextures[3], layered, lod);
                case 4u: return texelFetch(uTextures[4], layered, lod);
                case 5u: return texelFetch(uTextures[5], layered, lod);
                case 6u: return texelFetch(uTextures[6], layered, lod);
                case 7u: return texelFetch(uTextures[7], layered, lod);
                case 8u: return texelFetch(uTextures[8], layered, lod);
                case 9u: return texelFetch(uTextures[9], layered, lod);
                case 10u: return texelFetch(uTextures[10], layered, lod);
                case 11u: return texelFetch(uTextures[11], layered, lod);
                case 12u: return texelFetch(uTextures[12], layered, lod);
                case 13u: return texelFetch(uTextures[13], layered, lod);
                case 14u: return texelFetch(uTextures[14], layered, lod);
                case 15u: return texelFetch(uTextures[15], layered, lod);
                default: return vec4(1.0);
            }
        }
        ivec2 minosoftStaticTextureSize(ivec3 size) {
            return ivec2(size.x, size.y / 3);
        }
        ivec2 minosoftDynamicTextureSize(ivec3 size) { return size.xy; }
        ivec2 minosoftSampleTextureArraySize(uint textureArray, int lod) {
            switch (textureArray) {
                case 0u: return minosoftMaterialTextureSize(textureSize(uTextures[0], lod));
                case 1u: return minosoftMaterialTextureSize(textureSize(uTextures[1], lod));
                case 2u: return minosoftMaterialTextureSize(textureSize(uTextures[2], lod));
                case 3u: return minosoftMaterialTextureSize(textureSize(uTextures[3], lod));
                case 4u: return minosoftMaterialTextureSize(textureSize(uTextures[4], lod));
                case 5u: return minosoftMaterialTextureSize(textureSize(uTextures[5], lod));
                case 6u: return minosoftMaterialTextureSize(textureSize(uTextures[6], lod));
                case 7u: return minosoftMaterialTextureSize(textureSize(uTextures[7], lod));
                case 8u: return minosoftMaterialTextureSize(textureSize(uTextures[8], lod));
                case 9u: return minosoftMaterialTextureSize(textureSize(uTextures[9], lod));
                case 10u: return minosoftMaterialTextureSize(textureSize(uTextures[10], lod));
                case 11u: return minosoftMaterialTextureSize(textureSize(uTextures[11], lod));
                case 12u: return minosoftMaterialTextureSize(textureSize(uTextures[12], lod));
                case 13u: return minosoftMaterialTextureSize(textureSize(uTextures[13], lod));
                case 14u: return minosoftMaterialTextureSize(textureSize(uTextures[14], lod));
                case 15u: return minosoftMaterialTextureSize(textureSize(uTextures[15], lod));
                default: return ivec2(1);
            }
        }
        ivec2 minosoftSampleTextureSize(int lod) {
            return minosoftSampleTextureArraySize(minosoftTextureArray, lod);
        }
    """.trimIndent().markMaterialPhysicalCoordinates()

    /**
     * Fullscreen world-space-reflection programs do not have one draw-local
     * texture identity. Their serialized face data selects the source-native
     * Minosoft texture array and layer instead, so expose the same compactable
     * array switch used by terrain without publishing a false draw-wide
     * atlasSize value.
     */
    private val SOURCE_NATIVE_BLOCK_ATLAS_FRAGMENT_HEADER = buildString {
        appendLine("uniform sampler2DArray uTextures[16];")
        appendLine("vec3 minosoftStaticTextureCoordinate(vec3 coordinate) {")
        appendLine("    return vec3(coordinate.x, fract(coordinate.y) / 3.0, coordinate.z);")
        appendLine("}")
        appendLine("vec3 minosoftDynamicTextureCoordinate(vec3 coordinate) { return coordinate; }")
        appendLine("ivec2 minosoftStaticTextureSize(ivec3 size) {")
        appendLine("    return ivec2(size.x, size.y / 3);")
        appendLine("}")
        appendLine("ivec2 minosoftDynamicTextureSize(ivec3 size) { return size.xy; }")
        appendLine("vec4 minosoftSampleTextureArray(")
        appendLine("    uint textureArray,")
        appendLine("    float textureLayer,")
        appendLine("    vec2 uv")
        appendLine(") {")
        appendLine("    vec3 coordinate = vec3(uv, textureLayer);")
        appendLine("    switch (textureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return texture(uTextures[$index], " +
                    "minosoftMaterialTextureCoordinate(coordinate));",
            )
        }
        appendLine("        default: return vec4(1.0);")
        appendLine("    }")
        appendLine("}")
        appendLine("vec4 minosoftSampleTextureArrayLod(")
        appendLine("    uint textureArray,")
        appendLine("    float textureLayer,")
        appendLine("    vec2 uv,")
        appendLine("    float lod")
        appendLine(") {")
        appendLine("    vec3 coordinate = vec3(uv, textureLayer);")
        appendLine("    switch (textureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return textureLod(uTextures[$index], " +
                    "minosoftMaterialTextureCoordinate(coordinate), lod);",
            )
        }
        appendLine("        default: return vec4(1.0);")
        appendLine("    }")
        appendLine("}")
        appendLine("ivec2 minosoftSampleTextureArraySize(uint textureArray, int lod) {")
        appendLine("    switch (textureArray) {")
        for (index in 0 until 16) {
            appendLine(
                "        case ${index}u: return " +
                    "minosoftMaterialTextureSize(textureSize(uTextures[$index], lod));",
            )
        }
        appendLine("        default: return ivec2(1);")
        appendLine("    }")
        appendLine("}")
    }.trimEnd()

    private val TERRAIN_VERTEX_HEADER = """
        // minosoft:terrain_bridge
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinAmbientUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinLightTint;
        layout (location = 4) in vec2 minosoftMcEntity;
        layout (location = 7) in vec3 vaNormal;

        flat out uint minosoftTextureArray;
        out float minosoftTextureLayer;
        flat out uint minosoftLightIndex;
        out float minosoftFogFragCoord;

        uniform mat4 gbufferProjection;

        vec2 minosoftLegacyUv() {
            uint packed = floatBitsToUint(vinAmbientUV);
            vec2 physicalUv =
                vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
            return minosoftMaterialLogicalUv(physicalUv, minosoftTextureArray);
        }
        vec2 minosoftLegacyLightUv() {
            uint light = floatBitsToUint(vinLightTint) >> 24u;
            return vec2(float(light & 0xFu), float(light >> 4u)) / 15.0;
        }
        vec4 minosoftLegacyColor() {
            uint packed = floatBitsToUint(vinLightTint);
            vec3 color = vec3(
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
            const float ao[4] = float[4](1.0, 0.85, 0.75, 0.60);
            uint ambient = (floatBitsToUint(vinAmbientUV) >> 24u) & 0x3u;
            return vec4(color * ao[ambient], 1.0);
        }
    """.trimIndent()

    private val CLOUD_VERTEX_HEADER = """
        // minosoft:scene_bridge CLOUD CLOUD uViewProjectionMatrix,uCameraPosition,fog,uCloudsColor,uOffset,uYOffset
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in uint vinSide;
        out float minosoftFogFragCoord;
        out vec2 lmCoord;

        uniform mat4 uViewProjectionMatrix;
        uniform vec3 uCameraPosition;
        uniform vec3 uCloudsColor;
        uniform float uOffset;
        uniform float uYOffset;

        vec3 minosoftCloudPosition() {
            return vec3(vinPosition.x - uOffset, vinPosition.y + uYOffset, vinPosition.z);
        }
        vec4 minosoftCloudColor() {
            float brightness = vinSide == 0u ? 0.7 :
                (vinSide == 1u ? 1.0 : (vinSide < 4u ? 0.9 : 0.8));
            return vec4(uCloudsColor * brightness, 1.0);
        }
    """.trimIndent()

    private val CLOUD_FRAGMENT_HEADER = """
        layout (location = 0) out vec4 minosoftFragmentColor;
        in float minosoftFogFragCoord;
        uniform float uFogStart;
        uniform float uFogDistance;
        uniform vec4 uFogColor;
        uniform uint uFogFlags;
        vec4 minosoftCloudTexture(vec2 ignoredUv) {
            return vec4(1.0);
        }
    """.trimIndent()

    private val SKY_TEXTURE_VERTEX_HEADER = """
        // minosoft:scene_bridge SKY_TEXTURE SKY_TEXTURE uTextures,uSkyViewProjectionMatrix,uTexture,uTintColor
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in uint minosoftUvIndex;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;

        uniform mat4 uSkyViewProjectionMatrix;
        uniform uint uTexture;
        uniform vec4 uTintColor;

        const vec2 minosoftUvCorners[4] = vec2[4](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 0.0),
            vec2(1.0, 1.0)
        );
        vec2 minosoftSkyUv() {
            return minosoftMaterialLogicalUv(
                minosoftUvCorners[minosoftUvIndex] * 20.0,
                minosoftSceneTextureArray
            );
        }
    """.trimIndent()

    private val PLANET_VERTEX_BODY = """
        // minosoft:scene_bridge PLANET PLANET uMatrix,uTintColor,uTextures
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        out vec4 color;
        out vec2 coord0;
        out vec2 coord1;
        flat out uint minosoftSceneTextureArray;
        out float minosoftSceneTextureLayer;
        uniform mat4 uMatrix;
        uniform vec4 uTintColor;
        void main() {
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftSceneTextureArray = packedTexture >> 28u;
            minosoftSceneTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            gl_Position = uMatrix * vec4(vinPosition, 1.0);
            color = uTintColor;
            coord0 = minosoftMaterialLogicalUv(vinUV, minosoftSceneTextureArray);
            coord1 = vec2(1.0);
        }
    """.trimIndent()

    private val BASIC_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_COLOR COLOR uViewProjectionMatrix
        // minosoft:scene_bridge POSITION_COLOR_LIGHT LIGHT_COLOR uLightMapBuffer,uViewProjectionMatrix
        // minosoft:scene_bridge SKY_POSITION SKY_COLOR uSkyViewProjectionMatrix,uSkyColor
        out vec4 color;
        out float minosoftFogFragCoord;
        #if defined(MINOSOFT_STATE_ABI_SKY_COLOR)
        layout (location = 0) in vec3 vinPosition;
        uniform mat4 uSkyViewProjectionMatrix;
        uniform vec4 uSkyColor;
        void main() {
            gl_Position = uSkyViewProjectionMatrix * vec4(vinPosition, 1.0);
            color = uSkyColor;
            minosoftFogFragCoord = 0.0;
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinTintColor;
        #if defined(MINOSOFT_STATE_ABI_LIGHT_COLOR)
        layout (location = 2) in float vinLight;
        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftBasicLightMap[256];
        };
        #endif
        uniform mat4 uViewProjectionMatrix;
        uniform vec3 cameraPosition;
        vec4 minosoftBasicColor() {
            uint packed = floatBitsToUint(vinTintColor);
            vec4 decoded = vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
            #if defined(MINOSOFT_STATE_ABI_LIGHT_COLOR)
            decoded *= minosoftBasicLightMap[floatBitsToUint(vinLight) & 0xFFu];
            #endif
            return decoded;
        }
        void main() {
            gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
            color = minosoftBasicColor();
            minosoftFogFragCoord = length(vinPosition - cameraPosition);
        }
        #endif
    """.trimIndent()

    private val BASIC_FRAGMENT_HEADER = """
        layout (location = 0) out vec4 minosoftFragmentColor;
        in float minosoftFogFragCoord;
        uniform float fogDensity;
        uniform float fogStart;
        uniform float fogEnd;
        uniform vec4 iris_FogColor;
    """.trimIndent()

    private val LEGACY_TERRAIN_FRAGMENT_HEADER = """
        flat in uint minosoftLightIndex;
        in float minosoftFogFragCoord;

        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftLightMap[256];
        };
        #if defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
        uniform float uFogStart;
        uniform float uFogDistance;
        uniform vec4 uFogColor;
        uniform uint uFogFlags;
        #define fogDensity (float(uFogFlags != 0u) / max(sqrt(max(uFogStart + uFogDistance, 0.0)) - sqrt(max(uFogStart, 0.0)), 0.00001))
        #define fogStart sqrt(max(uFogStart, 0.0))
        #define fogEnd sqrt(max(uFogStart + uFogDistance, 0.0))
        #define iris_FogColor uFogColor
        #else
        uniform float fogDensity;
        uniform float fogStart;
        uniform float fogEnd;
        uniform vec4 iris_FogColor;
        #endif

        vec4 minosoftSampleLightmap(vec2 ignoredUv) {
            #if $LEGACY_TEXTURED_SCENE_CONDITION
            return vec4(1.0);
            #else
            return minosoftLightMap[minosoftLightIndex];
            #endif
        }
    """.trimIndent()

    private val LEGACY_MATERIAL_SELECTOR_FUNCTIONS = """
        vec4 minosoftSampleSelectedMaterial(int material, vec2 uv, float bias) {
            if (material == 1) return minosoftSampleNormal(uv, bias);
            if (material == 2) return minosoftSampleSpecular(uv, bias);
            return minosoftSampleTexture(uv, bias);
        }
        vec4 minosoftSampleSelectedMaterialGrad(
            int material,
            vec2 uv,
            vec2 dx,
            vec2 dy
        ) {
            if (material == 1) return minosoftSampleNormalGrad(uv, dx, dy);
            if (material == 2) return minosoftSampleSpecularGrad(uv, dx, dy);
            return minosoftSampleTextureGrad(uv, dx, dy);
        }
    """.trimIndent()

    private val LEGACY_SCENE_MATERIAL_SELECTOR_FUNCTIONS = """
        vec4 minosoftSampleSelectedSceneMaterial(int material, vec2 uv, float bias) {
            if (material == 1) return minosoftSampleSceneNormal(uv, bias);
            if (material == 2) return minosoftSampleSceneSpecular(uv, bias);
            return minosoftSampleSceneTexture(uv, bias);
        }
        vec4 minosoftSampleSelectedSceneMaterialGrad(
            int material,
            vec2 uv,
            vec2 dx,
            vec2 dy
        ) {
            if (material == 1) return minosoftSampleSceneNormalGrad(uv, dx, dy);
            if (material == 2) return minosoftSampleSceneSpecularGrad(uv, dx, dy);
            return minosoftSampleSceneTextureGrad(uv, dx, dy);
        }
    """.trimIndent()

    private val TERRAIN_FRAGMENT_HEADER = """
        layout (location = 0) out vec4 minosoftFragmentColor;
        flat in uint minosoftTextureArray;
        in float minosoftTextureLayer;
        flat in uint minosoftLightIndex;
        in float minosoftFogFragCoord;

        uniform sampler2DArray uTextures[16];
        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftLightMap[256];
        };
        #if defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
        uniform float uFogStart;
        uniform float uFogDistance;
        uniform vec4 uFogColor;
        uniform uint uFogFlags;
        #define fogDensity (float(uFogFlags != 0u) / max(sqrt(max(uFogStart + uFogDistance, 0.0)) - sqrt(max(uFogStart, 0.0)), 0.00001))
        #define fogStart sqrt(max(uFogStart, 0.0))
        #define fogEnd sqrt(max(uFogStart + uFogDistance, 0.0))
        #define iris_FogColor uFogColor
        #else
        uniform float fogDensity;
        uniform float fogStart;
        uniform float fogEnd;
        uniform vec4 iris_FogColor;
        #endif

        vec3 minosoftStaticTextureCoordinate(vec3 coordinate) {
            return vec3(coordinate.x, fract(coordinate.y) / 3.0, coordinate.z);
        }
        vec3 minosoftDynamicTextureCoordinate(vec3 coordinate) { return coordinate; }

        vec4 minosoftSampleTexture(vec2 uv) {
            vec3 coordinate = vec3(uv, minosoftTextureLayer);
            switch (minosoftTextureArray) {
                case 0u: return texture(uTextures[0], coordinate);
                case 1u: return texture(uTextures[1], coordinate);
                case 2u: return texture(uTextures[2], coordinate);
                case 3u: return texture(uTextures[3], coordinate);
                case 4u: return texture(uTextures[4], coordinate);
                case 5u: return texture(uTextures[5], coordinate);
                case 6u: return texture(uTextures[6], coordinate);
                case 7u: return texture(uTextures[7], coordinate);
                case 8u: return texture(uTextures[8], coordinate);
                case 9u: return texture(uTextures[9], coordinate);
                case 10u: return texture(uTextures[10], coordinate);
                case 11u: return texture(uTextures[11], coordinate);
                case 12u: return texture(uTextures[12], coordinate);
                case 13u: return texture(uTextures[13], coordinate);
                case 14u: return texture(uTextures[14], coordinate);
                case 15u: return texture(uTextures[15], coordinate);
                default: return vec4(1.0);
            }
        }
        vec4 minosoftSampleLightmap(vec2 ignoredUv) {
            #if $LEGACY_TEXTURED_SCENE_CONDITION
            return vec4(1.0);
            #else
            return minosoftLightMap[minosoftLightIndex];
            #endif
        }
    """.trimIndent().markMaterialPhysicalCoordinates()

    private const val LEGACY_TEXTURED_SCENE_CONDITION =
        "defined(MINOSOFT_STATE_ABI_GENERIC_TEXTURE) || " +
            "defined(MINOSOFT_STATE_ABI_WORLD_BORDER) || " +
            "defined(MINOSOFT_STATE_ABI_SKELETAL_TINTED) || " +
            "defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP) || " +
            "defined(MINOSOFT_STATE_ABI_ARM) || " +
            "defined(MINOSOFT_STATE_ABI_PARTICLE)"

    private val TEXTURED_SCENE_VERTEX_BODY = """
        // minosoft:scene_bridge POSITION_TEXTURE GENERIC_TEXTURE uTextures,uViewProjectionMatrix
        // minosoft:scene_bridge WORLD_BORDER WORLD_BORDER uTextures,uViewProjectionMatrix,uCameraPosition,fog,uTintColor,uTexture,uTextureOffset
        // minosoft:scene_bridge SKELETAL SKELETAL_TINTED uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uTintColor,uOutlineColor
        // minosoft:scene_bridge SKELETAL SKELETAL_LIGHTMAP uTextures,uViewProjectionMatrix,uCameraPosition,fog,uSkeletalBuffer,uLight,uLightMapBuffer,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius
        // minosoft:scene_bridge ARM_SKELETAL ARM uTextures,uTexture,uTintColor,uSkinParts,uTransform
        out vec4 color;
        out vec2 coord0;
        out vec2 coord1;
        out vec2 uv;
        out vec4 lmtexcoord;
        out vec4 vtexcoord;
        out vec4 vtexcoordam;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out vec3 averageSkyCol_Clouds;
        flat out vec4 lightCol;
        flat out vec3 WsunVec;
        flat out vec4 dailyWeatherParams0;
        flat out vec4 dailyWeatherParams1;
        out float VanillaAO;
        out vec4 normalMat;
        out vec4 tangent;
        out vec3 FlatNormals;
        flat out float blockID;
        flat out int NameTags;
        flat out float SSSAMOUNT;
        flat out float EMISSIVE;
        flat out int LIGHTNING;
        flat out int PORTAL;
        flat out int SIGN;
        out vec3 position_view;
        out vec3 position_scene;
        out vec4 tint;
        out vec2 light_levels;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out uint minosoftTextureArray;
        out float minosoftTextureLayer;
        flat out uint minosoftLightIndex;
        out float minosoftFogFragCoord;
        void minosoftFinishLegacyTexturedVaryings() {
            lmtexcoord = vec4(coord0, coord1);
            vtexcoord = vec4(coord0, 0.0, 0.0);
            vtexcoordam = vec4(0.0, 0.0, 1.0, 1.0);
            exposure = 0.0;
            HELD_ITEM_BRIGHTNESS = 0.0;
            averageSkyCol_Clouds = vec3(0.0);
            lightCol = vec4(0.0);
            WsunVec = vec3(0.0);
            dailyWeatherParams0 = vec4(0.0);
            dailyWeatherParams1 = vec4(0.0);
            VanillaAO = 1.0;
            normalMat = vec4(0.0, 1.0, 0.0, 1.0);
            tangent = vec4(1.0, 0.0, 0.0, 1.0);
            FlatNormals = normalMat.xyz;
            blockID = 0.0;
            NameTags = 0;
            SSSAMOUNT = 0.0;
            EMISSIVE = 0.0;
            LIGHTNING = 0;
            PORTAL = 0;
            SIGN = 0;
        }

        #if defined(MINOSOFT_STATE_ABI_GENERIC_TEXTURE)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTexture;
        layout (location = 3) in float vinTintColor;
        layout (location = 4) in vec3 vinNormal;
        layout (location = 5) in vec4 vinTangent;
        uniform mat4 uViewProjectionMatrix;
        uniform vec3 cameraPosition;
        void main() {
            uint packedTexture = floatBitsToUint(vinTexture);
            uint packedColor = floatBitsToUint(vinTintColor);
            minosoftTextureArray = packedTexture >> 28u;
            minosoftTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftLightIndex = 255u;
            gl_Position = uViewProjectionMatrix * vec4(vinPosition, 1.0);
            color = vec4(
                float((packedColor >> 16u) & 0xFFu),
                float((packedColor >> 8u) & 0xFFu),
                float(packedColor & 0xFFu),
                255.0
            ) / 255.0;
            coord0 = minosoftMaterialLogicalUv(vinUV, minosoftTextureArray);
            coord1 = vec2(1.0);
            vec3 minosoftNormal = normalize(vinNormal);
            vec3 minosoftTangent = normalize(vinTangent.xyz);
            vec3 minosoftBinormal =
                normalize(cross(minosoftNormal, minosoftTangent)) * vinTangent.w;
            tbn = mat3(minosoftTangent, minosoftBinormal, minosoftNormal);
            minosoftFogFragCoord = length(vinPosition - cameraPosition);
            minosoftFinishLegacyTexturedVaryings();
        }
        #elif defined(MINOSOFT_STATE_ABI_WORLD_BORDER)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinUVIndex;
        layout (location = 2) in float vinWidth;
        uniform mat4 uViewProjectionMatrix;
        uniform vec3 uCameraPosition;
        uniform vec4 uTintColor;
        uniform uint uTexture;
        uniform float uTextureOffset;
        const vec2 minosoftBorderUv[4] = vec2[4](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 0.0),
            vec2(1.0, 1.0)
        );
        void main() {
            vec3 position = vinPosition;
            if (position.y < 0.0) {
                position.y = uCameraPosition.y - 150.0;
            } else if (position.y > 0.0) {
                position.y = uCameraPosition.y + 150.0;
            }
            vec2 minosoftUv = minosoftBorderUv[floatBitsToUint(vinUVIndex)];
            minosoftUv.x *= vinWidth;
            minosoftUv.y *= 150.0;
            minosoftUv += vec2(uTextureOffset);
            minosoftTextureArray = uTexture >> 28u;
            minosoftTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
            minosoftLightIndex = 255u;
            gl_Position = uViewProjectionMatrix * vec4(position, 1.0);
            color = uTintColor;
            coord0 = minosoftMaterialLogicalUv(minosoftUv, minosoftTextureArray);
            coord1 = vec2(1.0);
            minosoftFogFragCoord = length(position - uCameraPosition);
            minosoftFinishLegacyTexturedVaryings();
        }
        #elif defined(MINOSOFT_STATE_ABI_ARM)
        #define POSITIVE_INFINITY (1.0 / 0.0)
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinPartTransformNormal;
        uniform uint uTexture;
        uniform vec4 uTintColor;
        uniform uint uSkinParts;
        uniform mat4 uTransform;
        #include "minosoft:skeletal/shade"
        void main() {
            uint part = floatBitsToUint(vinPartTransformNormal);
            uint skinPart = (part >> 19u) & 0xFFu;
            if (skinPart > 0u && ((1u << (skinPart - 1u)) & uSkinParts) == 0u) {
                gl_Position = vec4(POSITIVE_INFINITY);
                color = vec4(0.0);
                return;
            }
            vec4 position = uTransform * vec4(vinPosition, 1.0);
            vec3 normal = transformNormal(decodeNormal(part & 0xFFFu), uTransform);
            minosoftTextureArray = uTexture >> 28u;
            minosoftTextureLayer = float((uTexture >> 12u) & 0xFFFFu);
            minosoftLightIndex = 255u;
            gl_Position = position;
            color = vec4(vec3(getShade(normal)), 1.0) * uTintColor;
            coord0 = minosoftMaterialLogicalUv(vinUV, minosoftTextureArray);
            coord1 = vec2(1.0);
            minosoftFogFragCoord = 0.0;
            minosoftFinishLegacyTexturedVaryings();
        }
        #else
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in vec2 vinUV;
        layout (location = 2) in float vinTransformNormal;
        layout (location = 3) in float vinTexture;
        out vec3 finFragmentPosition;
        uniform vec3 uCameraPosition;
        uniform vec4 uTintColor;
        uniform vec4 uOutlineColor;
        #if defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
        uniform uint uLight;
        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftLightMap[256];
        };
        vec4 getLight(uint light) {
            return minosoftLightMap[light];
        }
        #include "minosoft:player_light"
        #endif
        #include "minosoft:skeletal/vertex"
        void main() {
            run_skeletal(floatBitsToUint(vinTransformNormal), vinPosition);
            uint packedTexture = floatBitsToUint(vinTexture);
            minosoftTextureArray = packedTexture >> 28u;
            minosoftTextureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftLightIndex = 255u;
            #if defined(MINOSOFT_STATE_ABI_SKELETAL_LIGHTMAP)
            color = finTintColor * getLight(uLight & 0xFFu);
            color.rgb = max(color.rgb, vec3(playerLightContribution(finFragmentPosition)));
            #else
            color = finTintColor * uTintColor;
            if (uOutlineColor.a > 0.0) color = uOutlineColor;
            #endif
            coord0 = minosoftMaterialLogicalUv(vinUV, minosoftTextureArray);
            coord1 = vec2(1.0);
            minosoftFogFragCoord = length(finFragmentPosition - uCameraPosition);
            minosoftFinishLegacyTexturedVaryings();
        }
        #endif
    """.trimIndent()

    private val PARTICLE_VERTEX_BODY = """
        #version 330 core
        // minosoft:scene_bridge PARTICLE_POINT PARTICLE uTextures,uLightMapBuffer,uViewProjectionMatrix,fog,uCameraPosition,uPlayerLightPosition,uPlayerLightIntensity,uPlayerLightRadius,uCameraRight,uCameraUp
        layout (location = 0) in vec3 vinPosition;
        layout (location = 1) in float vinMinUV;
        layout (location = 2) in float vinMaxUV;
        layout (location = 3) in float vinTexture;
        layout (location = 4) in float vinScale;
        layout (location = 5) in float vinTintColor;
        layout (location = 6) in float vinLight;

        layout(std140) uniform uLightMapBuffer {
            vec4 minosoftLightMap[256];
        };
        uniform vec3 uPlayerLightPosition;
        uniform float uPlayerLightIntensity;
        uniform float uPlayerLightRadius;

        out ParticleVertex {
            vec2 minUV;
            vec2 maxUV;
            flat uint textureArray;
            flat float textureLayer;
            float scale;
            vec4 color;
        } minosoftParticle;

        vec2 minosoftUnpackParticleUv(uint packed) {
            return vec2(float((packed >> 12u) & 0xFFFu), float(packed & 0xFFFu)) / 4095.0;
        }
        vec4 minosoftUnpackParticleColor(uint packed) {
            return vec4(
                float((packed >> 24u) & 0xFFu),
                float((packed >> 16u) & 0xFFu),
                float((packed >> 8u) & 0xFFu),
                float(packed & 0xFFu)
            ) / 255.0;
        }
        float minosoftPlayerLight(vec3 position) {
            float ratio = clamp(
                distance(position, uPlayerLightPosition) / max(uPlayerLightRadius, 0.001),
                0.0,
                1.0
            );
            float falloff = 1.0 - ratio;
            return uPlayerLightIntensity * falloff * falloff;
        }
        void main() {
            uint packedTexture = floatBitsToUint(vinTexture);
            vec4 light = minosoftLightMap[floatBitsToUint(vinLight) & 0xFFu];
            light.rgb = max(light.rgb, vec3(minosoftPlayerLight(vinPosition)));
            gl_Position = vec4(vinPosition, 1.0);
            minosoftParticle.minUV = minosoftUnpackParticleUv(floatBitsToUint(vinMinUV));
            minosoftParticle.maxUV = minosoftUnpackParticleUv(floatBitsToUint(vinMaxUV));
            minosoftParticle.textureArray = packedTexture >> 28u;
            minosoftParticle.textureLayer = float((packedTexture >> 12u) & 0xFFFFu);
            minosoftParticle.scale = vinScale;
            minosoftParticle.color = minosoftUnpackParticleColor(floatBitsToUint(vinTintColor)) * light;
        }
    """.trimIndent()

    private val PARTICLE_GEOMETRY_BODY = """
        #version 330 core
        $MATERIAL_LOGICAL_UV_FUNCTIONS
        layout (points) in;
        layout (triangle_strip, max_vertices = 4) out;

        uniform mat4 uViewProjectionMatrix;
        uniform mat4 gbufferModelView;
        uniform vec3 uCameraPosition;
        uniform vec3 uCameraRight;
        uniform vec3 uCameraUp;
        uniform vec3 sunPosition;

        in ParticleVertex {
            vec2 minUV;
            vec2 maxUV;
            flat uint textureArray;
            flat float textureLayer;
            float scale;
            vec4 color;
        } minosoftParticle[];

        out vec4 color;
        out vec2 coord0;
        out vec2 coord1;
        out vec2 uv;
        out vec2 texCoord;
        out vec2 lmCoord;
        out vec4 lmtexcoord;
        flat out float exposure;
        flat out float HELD_ITEM_BRIGHTNESS;
        flat out vec3 averageSkyCol_Clouds;
        flat out vec4 lightCol;
        flat out vec3 WsunVec;
        flat out vec4 dailyWeatherParams0;
        flat out vec4 dailyWeatherParams1;
        out vec3 position_view;
        out vec3 position_scene;
        out vec3 normal;
        out vec4 tint;
        flat out vec4 glColor;
        out vec2 light_levels;
        flat out uint material_mask;
        flat out mat3 tbn;
        flat out vec3 upVec;
        flat out vec3 sunVec;
        flat out vec3 northVec;
        flat out vec3 eastVec;
        flat out uint minosoftTextureArray;
        out float minosoftTextureLayer;
        flat out uint minosoftLightIndex;
        out float minosoftFogFragCoord;

        void minosoftEmitParticle(vec3 offset, vec2 particleUv) {
            vec3 position = gl_in[0].gl_Position.xyz + offset * minosoftParticle[0].scale;
            vec3 particleTangent = normalize(uCameraRight);
            vec3 particleBitangent = normalize(-uCameraUp);
            vec3 particleNormal = normalize(cross(uCameraRight, uCameraUp));
            minosoftTextureArray = minosoftParticle[0].textureArray;
            minosoftTextureLayer = minosoftParticle[0].textureLayer;
            vec2 logicalUv = minosoftMaterialLogicalUv(particleUv, minosoftTextureArray);
            gl_Position = uViewProjectionMatrix * vec4(position, 1.0);
            color = minosoftParticle[0].color;
            coord0 = logicalUv;
            coord1 = vec2(1.0);
            uv = logicalUv;
            texCoord = logicalUv;
            lmCoord = vec2(1.0);
            lmtexcoord = vec4(logicalUv, lmCoord);
            exposure = 0.0;
            HELD_ITEM_BRIGHTNESS = 0.0;
            averageSkyCol_Clouds = vec3(0.0);
            lightCol = vec4(0.0);
            WsunVec = vec3(0.0);
            dailyWeatherParams0 = vec4(0.0);
            dailyWeatherParams1 = vec4(0.0);
            position_scene = position - uCameraPosition;
            position_view = position_scene;
            normal = particleNormal;
            tint = minosoftParticle[0].color;
            glColor = tint;
            light_levels = vec2(1.0);
            material_mask = 0u;
            tbn = mat3(particleTangent, particleBitangent, particleNormal);
            upVec = normalize(gbufferModelView[1].xyz);
            sunVec = normalize(sunPosition);
            northVec = normalize(gbufferModelView[2].xyz);
            eastVec = normalize(gbufferModelView[0].xyz);
            minosoftLightIndex = 255u;
            minosoftFogFragCoord = length(position - uCameraPosition);
            EmitVertex();
        }
        void main() {
            minosoftEmitParticle(
                -(uCameraRight - uCameraUp),
                vec2(minosoftParticle[0].minUV.x, minosoftParticle[0].minUV.y)
            );
            minosoftEmitParticle(
                -(uCameraRight + uCameraUp),
                vec2(minosoftParticle[0].minUV.x, minosoftParticle[0].maxUV.y)
            );
            minosoftEmitParticle(
                uCameraRight + uCameraUp,
                vec2(minosoftParticle[0].maxUV.x, minosoftParticle[0].minUV.y)
            );
            minosoftEmitParticle(
                uCameraRight - uCameraUp,
                vec2(minosoftParticle[0].maxUV.x, minosoftParticle[0].maxUV.y)
            );
            EndPrimitive();
        }
    """.trimIndent()

    private val SCENE_TEXTURE_FRAGMENT_HEADER = """
        layout (location = 0) out vec4 minosoftFragmentColor;
        flat in uint minosoftSceneTextureArray;
        in float minosoftSceneTextureLayer;
        uniform sampler2DArray uTextures[16];

        vec3 minosoftStaticTextureCoordinate(vec3 coordinate) {
            return vec3(coordinate.x, fract(coordinate.y) / 3.0, coordinate.z);
        }
        vec3 minosoftDynamicTextureCoordinate(vec3 coordinate) { return coordinate; }

        vec4 minosoftSampleSceneTexture(vec2 uv) {
            vec3 coordinate = vec3(uv, minosoftSceneTextureLayer);
            switch (minosoftSceneTextureArray) {
                case 0u: return texture(uTextures[0], coordinate);
                case 1u: return texture(uTextures[1], coordinate);
                case 2u: return texture(uTextures[2], coordinate);
                case 3u: return texture(uTextures[3], coordinate);
                case 4u: return texture(uTextures[4], coordinate);
                case 5u: return texture(uTextures[5], coordinate);
                case 6u: return texture(uTextures[6], coordinate);
                case 7u: return texture(uTextures[7], coordinate);
                case 8u: return texture(uTextures[8], coordinate);
                case 9u: return texture(uTextures[9], coordinate);
                case 10u: return texture(uTextures[10], coordinate);
                case 11u: return texture(uTextures[11], coordinate);
                case 12u: return texture(uTextures[12], coordinate);
                case 13u: return texture(uTextures[13], coordinate);
                case 14u: return texture(uTextures[14], coordinate);
                case 15u: return texture(uTextures[15], coordinate);
                default: return vec4(1.0);
            }
        }
    """.trimIndent().markMaterialPhysicalCoordinates()
}
