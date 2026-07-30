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
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec3.i.Vec3i
import de.bixilon.kmath.vec.vec4.f.Vec4f
import kotlin.test.Test
import kotlin.test.assertEquals

class IrisFrameSmoothingTest {
    @Test
    fun `pack tick half lives produce exact exponential frame decay`() {
        val smoothing = IrisFrameSmoothing(
            IrisSmoothingDirectives(
                wetnessHalfLife = 10.0f,
                drynessHalfLife = 20.0f,
                eyeBrightnessHalfLife = 10.0f,
            ),
        )
        val dryDark = frame(0.0f, Vec2i(0, 0), frameTime = 0.0f)

        assertEquals(Vec2i(0, 0), smoothing.apply(dryDark).eyeBrightnessSmooth)
        assertEquals(0.0f, smoothing.apply(dryDark).wetness)

        val wetBright = smoothing.apply(frame(1.0f, Vec2i(160, 240), frameTime = 1.0f))
        assertEquals(Vec2i(80, 120), wetBright.eyeBrightnessSmooth)
        assertEquals(0.5f, wetBright.wetness, 1.0e-6f)

        val drying = smoothing.apply(frame(0.0f, Vec2i(160, 240), frameTime = 2.0f))
        assertEquals(0.25f, drying.wetness, 1.0e-6f)
    }

    @Test
    fun `zero half life is an explicit instant transition`() {
        val smoothing = IrisFrameSmoothing(IrisSmoothingDirectives(0.0f, 0.0f, 0.0f))
        smoothing.apply(frame(0.0f, Vec2i.EMPTY, 0.0f))

        val changed = smoothing.apply(frame(1.0f, Vec2i(80, 224), 0.0f))

        assertEquals(1.0f, changed.wetness)
        assertEquals(Vec2i(80, 224), changed.eyeBrightnessSmooth)
    }

    private fun frame(rain: Float, eye: Vec2i, frameTime: Float) = IrisFrameState(
        frameCounter = 0,
        frameTime = frameTime,
        frameTimeCounter = 0.0f,
        viewWidth = 1.0f,
        viewHeight = 1.0f,
        near = 0.05f,
        far = 128.0f,
        modelViewMatrix = Mat4f(),
        modelViewMatrixInverse = Mat4f(),
        previousModelViewMatrix = Mat4f(),
        projectionMatrix = Mat4f(),
        projectionMatrixInverse = Mat4f(),
        previousProjectionMatrix = Mat4f(),
        shadowModelView = Mat4f(),
        shadowModelViewInverse = Mat4f(),
        shadowProjection = Mat4f(),
        shadowProjectionInverse = Mat4f(),
        cameraPosition = Vec3d.EMPTY,
        previousCameraPosition = Vec3d.EMPTY,
        worldTime = 0,
        worldDay = 0,
        currentDate = Vec3i.EMPTY,
        currentTime = Vec3i.EMPTY,
        currentYearTime = Vec2i.EMPTY,
        playerState = IrisPlayerFrameState.EMPTY,
        eyeBrightness = eye,
        skyColor = Vec3f.EMPTY,
        hideGui = false,
        rainStrength = rain,
        thunderStrength = 0.0f,
        eyeAltitude = 0.0f,
        sunAngle = 0.0f,
        moonPhase = 0,
        screenBrightness = 0.0f,
        fogStart = 0.0f,
        fogEnd = 1.0f,
        fogColor = Vec4f.EMPTY,
    )
}
