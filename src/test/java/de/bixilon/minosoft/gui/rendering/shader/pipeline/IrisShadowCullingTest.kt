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

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrisShadowCullingTest {
    private val projection = CameraUtil.perspective(
        fovY = (Math.PI / 2.0).toFloat(),
        aspect = 1.0f,
        near = 0.1f,
        far = 100.0f,
    )

    private fun create(
        directives: IrisShadowDirectives,
        light: Vec3f = Vec3f(1.0f, 0.0f, 0.0f),
    ) = IrisShadowCullingSet.create(
        directives = directives,
        playerView = Mat4f(),
        playerProjection = projection,
        shadowLightDirection = light,
        camera = Vec3d.EMPTY,
        coordinateOrigin = Vec3d.EMPTY,
        effectiveRenderDistanceBlocks = 64.0,
    )

    @Test
    fun `distance culling preserves pinned non-culling and inclusive box decisions`() {
        val bounded = create(
            IrisShadowDirectives(
                distance = 10.0f,
                distanceRenderMultiplier = 1.0f,
                cullingMode = IrisShadowCullingMode.DISTANCE,
            ),
        )
        assertEquals(IrisShadowCullingKind.DISTANCE, bounded.terrain.kind)
        assertTrue(bounded.terrain.allowsAabb(10.0, -1.0, -1.0, 11.0, 1.0, 1.0))
        assertFalse(bounded.terrain.allowsAabb(10.01, -1.0, -1.0, 11.0, 1.0, 1.0))

        val nonPositive = create(
            IrisShadowDirectives(
                distanceRenderMultiplier = 0.0f,
                cullingMode = IrisShadowCullingMode.DISTANCE,
            ),
        )
        assertEquals(IrisShadowCullingKind.UNCULLED, nonPositive.terrain.kind)

        val beyondHostDistance = create(
            IrisShadowDirectives(
                distance = 65.0f,
                distanceRenderMultiplier = 1.0f,
                cullingMode = IrisShadowCullingMode.DISTANCE,
            ),
        )
        assertEquals(IrisShadowCullingKind.UNCULLED, beyondHostDistance.terrain.kind)
    }

    @Test
    fun `default selects distance for voxel geometry and advanced otherwise`() {
        val voxel = create(
            IrisShadowDirectives(
                distance = 16.0f,
                distanceRenderMultiplier = 1.0f,
                voxelizationDetected = true,
            ),
        )
        assertEquals(IrisShadowCullingKind.DISTANCE, voxel.terrain.kind)

        val ordinary = create(
            IrisShadowDirectives(
                distance = 16.0f,
                distanceRenderMultiplier = 1.0f,
            ),
        )
        assertEquals(IrisShadowCullingKind.ADVANCED, ordinary.terrain.kind)
    }

    @Test
    fun `advanced culling retains view and light-extruded casters`() {
        val culling = create(
            IrisShadowDirectives(
                distance = 64.0f,
                distanceRenderMultiplier = 1.0f,
                cullingMode = IrisShadowCullingMode.ADVANCED,
            ),
        )
        assertEquals(IrisShadowCullingKind.ADVANCED, culling.terrain.kind)
        assertTrue(culling.terrain.allowsAabb(-0.5, -0.5, -5.5, 0.5, 0.5, -4.5))
        assertTrue(culling.terrain.allowsAabb(9.5, -0.5, -5.5, 10.5, 0.5, -4.5))
        assertFalse(culling.terrain.allowsAabb(-10.5, -0.5, -5.5, -9.5, 0.5, -4.5))
        assertFalse(culling.terrain.allowsAabb(-0.5, 19.5, -5.5, 0.5, 20.5, -4.5))

        val zeroDistance = create(
            IrisShadowDirectives(
                distanceRenderMultiplier = 0.0f,
                cullingMode = IrisShadowCullingMode.ADVANCED,
            ),
        )
        assertEquals(IrisShadowCullingKind.CULL_EVERYTHING, zeroDistance.terrain.kind)
    }

    @Test
    fun `reversed culling keeps its voxel core and rejects beyond shadow distance`() {
        val culling = create(
            IrisShadowDirectives(
                distance = 20.0f,
                voxelDistance = 3.0f,
                distanceRenderMultiplier = -1.0f,
                cullingMode = IrisShadowCullingMode.REVERSED,
            ),
        )
        assertEquals(IrisShadowCullingKind.REVERSED, culling.terrain.kind)
        // Behind the player view, but inside the reversed voxel core.
        assertTrue(culling.terrain.allowsAabb(-0.5, -0.5, 0.5, 0.5, 0.5, 1.5))
        assertFalse(culling.terrain.allowsAabb(20.01, -0.5, -1.0, 21.0, 0.5, 0.0))
    }

    @Test
    fun `entity and block entity multipliers remain independent`() {
        val culling = create(
            IrisShadowDirectives(
                distance = 16.0f,
                distanceRenderMultiplier = 1.0f,
                entityShadowDistanceMultiplier = 0.5f,
                cullingMode = IrisShadowCullingMode.DISTANCE,
            ),
        )
        assertTrue(culling.hasDistinctEntityVolume)
        assertTrue(culling.terrain.allowsAabb(9.0, -0.5, -0.5, 10.0, 0.5, 0.5))
        assertFalse(culling.allowsEntityBounds(9.0, -0.5, -0.5, 10.0, 0.5, 0.5))
        assertTrue(culling.allowsBlockEntityBounds(7, 0, 0))
        assertFalse(culling.allowsBlockEntityBounds(10, 0, 0))
    }
}
