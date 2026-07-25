/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.effect

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemRenderProperty
import kotlin.math.min

/**
 * Entity-renderer destination for model-driven effects that live outside the
 * skeletal mesh. The publishing feature is identity-scoped so retirement of an
 * old content model cannot clear outputs already supplied by its replacement.
 */
class EntityRenderEffects(
    private val nativeShadowSize: Float,
) {
    private var owner: Any? = null
    private var outputs: Map<CemRenderProperty, Float> = emptyMap()
    private var directShadow: DirectShadow? = null

    @Synchronized
    fun publish(owner: Any, outputs: Map<CemRenderProperty, Float>) {
        this.owner = owner
        this.outputs = outputs.toMap()
        this.directShadow = null
    }

    /** Publishes vanilla display-entity radius/strength as absolute values. */
    @Synchronized
    fun publishShadow(owner: Any, radius: Float, strength: Float) {
        this.owner = owner
        this.outputs = emptyMap()
        this.directShadow = DirectShadow(
            radius = radius.takeIf(Float::isFinite)?.coerceIn(0.0f, MAX_SHADOW_SIZE) ?: 0.0f,
            strength = strength.takeIf(Float::isFinite)?.coerceIn(0.0f, 1.0f) ?: 0.0f,
        )
    }

    @Synchronized
    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.outputs = emptyMap()
        this.directShadow = null
    }

    @Synchronized
    fun snapshot(distanceSquared: Double): Snapshot {
        val directShadow = directShadow
        val shadowSize = directShadow?.radius ?: run {
            val shadowScale = outputs[CemRenderProperty.SHADOW_SIZE]
            min(nativeShadowSize * (shadowScale ?: 1.0f), MAX_SHADOW_SIZE).coerceAtLeast(0.0f)
        }
        val distanceFade = (1.0 - distanceSquared / SHADOW_FADE_DISTANCE_SQUARED)
            .coerceIn(0.0, 1.0)
            .toFloat()
        val shadowOpacity = (
            directShadow?.strength
                ?: outputs[CemRenderProperty.SHADOW_OPACITY]
                ?: DEFAULT_SHADOW_OPACITY
            ).coerceIn(0.0f, 1.0f) * distanceFade
        return Snapshot(
            shadowSize = shadowSize,
            shadowOpacity = shadowOpacity,
            shadowOffset = Vec2f(
                outputs[CemRenderProperty.SHADOW_OFFSET_X] ?: 0.0f,
                outputs[CemRenderProperty.SHADOW_OFFSET_Z] ?: 0.0f,
            ),
            leashOffset = Vec3f(
                outputs[CemRenderProperty.LEASH_OFFSET_X] ?: 0.0f,
                outputs[CemRenderProperty.LEASH_OFFSET_Y] ?: 0.0f,
                outputs[CemRenderProperty.LEASH_OFFSET_Z] ?: 0.0f,
            ),
        )
    }

    private data class DirectShadow(
        val radius: Float,
        val strength: Float,
    )

    data class Snapshot(
        val shadowSize: Float,
        val shadowOpacity: Float,
        val shadowOffset: Vec2f,
        val leashOffset: Vec3f,
    )

    private companion object {
        const val DEFAULT_SHADOW_OPACITY = 0.5f
        const val MAX_SHADOW_SIZE = 32.0f
        const val SHADOW_FADE_DISTANCE_SQUARED = 256.0
    }
}
