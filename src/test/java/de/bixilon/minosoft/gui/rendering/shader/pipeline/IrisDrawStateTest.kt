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
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import kotlin.test.Test
import kotlin.test.assertEquals

class IrisDrawStateTest {
    @Test
    fun `entity overlay matches pinned Iris overlay texture values`() {
        assertEquals(Vec4f.EMPTY, IrisEntityOverlay.resolve(hurtOrDying = false))
        assertEquals(Vec4f(1.0f, 0.0f, 0.0f, 77.0f / 255.0f), IrisEntityOverlay.resolve(hurtOrDying = true))
        assertEquals(
            Vec4f(1.0f, 1.0f, 1.0f, 192.0f / 255.0f),
            IrisEntityOverlay.resolve(hurtOrDying = false, whiteOverlayProgress = 1.0f),
        )
        assertEquals(
            IrisEntityOverlay.HURT,
            IrisEntityOverlay.resolve(hurtOrDying = true, whiteOverlayProgress = 1.0f),
        )
    }

    @Test
    fun `nested item scope retains entity identity and overrides overlay`() {
        val entity = ResourceLocation.of("naturalist:boar")
        val item = ResourceLocation.of("minecraft:iron_chestplate")
        val parent = IrisDrawState(entity = entity, entityColor = Vec4f(0.2f, 0.1f, 0.0f, 0.4f))

        val nested = IrisDrawState(
            item = item,
            entityColor = Vec4f(0.8f, 0.7f, 0.6f, 0.5f),
        ).over(parent)

        assertEquals(entity, nested.entity)
        assertEquals(item, nested.item)
        assertEquals(Vec4f(0.8f, 0.7f, 0.6f, 0.5f), nested.entityColor)
    }

    @Test
    fun `empty nested scope retains complete parent state`() {
        val parent = IrisDrawState(
            entity = ResourceLocation.of("minecraft:sheep"),
            item = ResourceLocation.of("minecraft:shears"),
            entityColor = Vec4f(1.0f, 0.0f, 0.0f, 0.3f),
        )

        assertEquals(parent, IrisDrawState.EMPTY.over(parent))
    }

    @Test
    fun `blend function uses exact OpenGL enum ABI and zeros while disabled`() {
        val state = BlendFunctionState(
            BlendingFunctions.SOURCE_ALPHA,
            BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
            BlendingFunctions.ONE,
            BlendingFunctions.ONE_MINUS_DESTINATION_ALPHA,
        )

        assertEquals(IrisBlendFunction(0x0302, 0x0303, 1, 0x0305), IrisResolvedDrawState.blend(true, state))
        assertEquals(IrisBlendFunction.EMPTY, IrisResolvedDrawState.blend(false, state))
    }
}
