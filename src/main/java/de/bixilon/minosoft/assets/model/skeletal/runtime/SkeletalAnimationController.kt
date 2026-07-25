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
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import kotlin.math.floor

data class SkeletalAnimationControllerSnapshot(
    val current: String?,
    val elapsedSeconds: Float,
    val previousPose: SkeletalPose,
    val transitionElapsedSeconds: Float,
    val transitionDurationSeconds: Float,
    val eventTimelineStarted: Boolean,
    val loopOverride: SkeletalAnimationLoop?,
) {
    init {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0f) {
            "Animation snapshot elapsed time must be finite and non-negative."
        }
        require(transitionElapsedSeconds.isFinite() && transitionElapsedSeconds >= 0.0f) {
            "Animation snapshot transition time must be finite and non-negative."
        }
        require(transitionDurationSeconds.isFinite() && transitionDurationSeconds >= 0.0f) {
            "Animation snapshot transition duration must be finite and non-negative."
        }
    }
}

class SkeletalAnimationController(
    private val clips: Map<String, SkeletalAnimationClip>,
    initial: String? = null,
) {
    var current: String? = null
        private set
    val elapsedSeconds get() = elapsed
    val finished: Boolean
        get() {
            val clip = currentClip() ?: return true
            return clip.loop == SkeletalAnimationLoop.ONCE && elapsed >= clip.lengthSeconds
        }
    val remainingSeconds: Float?
        get() {
            val clip = currentClip() ?: return 0.0f
            if (clip.loop != SkeletalAnimationLoop.ONCE) return null
            return (clip.lengthSeconds - elapsed).coerceAtLeast(0.0f)
        }
    private var previousPose = SkeletalPose(emptyMap())
    private var elapsed = 0.0f
    private var transitionElapsed = 0.0f
    private var transitionDuration = 0.0f
    private var eventTimelineStarted = false
    private var loopOverride: SkeletalAnimationLoop? = null

    init {
        initial?.let { play(it) }
    }

    fun play(
        name: String,
        transitionSeconds: Float = 0.0f,
        restart: Boolean = false,
        loopOverride: SkeletalAnimationLoop? = null,
    ) {
        require(name in clips) { "Unknown skeletal animation: $name" }
        require(transitionSeconds.isFinite() && transitionSeconds >= 0.0f) {
            "Animation transition must be finite and non-negative."
        }
        if (!restart && current == name && this.loopOverride == loopOverride) return
        previousPose = pose()
        current = name
        this.loopOverride = loopOverride
        elapsed = 0.0f
        transitionElapsed = 0.0f
        transitionDuration = transitionSeconds
        eventTimelineStarted = false
    }

    fun stop() {
        previousPose = pose()
        current = null
        loopOverride = null
        elapsed = 0.0f
        transitionElapsed = 0.0f
        transitionDuration = 0.0f
        eventTimelineStarted = false
    }

    fun snapshot() = SkeletalAnimationControllerSnapshot(
        current = current,
        elapsedSeconds = elapsed,
        previousPose = SkeletalPose(previousPose.bones.toMap()),
        transitionElapsedSeconds = transitionElapsed,
        transitionDurationSeconds = transitionDuration,
        eventTimelineStarted = eventTimelineStarted,
        loopOverride = loopOverride,
    )

    /**
     * Restores a compatible retained timeline without replaying already-fired
     * keyframes. Returns false when the replacement clip set no longer
     * contains the active animation.
     */
    fun restore(snapshot: SkeletalAnimationControllerSnapshot): Boolean {
        if (snapshot.current != null && snapshot.current !in clips) return false
        current = snapshot.current
        elapsed = snapshot.elapsedSeconds
        previousPose = SkeletalPose(snapshot.previousPose.bones.toMap())
        transitionElapsed = snapshot.transitionElapsedSeconds
        transitionDuration = snapshot.transitionDurationSeconds
        eventTimelineStarted = snapshot.eventTimelineStarted
        loopOverride = snapshot.loopOverride
        return true
    }

    fun update(
        deltaSeconds: Float,
        context: SkeletalExpressionContext = SkeletalExpressionContext(),
        eventConsumer: ((SkeletalAnimationEvent) -> Unit)? = null,
        easingOverride: String? = null,
        easingResolver: SkeletalEasingResolver? = null,
    ): SkeletalPose {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0.0f) {
            "Animation delta must be finite and non-negative."
        }
        val clip = currentClip()
        val previousElapsed = elapsed
        elapsed += deltaSeconds
        transitionElapsed += deltaSeconds
        if (clip != null && eventConsumer != null) {
            eventsBetween(clip, previousElapsed, elapsed, !eventTimelineStarted).forEach(eventConsumer)
        }
        if (clip != null) eventTimelineStarted = true
        return pose(context, easingOverride, easingResolver)
    }

    fun pose(
        context: SkeletalExpressionContext = SkeletalExpressionContext(),
        easingOverride: String? = null,
        easingResolver: SkeletalEasingResolver? = null,
    ): SkeletalPose {
        val clip = currentClip() ?: return SkeletalPose(emptyMap())
        val target = SkeletalAnimationEvaluator.evaluate(clip, elapsed, context, easingOverride, easingResolver)
        if (transitionDuration <= 0.0f || transitionElapsed >= transitionDuration) return target
        return previousPose.blend(target, transitionElapsed / transitionDuration)
    }

    private fun currentClip(): SkeletalAnimationClip? {
        val clip = current?.let(clips::get) ?: return null
        val loop = loopOverride ?: return clip
        return if (clip.loop == loop) clip else clip.copy(loop = loop)
    }

    private fun eventsBetween(
        clip: SkeletalAnimationClip,
        start: Float,
        end: Float,
        includeStart: Boolean,
    ): List<SkeletalAnimationEvent> {
        if (clip.events.isEmpty()) return emptyList()
        if (clip.loop != SkeletalAnimationLoop.LOOP || clip.lengthSeconds <= 0.0f) {
            val boundedEnd = end.coerceAtMost(clip.lengthSeconds)
            return clip.events.filter {
                (it.timeSeconds > start || includeStart && it.timeSeconds == start) &&
                    it.timeSeconds <= boundedEnd
            }
        }

        val length = clip.lengthSeconds.toDouble()
        val firstCycle = floor(start.toDouble() / length).toLong()
        val lastCycle = floor(end.toDouble() / length).toLong()
        val cycles = lastCycle - firstCycle + 1L
        require(cycles > 0L && cycles <= MAX_EVENT_DISPATCH / clip.events.size.coerceAtLeast(1)) {
            "Animation update would dispatch too many keyframe events."
        }
        val result = ArrayList<SkeletalAnimationEvent>()
        for (cycle in firstCycle..lastCycle) {
            val cycleStart = cycle * length
            for (event in clip.events) {
                val absolute = cycleStart + event.timeSeconds
                if ((absolute > start || includeStart && absolute == start.toDouble()) && absolute <= end.toDouble()) {
                    result += event
                    require(result.size <= MAX_EVENT_DISPATCH) {
                        "Animation update exceeds the $MAX_EVENT_DISPATCH keyframe event limit."
                    }
                }
            }
        }
        return result
    }

    private companion object {
        const val MAX_EVENT_DISPATCH = 16_384
    }
}
