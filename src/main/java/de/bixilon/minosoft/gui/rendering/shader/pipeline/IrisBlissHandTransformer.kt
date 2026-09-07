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

/** Prevents Bliss temporal history from bleeding world geometry through the hand. */
internal object IrisBlissHandTransformer {
    fun transform(
        name: String,
        phase: ShaderProgramPhase,
        stages: IrisLegacyShaderTransformer.Stages,
    ): IrisLegacyShaderTransformer.Stages {
        if (name != "composite6" || phase != ShaderProgramPhase.COMPOSITE) return stages

        val fragment = stages.fragment.replace("\r\n", "\n").replace('\r', '\n')
        if (
            "vec4 computeTAA(vec2 texcoord, bool hand){" !in fragment ||
            "bool hand = abs(dataUnpacked-0.75) < 0.01" !in fragment ||
            "const bool colortex5Clear = false;" !in fragment
        ) {
            return stages
        }

        val authored =
            "if(hand) blendingFactor = clamp(length(velocity/texelSize),blendingFactor,1.0);"
        val start = fragment.indexOf(authored)
        require(start >= 0) { "Bliss hand TAA history rule changed before rejection correction" }
        require(fragment.indexOf(authored, start + authored.length) < 0) {
            "Bliss hand TAA history rule changed before rejection correction: duplicate source block"
        }
        return stages.copy(
            fragment = fragment.replaceRange(
                start,
                start + authored.length,
                "if(hand) blendingFactor = 1.0; // minosoft: reject world history on the hand",
            ),
        )
    }
}
