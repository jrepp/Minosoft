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

class IrisComplementaryWaterTransformerTest {
    @Test
    fun `water normals use the strongest local light channel`() {
        val transformed = IrisComplementaryWaterTransformer.transform(
            "gbuffers_water",
            ShaderProgramPhase.TERRAIN,
            stages(
                """
                    // Complementary Shaders by EminGT
                    // ============================== Step 2: Water Normals ============================== //
                    void main() {
                        vec3 normalMap = vec3(0.0);
                        normalMap.xy *= 0.03 * lmCoordM.y + 0.01;
                    }
                """.trimIndent(),
            ),
        )

        assertContains(
            transformed.fragment,
            "float minosoftWaterSurfaceLight = max(lmCoordM.x, lmCoordM.y)",
        )
        assertContains(
            transformed.fragment,
            "normalMap.xy *= 0.03 * minosoftWaterSurfaceLight + 0.01",
        )
    }

    @Test
    fun `underwater attenuation preserves contrast only on lit surfaces`() {
        val transformed = IrisComplementaryWaterTransformer.transform(
            "composite1",
            ShaderProgramPhase.COMPOSITE,
            stages(
                """
                    // Complementary Shaders by EminGT
                    void main() {
                        if (isEyeInWater == 1) {
                            vec3 underwaterMult = vec3(0.80, 0.87, 0.97);
                            color.rgb *= underwaterMult * 0.85;
                            volumetricEffect.rgb *= pow2(underwaterMult * 0.55);
                        }
                    }
                """.trimIndent(),
            ),
        )

        assertContains(transformed.fragment, "minosoftUnderwaterSurfaceLight")
        assertContains(transformed.fragment, "clamp(GetLuminance(max(color.rgb, vec3(0.0)))")
        assertContains(transformed.fragment, "minosoftUnderwaterSurfaceLight * 0.65")
        assertContains(transformed.fragment, "color.rgb *= minosoftUnderwaterAttenuation")
        assertContains(transformed.fragment, "volumetricEffect.rgb *= pow2(underwaterMult * 0.55)")
    }

    @Test
    fun `unrelated pack source is unchanged`() {
        val original = stages("void main() { gl_FragData[0] = vec4(1.0); }")

        assertEquals(
            original,
            IrisComplementaryWaterTransformer.transform(
                "gbuffers_water",
                ShaderProgramPhase.TERRAIN,
                original,
            ),
        )
    }

    private fun stages(fragment: String) = IrisLegacyShaderTransformer.Stages(
        vertex = "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
        fragment = fragment,
    )
}
