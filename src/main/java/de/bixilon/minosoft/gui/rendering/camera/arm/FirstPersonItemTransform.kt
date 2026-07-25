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

package de.bixilon.minosoft.gui.rendering.camera.arm

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.math.MathConstants.PIf
import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import kotlin.math.sin
import kotlin.math.sqrt

object FirstPersonItemTransform {
    fun displayPosition(arm: Arms) = when (arm) {
        Arms.LEFT -> DisplayPositions.FIRST_PERSON_LEFT_HAND
        Arms.RIGHT -> DisplayPositions.FIRST_PERSON_RIGHT_HAND
    }

    fun opposite(arm: Arms) = when (arm) {
        Arms.LEFT -> Arms.RIGHT
        Arms.RIGHT -> Arms.LEFT
    }

    /**
     * Reflects a right-hand item display transform across the camera's X axis.
     * Conjugating by the reflection preserves winding while mirroring both
     * translation and rotation.
     */
    fun mirrorRightHandDisplay(display: Mat4f): Mat4f {
        val mirrored = MMat4f(display)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                if ((row == 0) xor (column == 0)) {
                    mirrored[row, column] = -mirrored[row, column]
                }
            }
        }
        return mirrored.unsafe
    }

    fun create(arm: Arms, display: Mat4f, flat: Boolean, swingProgress: Float?): Mat4f {
        val side = if (arm == Arms.RIGHT) 1.0f else -1.0f
        return MMat4f().apply {
            translateAssign(Vec3f(side * 0.56f, -0.52f, -0.72f))

            swingProgress?.let { progress ->
                translateAssign(swingOffset(arm, progress))
                val rotation = swingRotation(arm, progress)
                rotateYAssign(rotation.y.rad)
                rotateZAssign(rotation.z.rad)
                rotateXAssign(rotation.x.rad)
            }

            if (flat) {
                // Generated items currently use one zero-thickness textured
                // quad. Their standard -90-degree handheld display yaw would
                // otherwise turn that quad exactly edge-on to the camera.
                rotateYAssign((side * FLAT_ITEM_YAW_DEGREES).rad)
            }
            this *= display
            if (flat) {
                // Flat item meshes are kept at the dropped-item 0.3-block size.
                // Normalize that quad around its center for the hand transform.
                scaleAssign(Vec3f(10.0f / 3.0f, 10.0f / 3.0f, 1.0f))
                translateAssign(Vec3f(-0.45f, -0.15f, -0.5f))
            } else {
                translateAssign(Vec3f(-0.5f))
            }
        }.unsafe
    }

    /** Vanilla-like first-person attack translation, mirrored for the active arm. */
    fun swingOffset(arm: Arms, progress: Float): Vec3f {
        val normalized = progress.coerceIn(0.0f, 1.0f)
        if (normalized == 0.0f || normalized == 1.0f) return Vec3f.EMPTY

        val side = if (arm == Arms.RIGHT) 1.0f else -1.0f
        val eased = sqrt(normalized)
        return Vec3f(
            -side * 0.4f * sin(eased * PIf),
            0.2f * sin(eased * PIf * 2.0f),
            -0.2f * sin(normalized * PIf),
        )
    }

    /** Vanilla-like attack rotations in degrees: x pitch, y yaw, z roll. */
    fun swingRotation(arm: Arms, progress: Float): Vec3f {
        val normalized = progress.coerceIn(0.0f, 1.0f)
        if (normalized == 0.0f || normalized == 1.0f) return Vec3f.EMPTY

        val side = if (arm == Arms.RIGHT) 1.0f else -1.0f
        val windup = sin(normalized * normalized * PIf)
        val swing = sin(sqrt(normalized) * PIf)
        return Vec3f(
            -80.0f * swing,
            -side * 20.0f * windup,
            -side * 20.0f * swing,
        )
    }

    private const val FLAT_ITEM_YAW_DEGREES = 45.0f
}
