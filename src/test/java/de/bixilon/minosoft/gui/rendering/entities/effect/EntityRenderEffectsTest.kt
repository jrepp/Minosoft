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
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityRenderEffectsTest {

    @Test
    fun `resolves exact EMF shadow and leash outputs`() {
        val effects = EntityRenderEffects(nativeShadowSize = 2.0f)
        val owner = Any()
        effects.publish(
            owner,
            mapOf(
                CemRenderProperty.SHADOW_SIZE to 3.0f,
                CemRenderProperty.SHADOW_OPACITY to 0.8f,
                CemRenderProperty.SHADOW_OFFSET_X to 1.5f,
                CemRenderProperty.SHADOW_OFFSET_Z to -2.0f,
                CemRenderProperty.LEASH_OFFSET_X to 0.25f,
                CemRenderProperty.LEASH_OFFSET_Y to 0.5f,
                CemRenderProperty.LEASH_OFFSET_Z to 0.75f,
            ),
        )

        val snapshot = effects.snapshot(distanceSquared = 64.0)

        assertEquals(6.0f, snapshot.shadowSize)
        assertEquals(0.6f, snapshot.shadowOpacity)
        assertEquals(Vec2f(1.5f, -2.0f), snapshot.shadowOffset)
        assertEquals(Vec3f(0.25f, 0.5f, 0.75f), snapshot.leashOffset)
    }

    @Test
    fun `replacement owner prevents retired model from clearing live outputs`() {
        val effects = EntityRenderEffects(nativeShadowSize = 4.0f)
        val retired = Any()
        val replacement = Any()
        effects.publish(retired, mapOf(CemRenderProperty.SHADOW_SIZE to 0.25f))
        effects.publish(replacement, mapOf(CemRenderProperty.SHADOW_SIZE to 0.75f))

        effects.clear(retired)
        assertEquals(3.0f, effects.snapshot(0.0).shadowSize)

        effects.clear(replacement)
        assertEquals(4.0f, effects.snapshot(0.0).shadowSize)
        assertEquals(0.5f, effects.snapshot(0.0).shadowOpacity)
    }

    @Test
    fun `shadow size and opacity stay inside native renderer bounds`() {
        val effects = EntityRenderEffects(nativeShadowSize = 4.0f)
        val owner = Any()
        effects.publish(
            owner,
            mapOf(
                CemRenderProperty.SHADOW_SIZE to 100.0f,
                CemRenderProperty.SHADOW_OPACITY to 2.0f,
            ),
        )

        assertEquals(32.0f, effects.snapshot(0.0).shadowSize)
        assertEquals(1.0f, effects.snapshot(0.0).shadowOpacity)
        assertEquals(0.0f, effects.snapshot(256.0).shadowOpacity)
    }

    @Test
    fun `display shadow publishes absolute radius and retains owner safety`() {
        val effects = EntityRenderEffects(nativeShadowSize = 0.5f)
        val display = Any()
        val retired = Any()

        effects.publishShadow(display, radius = 3.0f, strength = 0.8f)
        effects.clear(retired)
        assertEquals(3.0f, effects.snapshot(0.0).shadowSize)
        assertEquals(0.8f, effects.snapshot(0.0).shadowOpacity)

        effects.clear(display)
        assertEquals(0.5f, effects.snapshot(0.0).shadowSize)
        assertEquals(0.5f, effects.snapshot(0.0).shadowOpacity)
    }
}
