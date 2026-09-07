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

class IrisBlissHandTransformerTest {
    @Test
    fun `Bliss hand pixels reject temporal world history`() {
        val transformed = IrisBlissHandTransformer.transform(
            "composite6",
            ShaderProgramPhase.COMPOSITE,
            stages(blissFragment()),
        )

        assertContains(
            transformed.fragment,
            "if(hand) blendingFactor = 1.0; // minosoft: reject world history on the hand",
        )
    }

    @Test
    fun `changed Bliss hand history rule fails closed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            IrisBlissHandTransformer.transform(
                "composite6",
                ShaderProgramPhase.COMPOSITE,
                stages(blissFragment().replace("clamp(length(velocity/texelSize),blendingFactor,1.0)", "0.5")),
            )
        }

        assertContains(failure.message.orEmpty(), "Bliss hand TAA history rule changed")
    }

    @Test
    fun `unrelated composite is unchanged`() {
        val original = stages("void main() { gl_FragData[0] = vec4(1.0); }")
        assertEquals(
            original,
            IrisBlissHandTransformer.transform(
                "composite6",
                ShaderProgramPhase.COMPOSITE,
                original,
            ),
        )
    }

    private fun blissFragment() = """
        const bool colortex5Clear = false;
        vec4 computeTAA(vec2 texcoord, bool hand){
            float blendingFactor = BLEND_FACTOR;
            if(hand) blendingFactor = clamp(length(velocity/texelSize),blendingFactor,1.0);
            return vec4(blendingFactor);
        }
        void main() {
            bool hand = abs(dataUnpacked-0.75) < 0.01 && texture2D(depthtex1,taauTC).x < 1.0;
        }
    """.trimIndent()

    private fun stages(fragment: String) = IrisLegacyShaderTransformer.Stages(
        vertex = "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }",
        fragment = fragment,
    )
}
