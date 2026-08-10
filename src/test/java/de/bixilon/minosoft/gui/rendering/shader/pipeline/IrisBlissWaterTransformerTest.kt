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
import kotlin.test.assertFailsWith

class IrisBlissWaterTransformerTest {
    @Test
    fun `dark above-water surfaces receive a bounded lift`() {
        val transformed = IrisBlissWaterTransformer.transform(
            "gbuffers_water",
            ShaderProgramPhase.TERRAIN,
            stages(
                """
                    #ifdef WATER_BACKGROUND_SPECULAR
                    #endif
                    void main() {
                        bool isWater = true;
                        if(isEyeInWater == 1 && isWater) BackgroundReflection.rgb = vec3(0.0);
                        gl_FragData[0].rgb = clamp(FinalColor / gl_FragData[0].a*alpha0*(1.0-fresnel) * 0.1		+	Reflections_Final / gl_FragData[0].a * 0.1,0.0,65100.0);
                    }
                """.trimIndent(),
            ),
        )

        assertContains(transformed.fragment, "float minosoftWaterSurfaceScale")
        assertContains(transformed.fragment, "isWater && isEyeInWater == 0 ? 0.30 : 0.1")
        assertContains(transformed.fragment, "isWater && isEyeInWater == 0 ? toLinear(color.rgb) * 0.12")
        assertContains(transformed.fragment, "minosoftWaterSurfaceFloor")
        assertContains(transformed.fragment, "Reflections_Final / gl_FragData[0].a * minosoftWaterSurfaceScale")
    }

    @Test
    fun `underwater fog retains local scene contrast`() {
        val transformed = IrisBlissWaterTransformer.transform(
            "composite4",
            ShaderProgramPhase.COMPOSITE,
            stages(
                """
                    void main() {
                        float dirtAmount = Dirt_Amount + 0.01;
                        color.rgb = mix(fogColor, color.rgb * thresholdAbsorbedColor, fogfade);
                        bloomyFogMult *= 0.4;
                    }
                """.trimIndent(),
            ),
        )

        assertContains(
            transformed.fragment,
            "color.rgb * mix(vec3(1.0), thresholdAbsorbedColor, 0.65)",
        )
    }

    @Test
    fun `changed Bliss source fails closed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            IrisBlissWaterTransformer.transform(
                "composite4",
                ShaderProgramPhase.COMPOSITE,
                stages(
                    """
                        void main() {
                            float dirtAmount = Dirt_Amount + 0.01;
                            color.rgb *= thresholdAbsorbedColor;
                            bloomyFogMult *= 0.4;
                        }
                    """.trimIndent(),
                ),
            )
        }

        assertContains(failure.message.orEmpty(), "Bliss underwater fog changed")
    }

    @Test
    fun `duplicate Bliss source block fails closed`() {
        val attenuation = "color.rgb * thresholdAbsorbedColor"
        val failure = assertFailsWith<IllegalArgumentException> {
            IrisBlissWaterTransformer.transform(
                "composite4",
                ShaderProgramPhase.COMPOSITE,
                stages(
                    """
                        void main() {
                            float dirtAmount = Dirt_Amount + 0.01;
                            vec3 first = $attenuation;
                            vec3 second = $attenuation;
                            bloomyFogMult *= 0.4;
                        }
                    """.trimIndent(),
                ),
            )
        }

        assertContains(failure.message.orEmpty(), "duplicate source block")
    }

    @Test
    fun `unrelated pack source is unchanged`() {
        val original = stages("void main() { gl_FragData[0] = vec4(1.0); }")

        assertEquals(
            original,
            IrisBlissWaterTransformer.transform(
                "composite4",
                ShaderProgramPhase.COMPOSITE,
                original,
            ),
        )
    }

    private fun stages(fragment: String) = IrisLegacyShaderTransformer.Stages(
        vertex = "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
        fragment = fragment,
    )
}
