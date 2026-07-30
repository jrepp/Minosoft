/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.mesh

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray
import kotlin.math.abs
import kotlin.math.sqrt

object SkeletalMeshUtil {
    private const val ADD = +1.001f
    private const val TANGENT_EPSILON = 1.0e-8f

    private fun encode(part: Float): Int {
        if (part <= -1.0f) return 0
        if (part >= 1.0f) return 0x0F
        val value = part * ADD
        if (value < 0.0f) return ((value + 1.0f) * 8.0f).toInt()
        return 8 + (value * 7.0f).toInt()
    }

    fun encodeNormal(normal: Vec3f): Int {
        val x = encode(normal.x) and 0x0F
        val y = encode(normal.y * ADD) and 0x0F
        val z = encode(normal.z) and 0x0F

        return (y shl 8) or (z shl 4) or (x)
    }

    fun faceUvMidpoint(uv: UnpackedUVArray): Vec2f {
        var u = 0.0f
        var v = 0.0f
        for (vertex in 0 until UnpackedUVArray.COMPONENT_SIZE) {
            val offset = vertex * UnpackedUVArray.COMPONENT
            u += uv.raw[offset]
            v += uv.raw[offset + 1]
        }
        return Vec2f(
            u / UnpackedUVArray.COMPONENT_SIZE,
            v / UnpackedUVArray.COMPONENT_SIZE,
        )
    }

    fun faceTangent(positions: FaceVertexData, uv: UnpackedUVArray, normal: Vec3f): Vec4f =
        faceTangent(
            positions,
            uv.raw[0], uv.raw[1],
            uv.raw[2], uv.raw[3],
            uv.raw[4], uv.raw[5],
            normal,
        )

    fun faceTangent(positions: FaceVertexData, uv: PackedUVArray, normal: Vec3f): Vec4f =
        faceTangent(
            positions,
            uv[0].u, uv[0].v,
            uv[1].u, uv[1].v,
            uv[2].u, uv[2].v,
            normal,
        )

    private fun faceTangent(
        positions: FaceVertexData,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        u2: Float,
        v2: Float,
        normal: Vec3f,
    ): Vec4f {
        require(positions.size == 12) { "Cuboid tangents require four vec3 positions" }
        val edge1X = positions[3] - positions[0]
        val edge1Y = positions[4] - positions[1]
        val edge1Z = positions[5] - positions[2]
        val edge2X = positions[6] - positions[0]
        val edge2Y = positions[7] - positions[1]
        val edge2Z = positions[8] - positions[2]
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
        if (abs(determinant) > TANGENT_EPSILON) {
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
            bitangentX = edge2X
            bitangentY = edge2Y
            bitangentZ = edge2Z
        }

        val length = sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ)
        if (length > TANGENT_EPSILON) {
            tangentX /= length
            tangentY /= length
            tangentZ /= length
        } else {
            val reference = if (abs(normal.y) < 0.999f) Vec3f(0.0f, 1.0f, 0.0f) else Vec3f(0.0f, 0.0f, 1.0f)
            tangentX = reference.y * normal.z - reference.z * normal.y
            tangentY = reference.z * normal.x - reference.x * normal.z
            tangentZ = reference.x * normal.y - reference.y * normal.x
            val fallbackLength = sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ)
            tangentX /= fallbackLength
            tangentY /= fallbackLength
            tangentZ /= fallbackLength
        }
        val crossX = normal.y * tangentZ - normal.z * tangentY
        val crossY = normal.z * tangentX - normal.x * tangentZ
        val crossZ = normal.x * tangentY - normal.y * tangentX
        val handedness = if (
            crossX * bitangentX + crossY * bitangentY + crossZ * bitangentZ < 0.0f
        ) {
            -1.0f
        } else {
            1.0f
        }
        return Vec4f(tangentX, tangentY, tangentZ, handedness)
    }
}
