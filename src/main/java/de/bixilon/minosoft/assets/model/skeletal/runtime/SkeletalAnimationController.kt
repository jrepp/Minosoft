/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext

class SkeletalAnimationController(
    private val clips: Map<String, SkeletalAnimationClip>,
    initial: String? = null,
) {
    var current: String? = null
        private set
    private var previousPose = SkeletalPose(emptyMap())
    private var elapsed = 0.0f
    private var transitionElapsed = 0.0f
    private var transitionDuration = 0.0f

    init {
        initial?.let { play(it) }
    }

    fun play(name: String, transitionSeconds: Float = 0.0f, restart: Boolean = false) {
        require(name in clips) { "Unknown skeletal animation: $name" }
        require(transitionSeconds >= 0.0f) { "Animation transition must not be negative." }
        if (!restart && current == name) return
        previousPose = pose()
        current = name
        elapsed = 0.0f
        transitionElapsed = 0.0f
        transitionDuration = transitionSeconds
    }

    fun update(deltaSeconds: Float, context: SkeletalExpressionContext = SkeletalExpressionContext()): SkeletalPose {
        require(deltaSeconds >= 0.0f) { "Animation delta must not be negative." }
        elapsed += deltaSeconds
        transitionElapsed += deltaSeconds
        return pose(context)
    }

    fun pose(context: SkeletalExpressionContext = SkeletalExpressionContext()): SkeletalPose {
        val clip = current?.let(clips::get) ?: return SkeletalPose(emptyMap())
        val target = SkeletalAnimationEvaluator.evaluate(clip, elapsed, context)
        if (transitionDuration <= 0.0f || transitionElapsed >= transitionDuration) return target
        return previousPose.blend(target, transitionElapsed / transitionDuration)
    }
}
