/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.entities

import de.bixilon.minosoft.data.entities.EntityRotation.Companion.interpolateYaw
import kotlin.math.abs

/**
 * Tick history for the vanilla mob body-control algorithm.
 *
 * Entity yaw and head yaw are protocol/physics state. Body yaw is derived
 * client-side and therefore needs its own previous/current history so models
 * and attached render features observe one partial-tick body pose.
 */
class EntityBodyRotation(
    initialBodyYaw: Float,
    initialHeadYaw: Float,
) {
    private var bodyAdjustTicks = 0
    private var lastHeadYaw = initialHeadYaw

    var previousBodyYaw = initialBodyYaw
        private set
    var currentBodyYaw = initialBodyYaw
        private set
    var previousHeadYaw = initialHeadYaw
        private set
    var currentHeadYaw = initialHeadYaw
        private set

    fun tick(
        entityYaw: Float,
        headYaw: Float,
        moving: Boolean,
        independent: Boolean,
        maxHeadRotation: Float,
    ) {
        previousBodyYaw = currentBodyYaw
        previousHeadYaw = currentHeadYaw
        currentHeadYaw = headYaw

        if (moving) {
            currentBodyYaw = entityYaw
            currentHeadYaw = clampAngle(currentHeadYaw, currentBodyYaw, maxHeadRotation)
            lastHeadYaw = currentHeadYaw
            bodyAdjustTicks = 0
            return
        }
        if (!independent) return

        if (abs(currentHeadYaw - lastHeadYaw) > HEAD_TURN_THRESHOLD) {
            bodyAdjustTicks = 0
            lastHeadYaw = currentHeadYaw
            currentBodyYaw = clampAngle(currentBodyYaw, currentHeadYaw, maxHeadRotation)
            return
        }

        bodyAdjustTicks++
        if (bodyAdjustTicks <= BODY_ADJUST_DELAY) return

        val progress = ((bodyAdjustTicks - BODY_ADJUST_DELAY).toFloat() / BODY_ADJUST_DURATION).coerceIn(0.0f, 1.0f)
        val limit = maxHeadRotation * (1.0f - progress)
        currentBodyYaw = clampAngle(currentBodyYaw, currentHeadYaw, limit)
    }

    fun interpolateBody(delta: Float): Float = interpolateYaw(delta, previousBodyYaw, currentBodyYaw)

    fun interpolateHead(delta: Float): Float = interpolateYaw(delta, previousHeadYaw, currentHeadYaw)

    companion object {
        private const val HEAD_TURN_THRESHOLD = 15.0f
        private const val BODY_ADJUST_DELAY = 10
        private const val BODY_ADJUST_DURATION = 10.0f

        fun clampAngle(value: Float, mean: Float, maxDeviation: Float): Float {
            val delta = wrapDegrees(mean - value).coerceIn(-maxDeviation, maxDeviation)
            return mean - delta
        }

        fun wrapDegrees(value: Float): Float {
            var wrapped = value % EntityRotation.CIRCLE_DEGREE
            if (wrapped >= EntityRotation.HALF_CIRCLE_DEGREE) wrapped -= EntityRotation.CIRCLE_DEGREE
            if (wrapped < -EntityRotation.HALF_CIRCLE_DEGREE) wrapped += EntityRotation.CIRCLE_DEGREE
            return wrapped
        }
    }
}
