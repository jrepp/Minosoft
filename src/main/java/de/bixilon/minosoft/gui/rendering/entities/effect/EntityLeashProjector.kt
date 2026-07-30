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
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audited Minecraft 1.20.4 leash anchor and ribbon geometry.
 *
 * The renderer emits two crossed, 24-segment ribbons. Keeping the projection
 * independent from OpenGL makes the compatibility boundary testable.
 */
object EntityLeashProjector {
    const val SEGMENTS = 24
    const val WIDTH = 0.025f

    val LIGHT = RGBAColor(0.5f, 0.4f, 0.3f)
    val DARK = RGBAColor(0.35f, 0.28f, 0.21f)

    data class RibbonQuad(
        val first0: Vec3f,
        val second0: Vec3f,
        val second1: Vec3f,
        val first1: Vec3f,
        val color0: RGBAColor,
        val color1: RGBAColor,
        val light0: LightLevel,
        val light1: LightLevel,
        val normal: Vec3f,
    )

    /**
     * Mob-side anchor. EMF leash outputs are additions to the vanilla local
     * offset and therefore rotate with the mob's body yaw.
     */
    fun mobAnchor(
        position: Vec3d,
        bodyYaw: Float,
        width: Float,
        standingEyeHeight: Float,
        emfOffset: Vec3f = Vec3f.EMPTY,
    ): Vec3d {
        val angle = Math.toRadians(bodyYaw.toDouble()) + PI / 2.0
        val localX = emfOffset.x.toDouble()
        val localZ = width * 0.4 + emfOffset.z
        val x = cos(angle) * localZ + sin(angle) * localX
        val z = sin(angle) * localZ - cos(angle) * localX
        return Vec3d(
            position.x + x,
            position.y + standingEyeHeight + emfOffset.y,
            position.z + z,
        )
    }

    fun genericHolderAnchor(position: Vec3d, standingEyeHeight: Float) =
        Vec3d(position.x, position.y + standingEyeHeight * 0.7, position.z)

    fun knotHolderAnchor(position: Vec3d) =
        Vec3d(position.x, position.y + 0.2, position.z)

    /**
     * Player-side holder anchor for normal, swimming, and elytra/riptide poses.
     * Minosoft supplies already-interpolated render rotation to this function.
     */
    fun playerHolderAnchor(
        position: Vec3d,
        bodyYaw: Float,
        pitch: Float,
        height: Float,
        rightMainArm: Boolean,
        sneaking: Boolean,
        swimming: Boolean,
        flying: Boolean,
        velocity: Vec3d,
    ): Vec3d {
        val side = 0.22 * if (rightMainArm) -1.0 else 1.0
        val yaw = -Math.toRadians(bodyYaw.toDouble())
        val pitchRadians = -Math.toRadians(pitch.toDouble()) * 0.5
        val offset = when {
            flying -> {
                val view = viewVector(bodyYaw, pitch)
                val velocityLength2 = velocity.x * velocity.x + velocity.z * velocity.z
                val viewLength2 = view.x * view.x + view.z * view.z
                val roll = if (velocityLength2 > 0.0 && viewLength2 > 0.0) {
                    val dot = ((velocity.x * view.x + velocity.z * view.z) / sqrt(velocityLength2 * viewLength2)).coerceIn(-1.0, 1.0)
                    sign(velocity.x * view.z - velocity.z * view.x) * acos(dot)
                } else {
                    0.0
                }
                rotateY(rotateX(rotateZ(Vec3d(side, -0.11, 0.85), -roll), pitchRadians), yaw)
            }

            swimming -> rotateY(rotateX(Vec3d(side, 0.2, -0.15), pitchRadians), yaw)
            else -> rotateY(Vec3d(side, height - 1.0, if (sneaking) -0.2 else 0.07), yaw)
        }
        return Vec3d(position.x + offset.x, position.y + offset.y, position.z + offset.z)
    }

    fun point(start: Vec3f, end: Vec3f, index: Int): Vec3f {
        require(index in 0..SEGMENTS)
        val progress = index.toFloat() / SEGMENTS
        val differenceY = end.y - start.y
        val y = if (differenceY > 0.0f) {
            start.y + differenceY * progress * progress
        } else {
            start.y + differenceY - differenceY * (1.0f - progress) * (1.0f - progress)
        }
        return Vec3f(
            start.x + (end.x - start.x) * progress,
            y,
            start.z + (end.z - start.z) * progress,
        )
    }

    fun interpolateLight(start: LightLevel, end: LightLevel, index: Int): LightLevel {
        require(index in 0..SEGMENTS)
        val progress = index.toFloat() / SEGMENTS
        return LightLevel(
            (start.block + (end.block - start.block) * progress).toInt(),
            (start.sky + (end.sky - start.sky) * progress).toInt(),
        )
    }

