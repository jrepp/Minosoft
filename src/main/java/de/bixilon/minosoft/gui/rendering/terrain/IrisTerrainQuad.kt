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

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Values shared by all four vertices of one Iris terrain quad.
 */
data class IrisTerrainQuad(
    val midTexture: Vec2f,
    val tangent: Vec4f,
    val normal: Vec3f,
) {
    companion object {
        private const val EPSILON = 1.0e-8f

        fun calculate(positions: FaceVertexData, uv: PackedUVArray): IrisTerrainQuad {
            require(positions.size == 12) { "Terrain quads require four vec3 positions" }

            val u0 = uv[0].u
            val v0 = uv[0].v
            val u1 = uv[1].u
            val v1 = uv[1].v
            val u2 = uv[2].u
            val v2 = uv[2].v
            val midTexture = Vec2f(
                (u0 + u1 + u2 + uv[3].u) * 0.25f,
                (v0 + v1 + v2 + uv[3].v) * 0.25f,
            )

            val edge1X = positions[3] - positions[0]
            val edge1Y = positions[4] - positions[1]
            val edge1Z = positions[5] - positions[2]
            val edge2X = positions[6] - positions[0]
            val edge2Y = positions[7] - positions[1]
            val edge2Z = positions[8] - positions[2]

            val rawNormalX = edge1Y * edge2Z - edge1Z * edge2Y
            val rawNormalY = edge1Z * edge2X - edge1X * edge2Z
            val rawNormalZ = edge1X * edge2Y - edge1Y * edge2X
            val normalLength = length(rawNormalX, rawNormalY, rawNormalZ)
            val normal = if (normalLength > EPSILON) {
                Vec3f(rawNormalX / normalLength, rawNormalY / normalLength, rawNormalZ / normalLength)
            } else {
                Vec3f(0.0f, 1.0f, 0.0f)
            }

            val deltaU1 = u1 - u0
            val deltaV1 = v1 - v0
            val deltaU2 = u2 - u0
            val deltaV2 = v2 - v0
            val determinant = deltaU1 * deltaV2 - deltaU2 * deltaV1

            var tangentX: Float
            var tangentY: Float
            var tangentZ: Float
            var bitangentX: Float
            var bitangentY: Float
            var bitangentZ: Float
            if (abs(determinant) > EPSILON) {
                val inverse = 1.0f / determinant
                tangentX = (deltaV2 * edge1X - deltaV1 * edge2X) * inverse
                tangentY = (deltaV2 * edge1Y - deltaV1 * edge2Y) * inverse
                tangentZ = (deltaV2 * edge1Z - deltaV1 * edge2Z) * inverse
                bitangentX = (-deltaU2 * edge1X + deltaU1 * edge2X) * inverse
                bitangentY = (-deltaU2 * edge1Y + deltaU1 * edge2Y) * inverse
                bitangentZ = (-deltaU2 * edge1Z + deltaU1 * edge2Z) * inverse
            } else {
                tangentX = edge1X
                tangentY = edge1Y
                tangentZ = edge1Z
                bitangentX = 0.0f
                bitangentY = 0.0f
                bitangentZ = 1.0f
            }

            val tangentLength = length(tangentX, tangentY, tangentZ)
            if (tangentLength > EPSILON) {
                tangentX /= tangentLength
                tangentY /= tangentLength
                tangentZ /= tangentLength
            } else {
                tangentX = 1.0f
                tangentY = 0.0f
                tangentZ = 0.0f
            }
            val crossX = normal.y * tangentZ - normal.z * tangentY
            val crossY = normal.z * tangentX - normal.x * tangentZ
            val crossZ = normal.x * tangentY - normal.y * tangentX
            val handedness = if (crossX * bitangentX + crossY * bitangentY + crossZ * bitangentZ < 0.0f) {
                -1.0f
            } else {
                1.0f
            }

            return IrisTerrainQuad(
                midTexture = midTexture,
                tangent = Vec4f(tangentX, tangentY, tangentZ, handedness),
                normal = normal,
            )
        }

        private fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
    }
}
