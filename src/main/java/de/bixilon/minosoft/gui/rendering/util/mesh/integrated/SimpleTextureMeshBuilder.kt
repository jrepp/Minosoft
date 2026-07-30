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

package de.bixilon.minosoft.gui.rendering.util.mesh.integrated

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import kotlin.math.abs
import kotlin.math.sqrt

open class SimpleTextureMeshBuilder(context: RenderContext, estimate: Int = 1) : QuadMeshBuilder(context, SimpleTextureMeshStruct, estimate) {

    fun addVertex(
        x: Float,
        y: Float,
        z: Float,
        texture: ShaderTexture,
        uv: Vec2f,
        color: RGBAColor,
        normal: Vec3f = DEFAULT_NORMAL,
        tangent: Vec4f = DEFAULT_TANGENT,
        midUv: Vec2f = uv,
    ) {
        data.add(
            x, y, z,
            uv.x, uv.y,
            texture.shaderId.buffer(),
            color.rgba.buffer(),
        )
        data.add(normal.x, normal.y, normal.z)
        data.add(tangent.x, tangent.y, tangent.z, tangent.w)
        data.add(midUv.x, midUv.y)
    }

    fun addVertex(
        position: Vec3f,
        texture: ShaderTexture,
        uv: Vec2f,
        color: RGBAColor,
        normal: Vec3f = DEFAULT_NORMAL,
        tangent: Vec4f = DEFAULT_TANGENT,
        midUv: Vec2f = uv,
    ) = addVertex(
        position.x, position.y, position.z,
        texture,
        uv,
        color,
        normal,
        tangent,
        midUv,
    )

    /**
     * Adds one world-space textured quad with a producer-derived normal and
     * tangent shared by all four vertices. Screen-space users may continue to
     * call [addVertex]; their shader ignores the neutral fallback basis.
     */
    fun addQuad(
        positions: Array<out Vec3f>,
        uvs: Array<out Vec2f>,
        texture: ShaderTexture,
        color: RGBAColor,
    ) {
        require(positions.size == QUAD_VERTICES) { "Textured quads require four positions" }
        require(uvs.size == QUAD_VERTICES) { "Textured quads require four UV coordinates" }
        val basis = SimpleTextureQuadBasis.calculate(
            positions[0],
            positions[1],
            positions[2],
            uvs[0],
            uvs[1],
            uvs[2],
            uvs[3],
        )
        for (index in 0 until QUAD_VERTICES) {
            addVertex(
                positions[index],
                texture,
                uvs[index],
                color,
                basis.normal,
                basis.tangent,
                basis.midUv,
            )
        }
        addIndexQuad()
    }

    data class SimpleTextureMeshStruct(
        val position: Vec3f,
        val uv: Vec2f,
        val texture: ShaderTexture,
        val tint: RGBAColor,
        val normal: Vec3f,
        val tangent: Vec4f,
        val midUv: Vec2f,
    ) {
        companion object : MeshStruct(SimpleTextureMeshStruct::class)
    }

    private companion object {
        const val QUAD_VERTICES = 4
        val DEFAULT_NORMAL = Vec3f(0.0f, 0.0f, 1.0f)
        val DEFAULT_TANGENT = Vec4f(1.0f, 0.0f, 0.0f, 1.0f)
    }
}

/** Stable tangent basis derived from a textured quad's first triangle. */
data class SimpleTextureQuadBasis(
    val normal: Vec3f,
    val tangent: Vec4f,
    val midUv: Vec2f,
) {
    companion object {
        private const val EPSILON = 1.0e-8f

        fun calculate(
            position0: Vec3f,
            position1: Vec3f,
            position2: Vec3f,
            uv0: Vec2f,
            uv1: Vec2f,
            uv2: Vec2f,
            uv3: Vec2f,
        ): SimpleTextureQuadBasis {
            val edge1X = position1.x - position0.x
            val edge1Y = position1.y - position0.y
            val edge1Z = position1.z - position0.z
            val edge2X = position2.x - position0.x
            val edge2Y = position2.y - position0.y
            val edge2Z = position2.z - position0.z

            val rawNormalX = edge1Y * edge2Z - edge1Z * edge2Y
            val rawNormalY = edge1Z * edge2X - edge1X * edge2Z
            val rawNormalZ = edge1X * edge2Y - edge1Y * edge2X
            val normalLength = length(rawNormalX, rawNormalY, rawNormalZ)
            val normal = if (normalLength > EPSILON) {
                Vec3f(
                    rawNormalX / normalLength,
                    rawNormalY / normalLength,
                    rawNormalZ / normalLength,
                )
            } else {
                Vec3f(0.0f, 1.0f, 0.0f)
            }

            val deltaU1 = uv1.x - uv0.x
            val deltaV1 = uv1.y - uv0.y
            val deltaU2 = uv2.x - uv0.x
            val deltaV2 = uv2.y - uv0.y
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
                bitangentX = edge2X
                bitangentY = edge2Y
                bitangentZ = edge2Z
            }

            val tangentNormalDot =
                tangentX * normal.x + tangentY * normal.y + tangentZ * normal.z
            tangentX -= normal.x * tangentNormalDot
            tangentY -= normal.y * tangentNormalDot
            tangentZ -= normal.z * tangentNormalDot
            val tangentLength = length(tangentX, tangentY, tangentZ)
            if (tangentLength > EPSILON) {
                tangentX /= tangentLength
                tangentY /= tangentLength
                tangentZ /= tangentLength
            } else {
                val reference = if (abs(normal.y) < 0.999f) {
                    Vec3f(0.0f, 1.0f, 0.0f)
                } else {
                    Vec3f(0.0f, 0.0f, 1.0f)
                }
                tangentX = reference.y * normal.z - reference.z * normal.y
                tangentY = reference.z * normal.x - reference.x * normal.z
                tangentZ = reference.x * normal.y - reference.y * normal.x
                val fallbackLength = length(tangentX, tangentY, tangentZ)
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
            return SimpleTextureQuadBasis(
                normal = normal,
                tangent = Vec4f(tangentX, tangentY, tangentZ, handedness),
                midUv = Vec2f(
                    (uv0.x + uv1.x + uv2.x + uv3.x) * 0.25f,
                    (uv0.y + uv1.y + uv2.y + uv3.y) * 0.25f,
                ),
            )
        }

        private fun length(x: Float, y: Float, z: Float): Float =
            sqrt(x * x + y * y + z * z)
    }
}