    fun ribbons(
        start: Vec3f,
        end: Vec3f,
        startLight: LightLevel = LightLevel.MAX,
        endLight: LightLevel = LightLevel.MAX,
    ): List<RibbonQuad> {
        val differenceX = end.x - start.x
        val differenceZ = end.z - start.z
        val horizontalLength = sqrt(differenceX * differenceX + differenceZ * differenceZ)
        val scale = if (horizontalLength == 0.0f) 0.0f else WIDTH / horizontalLength / 2.0f
        val offsetX = differenceZ * scale
        val offsetZ = differenceX * scale
        val quads = ArrayList<RibbonQuad>(SEGMENTS * 2)

        for (reverse in arrayOf(false, true)) {
            var previous = crossSection(point(start, end, 0), offsetX, offsetZ, reverse)
            var previousColor = color(0, reverse)
            var previousLight = startLight
            for (index in 1..SEGMENTS) {
                val current = crossSection(point(start, end, index), offsetX, offsetZ, reverse)
                val currentColor = color(index, reverse)
                val currentLight = interpolateLight(startLight, endLight, index)
                quads += RibbonQuad(
                    previous.first,
                    previous.second,
                    current.second,
                    current.first,
                    previousColor,
                    currentColor,
                    previousLight,
                    currentLight,
                    ribbonNormal(previous.first, current.first, current.second, reverse),
                )
                previous = current
                previousColor = currentColor
                previousLight = currentLight
            }
        }
        return quads
    }

    private fun color(index: Int, reverse: Boolean) =
        if (index % 2 == if (reverse) 1 else 0) DARK else LIGHT

    /**
     * Normal of the retained front triangle (first0, first1, second1). The two
     * bounded fallbacks preserve distinct crossed-ribbon orientations when a
     * vertical or zero-length leash collapses the submitted face.
     */
    private fun ribbonNormal(
        first0: Vec3f,
        first1: Vec3f,
        second1: Vec3f,
        reverse: Boolean,
    ): Vec3f {
        val segmentX = first1.x - first0.x
        val segmentY = first1.y - first0.y
        val segmentZ = first1.z - first0.z
        val diagonalX = second1.x - first0.x
        val diagonalY = second1.y - first0.y
        val diagonalZ = second1.z - first0.z
        val normalX = segmentY * diagonalZ - segmentZ * diagonalY
        val normalY = segmentZ * diagonalX - segmentX * diagonalZ
        val normalZ = segmentX * diagonalY - segmentY * diagonalX
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (length <= NORMAL_EPSILON) {
            return if (reverse) Vec3f(1.0f, 0.0f, 0.0f) else Vec3f(0.0f, 0.0f, 1.0f)
        }
        return Vec3f(normalX / length, normalY / length, normalZ / length)
    }

    private fun crossSection(point: Vec3f, offsetX: Float, offsetZ: Float, reverse: Boolean): Pair<Vec3f, Vec3f> {
        val firstY = if (reverse) point.y else point.y + WIDTH
        val secondY = if (reverse) point.y + WIDTH else point.y
        return Vec3f(point.x - offsetX, firstY, point.z + offsetZ) to
            Vec3f(point.x + offsetX, secondY, point.z - offsetZ)
    }

    private fun viewVector(yaw: Float, pitch: Float): Vec3d {
        val pitchRadians = Math.toRadians(pitch.toDouble())
        val yawRadians = -Math.toRadians(yaw.toDouble())
        val pitchCos = cos(pitchRadians)
        return Vec3d(sin(yawRadians) * pitchCos, -sin(pitchRadians), cos(yawRadians) * pitchCos)
    }

    private fun rotateX(vector: Vec3d, angle: Double): Vec3d {
        val cosine = cos(angle)
        val sine = sin(angle)
        return Vec3d(vector.x, vector.y * cosine + vector.z * sine, vector.z * cosine - vector.y * sine)
    }

    private fun rotateY(vector: Vec3d, angle: Double): Vec3d {
        val cosine = cos(angle)
        val sine = sin(angle)
        return Vec3d(vector.x * cosine + vector.z * sine, vector.y, vector.z * cosine - vector.x * sine)
    }

    private fun rotateZ(vector: Vec3d, angle: Double): Vec3d {
        val cosine = cos(angle)
        val sine = sin(angle)
        return Vec3d(vector.x * cosine + vector.y * sine, vector.y * cosine - vector.x * sine, vector.z)
    }

    private const val NORMAL_EPSILON = 1.0e-8f
}
