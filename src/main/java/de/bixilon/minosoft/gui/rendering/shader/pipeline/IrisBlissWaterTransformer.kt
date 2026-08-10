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
 * Keeps Bliss water readable without replacing its authored absorption color.
 * The rewrites are tied to exact source blocks so another pack or a changed
 * Bliss revision cannot receive a partial presentation correction.
 */
internal object IrisBlissWaterTransformer {
    fun transform(
        name: String,
        phase: ShaderProgramPhase,
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        return when {
            name == "gbuffers_water" && phase == ShaderProgramPhase.TERRAIN ->
                liftDarkWaterSurfaces(stages)
            name == "composite4" && phase == ShaderProgramPhase.COMPOSITE ->
                preserveUnderwaterContrast(stages)
            else -> stages
        }
    }

    private fun liftDarkWaterSurfaces(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        val fragment = normalizedLines(stages.fragment)
        if (
            "#ifdef WATER_BACKGROUND_SPECULAR" !in fragment ||
            "if(isEyeInWater == 1 && isWater)" !in fragment
        ) {
            return stages
        }
        val authoredOutput = """
            gl_FragData[0].rgb = clamp(FinalColor / gl_FragData[0].a*alpha0*(1.0-fresnel) * 0.1		+	Reflections_Final / gl_FragData[0].a * 0.1,0.0,65100.0);
        """.trimIndent()
        return stages.copy(
            fragment = replaceExactlyOnce(
                fragment,
                authoredOutput,
                """
                    float minosoftWaterSurfaceScale =
                        isWater && isEyeInWater == 0 ? 0.30 : 0.1;
                    vec3 minosoftWaterSurfaceFloor =
                        isWater && isEyeInWater == 0 ? toLinear(color.rgb) * 0.12 : vec3(0.0);
                    gl_FragData[0].rgb = max(
                        clamp(
                            FinalColor / gl_FragData[0].a * alpha0 * (1.0-fresnel) * minosoftWaterSurfaceScale +
                            Reflections_Final / gl_FragData[0].a * minosoftWaterSurfaceScale,
                            0.0,
                            65100.0
                        ),
                        minosoftWaterSurfaceFloor
                    );
                """.trimIndent(),
                "Bliss reflective-water output changed before presentation correction",
            ),
        )
    }

    private fun preserveUnderwaterContrast(
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        val fragment = normalizedLines(stages.fragment)
        if (
            "float dirtAmount = Dirt_Amount + 0.01;" !in fragment ||
            "bloomyFogMult *= 0.4;" !in fragment
        ) {
            return stages
        }
        val authoredAttenuation =
            "color.rgb * thresholdAbsorbedColor"
        return stages.copy(
            fragment = replaceExactlyOnce(
                fragment,
                authoredAttenuation,
                "color.rgb * mix(vec3(1.0), thresholdAbsorbedColor, 0.65)",
                "Bliss underwater fog changed before contrast-preservation correction",
            ),
        )
    }

    private fun replaceExactlyOnce(
        source: String,
        authoredSource: String,
        replacement: String,
        changedMessage: String,
    ): String {
        val start = source.indexOf(authoredSource)
        require(start >= 0) { changedMessage }
        require(source.indexOf(authoredSource, start + authoredSource.length) < 0) {
            "$changedMessage: duplicate source block"
        }
        return source.replaceRange(start, start + authoredSource.length, replacement)
    }

    private fun normalizedLines(source: String): String =
        source.replace("\r\n", "\n").replace('\r', '\n')
}
