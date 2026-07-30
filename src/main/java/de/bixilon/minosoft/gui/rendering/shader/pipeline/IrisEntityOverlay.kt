/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.entities.entities.LivingEntity

/**
 * CPU representation of the color produced by Minecraft's 16x16 entity
 * overlay texture and Iris's EntityPatcher.
 *
 * Minosoft batches one entity feature at a time, so the overlay texel is
 * constant for the complete draw. Keeping the resolved value in [IrisDrawState]
 * preserves the observable Iris value without adding a redundant UV1 vertex
 * attribute to every host mesh ABI.
 */
object IrisEntityOverlay {
    private const val OVERLAY_STEPS = 15
    private const val WHITE_ALPHA_SCALE = 0.75f
    private const val CHANNEL_MAX = 255

    val HURT = Vec4f(1.0f, 0.0f, 0.0f, 77.0f / CHANNEL_MAX)

    fun resolve(entity: LivingEntity): Vec4f =
        resolve(
            entity.hurtTime > 0 || entity.deathTime > 0,
            entity.whiteOverlayProgress(entity.renderInfo.partialTick),
        )

    fun resolve(hurtOrDying: Boolean, whiteOverlayProgress: Float = 0.0f): Vec4f {
        if (hurtOrDying) return HURT

        val index = (whiteOverlayProgress.coerceIn(0.0f, 1.0f) * OVERLAY_STEPS).toInt()
        val storedAlpha = ((1.0f - index.toFloat() / OVERLAY_STEPS * WHITE_ALPHA_SCALE) * CHANNEL_MAX).toInt()
        val overlayAlpha = 1.0f - storedAlpha.toFloat() / CHANNEL_MAX
        if (overlayAlpha == 0.0f) return Vec4f.EMPTY
        return Vec4f(1.0f, 1.0f, 1.0f, overlayAlpha)
    }
}
