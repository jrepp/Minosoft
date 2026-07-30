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
 * Corrects two Complementary water-lighting assumptions at the resolved-source
 * boundary while retaining the pack's authored dark-water response. Each
 * correction is anchored to the exact source around it so a changed pack
 * revision fails instead of receiving a partial rewrite.
 */
internal object IrisComplementaryWaterTransformer {
    fun transform(
        name: String,
        phase: ShaderProgramPhase,
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        return when {
            name == "gbuffers_water" && phase == ShaderProgramPhase.TERRAIN ->
                restoreBlockLitWaterNormals(stages)
            name == "composite1" && phase == ShaderProgramPhase.COMPOSITE ->
                preserveLitUnderwaterSurfaces(stages)
            else -> stages
        }
    }

    private fun restoreBlockLitWaterNormals(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        val fragment = normalizedLines(stages.fragment)
        if (
            "Complementary Shaders by EminGT" !in fragment ||
            "Water Normals" !in fragment
        ) {
            return stages
        }
        val authoredNormalLight = "normalMap.xy *= 0.03 * lmCoordM.y + 0.01;"
        require(authoredNormalLight in fragment) {
            "Complementary water-normal lighting changed before block-light injection"
        }
        return stages.copy(
            fragment = fragment.replace(
                authoredNormalLight,
                """
                    float minosoftWaterSurfaceLight = max(lmCoordM.x, lmCoordM.y);
                    normalMap.xy *= 0.03 * minosoftWaterSurfaceLight + 0.01;
                """.trimIndent(),
            ),
        )
    }

    private fun preserveLitUnderwaterSurfaces(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        val fragment = normalizedLines(stages.fragment)
        if (
            "Complementary Shaders by EminGT" !in fragment ||
            "if (isEyeInWater == 1)" !in fragment
        ) {
            return stages
        }
        val authoredAttenuation = """
            vec3 underwaterMult = vec3(0.80, 0.87, 0.97);
                    color.rgb *= underwaterMult * 0.85;
                    volumetricEffect.rgb *= pow2(underwaterMult * 0.55);
        """.trimIndent()
        require(authoredAttenuation in fragment) {
            "Complementary underwater attenuation changed before lit-surface preservation"
        }
        return stages.copy(
            fragment = fragment.replace(
                authoredAttenuation,
                """
                    vec3 underwaterMult = vec3(0.80, 0.87, 0.97);
                    float minosoftUnderwaterSurfaceLight = smoothstep(
                        0.06,
                        0.30,
                        clamp(GetLuminance(max(color.rgb, vec3(0.0))), 0.0, 1.0)
                    );
                    vec3 minosoftUnderwaterAttenuation = mix(
                        underwaterMult * 0.85,
                        vec3(0.96),
                        minosoftUnderwaterSurfaceLight * 0.65
                    );
                    color.rgb *= minosoftUnderwaterAttenuation;
                    volumetricEffect.rgb *= pow2(underwaterMult * 0.55);
                """.trimIndent(),
            ),
        )
    }

    private fun normalizedLines(source: String): String =
        source.replace("\r\n", "\n").replace('\r', '\n')
}
