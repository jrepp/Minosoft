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

import de.bixilon.kmath.vec.vec2.i.Vec2i
import kotlin.math.exp

/**
 * Per-pipeline-generation implementation of Iris 1.7.2's SmoothedFloat state.
 *
 * Pack half-lives are expressed in ticks and Iris converts them to seconds
 * using a 0.1 multiplier before applying exponential decay each frame.
 */
internal class IrisFrameSmoothing(
    directives: IrisSmoothingDirectives,
) {
    private val wetness = IrisExponentialSmoother(
        directives.wetnessHalfLife,
        directives.drynessHalfLife,
    )
    private val eyeBlock = IrisExponentialSmoother(
        directives.eyeBrightnessHalfLife,
        directives.eyeBrightnessHalfLife,
    )
    private val eyeSky = IrisExponentialSmoother(
        directives.eyeBrightnessHalfLife,
        directives.eyeBrightnessHalfLife,
    )

    fun apply(state: IrisFrameState): IrisFrameState {
        val deltaSeconds = state.frameTime.coerceAtLeast(0.0f)
        return state.copy(
            eyeBrightnessSmooth = Vec2i(
                eyeBlock.update(state.eyeBrightness.x.toFloat(), deltaSeconds).toInt(),
                eyeSky.update(state.eyeBrightness.y.toFloat(), deltaSeconds).toInt(),
            ),
            wetness = wetness.update(state.rainStrength, deltaSeconds).coerceIn(0.0f, 1.0f),
        )
    }
}

internal class IrisExponentialSmoother(
    private val risingHalfLifeTicks: Float,
    private val fallingHalfLifeTicks: Float,
) {
    private var accumulator: Float? = null

    init {
        require(risingHalfLifeTicks.isFinite() && risingHalfLifeTicks >= 0.0f)
        require(fallingHalfLifeTicks.isFinite() && fallingHalfLifeTicks >= 0.0f)
    }

    fun update(target: Float, deltaSeconds: Float): Float {
        require(target.isFinite()) { "Iris smoothing target must be finite" }
        val previous = accumulator
        if (previous == null) {
            accumulator = target
            return target
        }
        val halfLifeTicks = if (target > previous) risingHalfLifeTicks else fallingHalfLifeTicks
        val next = if (halfLifeTicks == 0.0f) {
            target
        } else {
            val halfLifeSeconds = halfLifeTicks * TICK_TO_SECONDS
            val retained = exp((-LN_2 * deltaSeconds / halfLifeSeconds).toDouble()).toFloat()
            target + (previous - target) * retained
        }
        accumulator = next
        return next
    }

    private companion object {
        const val TICK_TO_SECONDS = 0.1f
        val LN_2 = kotlin.math.ln(2.0).toFloat()
    }
}
