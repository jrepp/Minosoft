/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.entities

import kotlin.test.Test
import kotlin.test.assertEquals

class EntityBodyRotationTest {

    @Test
    fun `moving mob follows entity yaw and clamps its head`() {
        val rotation = EntityBodyRotation(initialBodyYaw = 0.0f, initialHeadYaw = 0.0f)

        rotation.tick(
            entityYaw = 90.0f,
            headYaw = 180.0f,
            moving = true,
            independent = true,
            maxHeadRotation = 75.0f,
        )

        assertEquals(90.0f, rotation.currentBodyYaw)
        assertEquals(165.0f, rotation.currentHeadYaw)
        assertEquals(45.0f, rotation.interpolateBody(0.5f))
        assertEquals(82.5f, rotation.interpolateHead(0.5f))
    }

    @Test
    fun `stationary head turn immediately pulls body within its limit`() {
        val rotation = EntityBodyRotation(initialBodyYaw = 0.0f, initialHeadYaw = 0.0f)

        rotation.tick(
            entityYaw = 0.0f,
            headYaw = 100.0f,
            moving = false,
            independent = true,
            maxHeadRotation = 75.0f,
        )

        assertEquals(25.0f, rotation.currentBodyYaw)
        assertEquals(100.0f, rotation.currentHeadYaw)
        assertEquals(12.5f, rotation.interpolateBody(0.5f))
    }

    @Test
    fun `stationary body converges after vanilla delay`() {
        val rotation = EntityBodyRotation(initialBodyYaw = 0.0f, initialHeadYaw = 30.0f)

        repeat(10) {
            rotation.tick(0.0f, 30.0f, moving = false, independent = true, maxHeadRotation = 75.0f)
        }
        assertEquals(0.0f, rotation.currentBodyYaw)

        repeat(10) {
            rotation.tick(0.0f, 30.0f, moving = false, independent = true, maxHeadRotation = 75.0f)
        }
        assertEquals(30.0f, rotation.currentBodyYaw)
    }

    @Test
    fun `rider controlled mob leaves body adjustment unchanged`() {
        val rotation = EntityBodyRotation(initialBodyYaw = 20.0f, initialHeadYaw = 20.0f)

        rotation.tick(
            entityYaw = 90.0f,
            headYaw = 120.0f,
            moving = false,
            independent = false,
            maxHeadRotation = 75.0f,
        )

        assertEquals(20.0f, rotation.currentBodyYaw)
        assertEquals(120.0f, rotation.currentHeadYaw)
    }

    @Test
    fun `angle clamp and interpolation take shortest wraparound path`() {
        assertEquals(-180.0f, EntityBodyRotation.clampAngle(170.0f, -170.0f, 10.0f))

        val rotation = EntityBodyRotation(initialBodyYaw = 170.0f, initialHeadYaw = 170.0f)
        rotation.tick(
            entityYaw = -170.0f,
            headYaw = -170.0f,
            moving = true,
            independent = true,
            maxHeadRotation = 75.0f,
        )

        assertEquals(-180.0f, rotation.interpolateBody(0.5f))
    }
}
