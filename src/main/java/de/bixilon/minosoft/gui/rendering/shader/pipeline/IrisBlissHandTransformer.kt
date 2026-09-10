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

        val authoredClassifier =
            "bool hand = abs(dataUnpacked-0.75) < 0.01 && texture2D(depthtex1,taauTC).x < 1.0;"
        val classifierStart = fragment.indexOf(authoredClassifier)
        require(classifierStart >= 0) { "Bliss hand current-depth rule changed before rejection correction" }
        require(fragment.indexOf(authoredClassifier, classifierStart + authoredClassifier.length) < 0) {
            "Bliss hand current-depth rule changed before rejection correction: duplicate source block"
        }
        val currentDepthClassifier =
            "bool hand = abs(dataUnpacked-0.75) < 0.01 && texture2D(depthtex0,taauTC).x < 1.0;" +
                " // minosoft: validate the current hand draw, not pre-hand background depth"
        val correctedClassifier = fragment.replaceRange(
            classifierStart,
            classifierStart + authoredClassifier.length,
            currentDepthClassifier,
        )

        val authoredHistory =
            "if(hand) blendingFactor = clamp(length(velocity/texelSize),blendingFactor,1.0);"
        val historyStart = correctedClassifier.indexOf(authoredHistory)
        require(historyStart >= 0) { "Bliss hand TAA history rule changed before rejection correction" }
        require(correctedClassifier.indexOf(authoredHistory, historyStart + authoredHistory.length) < 0) {
            "Bliss hand TAA history rule changed before rejection correction: duplicate source block"
        }
        return stages.copy(
            fragment = correctedClassifier.replaceRange(
                historyStart,
                historyStart + authoredHistory.length,
                "if(hand) blendingFactor = 1.0; // minosoft: reject world history on the hand",
            ),
        )
    }
}
