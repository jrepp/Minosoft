/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.effect

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityLeashProjectorTest {

    @Test
    fun `mob anchor rotates vanilla and EMF local offsets with body yaw`() {
        val anchor = EntityLeashProjector.mobAnchor(
            position = Vec3d(10.0, 20.0, 30.0),
            bodyYaw = 0.0f,
            width = 1.0f,
            standingEyeHeight = 1.5f,
            emfOffset = Vec3f(0.25f, 0.5f, 0.75f),
        )

        assertEquals(10.25, anchor.x, 0.000001)
        assertEquals(22.0, anchor.y, 0.000001)
        assertEquals(31.15, anchor.z, 0.000001)
    }

    @Test
    fun `generic and fence knot holder anchors follow vanilla contracts`() {
        assertEquals(
            Vec3d(1.0, 3.4, 3.0),
            EntityLeashProjector.genericHolderAnchor(Vec3d(1.0, 2.0, 3.0), 2.0f),
        )
        assertEquals(
            Vec3d(1.0, 2.2, 3.0),
            EntityLeashProjector.knotHolderAnchor(Vec3d(1.0, 2.0, 3.0)),
        )
    }

    @Test
    fun `normal player holder anchor uses main hand body yaw and pose height`() {
        val anchor = EntityLeashProjector.playerHolderAnchor(
            position = Vec3d(0.0, 0.0, 0.0),
            bodyYaw = 0.0f,
            pitch = 0.0f,
            height = 1.8f,
            rightMainArm = true,
            sneaking = false,
            swimming = false,
            flying = false,
            velocity = Vec3d.EMPTY,
        )

        assertEquals(-0.22, anchor.x, 0.000001)
        assertEquals(0.8, anchor.y, 0.000001)
        assertEquals(0.07, anchor.z, 0.000001)
    }

    @Test
    fun `swimming and flying player holder anchors use pose-specific hand positions`() {
        val swimming = EntityLeashProjector.playerHolderAnchor(
            position = Vec3d.EMPTY,
            bodyYaw = 0.0f,
            pitch = 0.0f,
            height = 0.6f,
            rightMainArm = true,
            sneaking = false,
            swimming = true,
            flying = false,
            velocity = Vec3d.EMPTY,
        )
        val flying = EntityLeashProjector.playerHolderAnchor(
            position = Vec3d.EMPTY,
            bodyYaw = 0.0f,
            pitch = 0.0f,
            height = 0.6f,
            rightMainArm = true,
            sneaking = false,
            swimming = false,
            flying = true,
            velocity = Vec3d(0.0, 0.0, 1.0),
        )

        assertEquals(Vec3d(-0.22, 0.2, -0.15), swimming)
        assertEquals(Vec3d(-0.22, -0.11, 0.85), flying)
    }

    @Test
    fun `ribbons use audited count width alternating color and upward sag`() {
        val start = Vec3f(0.0f, 0.0f, 0.0f)
        val end = Vec3f(4.0f, 4.0f, 0.0f)
        val quads = EntityLeashProjector.ribbons(start, end)

        assertEquals(EntityLeashProjector.SEGMENTS * 2, quads.size)
        assertEquals(EntityLeashProjector.DARK, quads.first().color0)
        assertEquals(EntityLeashProjector.LIGHT, quads.first().color1)
        assertEquals(EntityLeashProjector.LIGHT, quads[EntityLeashProjector.SEGMENTS].color0)
        assertEquals(EntityLeashProjector.WIDTH, quads.first().first0.y - quads.first().second0.y, 0.000001f)
        assertEquals(1.0f, EntityLeashProjector.point(start, end, 12).y, 0.000001f)
        assertTrue(quads.all { quad ->
            listOf(quad.first0, quad.second0, quad.second1, quad.first1).all {
                it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
            }
        })
        assertTrue(quads.all { quad ->
            val length = kotlin.math.sqrt(
                quad.normal.x * quad.normal.x +
                    quad.normal.y * quad.normal.y +
                    quad.normal.z * quad.normal.z,
            )
            kotlin.math.abs(length - 1.0f) < 0.0001f
        })
    }

    @Test
    fun `vertical leash remains finite`() {
        val quads = EntityLeashProjector.ribbons(
            Vec3f(1.0f, 1.0f, 1.0f),
            Vec3f(1.0f, 4.0f, 1.0f),
        )

        assertTrue(quads.flatMap { listOf(it.first0, it.second0, it.second1, it.first1) }.all {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
        })
        assertTrue(quads.all { it.normal.x.isFinite() && it.normal.y.isFinite() && it.normal.z.isFinite() })
        assertEquals(Vec3f(0.0f, 0.0f, 1.0f), quads.first().normal)
        assertEquals(Vec3f(1.0f, 0.0f, 0.0f), quads[EntityLeashProjector.SEGMENTS].normal)
    }

    @Test
    fun `block and sky light interpolate independently with vanilla truncation`() {
        val start = LightLevel(block = 0, sky = 15)
        val end = LightLevel(block = 15, sky = 0)

        assertEquals(start, EntityLeashProjector.interpolateLight(start, end, 0))
        assertEquals(LightLevel(block = 7, sky = 7), EntityLeashProjector.interpolateLight(start, end, 12))
        assertEquals(end, EntityLeashProjector.interpolateLight(start, end, EntityLeashProjector.SEGMENTS))

        val first = EntityLeashProjector.ribbons(
            Vec3f.EMPTY,
            Vec3f(1.0f, 0.0f, 0.0f),
            start,
            end,
        ).first()
        assertEquals(start, first.light0)
        assertEquals(EntityLeashProjector.interpolateLight(start, end, 1), first.light1)
    }
}
