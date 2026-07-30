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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.player

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.entities.entities.player.SkinParts
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.AbstractSkeletalMeshBuilder
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMeshUtil
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadConsumer.Companion.iterate
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray

/**
 * Player meshes select their dynamic skin layer at draw time. Their baked UVs
 * therefore stay in the logical 0..1 skin domain instead of inheriting the
 * physical sub-rectangle of a placeholder static-array texture.
 */
internal object PlayerSkinUvTexture : ShaderTexture {
    override val shaderId = 0
    override val transparency = TextureTransparencies.TRANSLUCENT

    override fun transformUV(uv: Vec2f) = uv
    override fun transformUV(u: Float, v: Float) = PackedUV(u, v)
    override fun transformU(u: Float) = u
    override fun transformV(v: Float) = v
    override fun transformUV(uv: PackedUV) = uv
}

open class PlayerModelMeshBuilder(context: RenderContext) : AbstractSkeletalMeshBuilder(context, PlayerMeshStruct, 1) {

    inline fun addVertex(
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        partTransformNormal: Float,
        midUv: Vec2f,
        tangent: Vec4f,
    ) {
        data.add(
            x, y, z,
            u, v,
            partTransformNormal,
            midUv.x, midUv.y,
        )
        data.add(tangent.x, tangent.y, tangent.z, tangent.w)
    }

    private fun addVertex(
        position: FaceVertexData,
        positionOffset: Int,
        uv: UnpackedUVArray,
        uvOffset: Int,
        partTransformNormal: Float,
        midUv: Vec2f,
        tangent: Vec4f,
    ) = addVertex(
        position[positionOffset + 0], position[positionOffset + 1], position[positionOffset + 2],
        uv.raw[uvOffset + 0], uv.raw[uvOffset + 1],
        partTransformNormal,
        midUv,
        tangent,
    )

    override fun addQuad(positions: FaceVertexData, uv: UnpackedUVArray, transform: Int, normal: Vec3f, texture: ShaderTexture, path: String) {
        val part = encodedPart(path)
        val partTransformNormal = ((part shl 19) or (transform shl 12) or SkeletalMeshUtil.encodeNormal(normal)).buffer()
        val midUv = SkeletalMeshUtil.faceUvMidpoint(uv)
        val tangent = SkeletalMeshUtil.faceTangent(positions, uv, normal)

        // TODO: verify render order
        iterate {
            addVertex(
                positions,
                it * Vec3f.LENGTH,
                uv,
                it * Vec2f.LENGTH,
                partTransformNormal,
                midUv,
                tangent,
            )
        }
        addIndexQuad()
    }

    data class PlayerMeshStruct(
        val position: Vec3f,
        val uv: Vec2f,
        val partTransformNormal: Int,
        val midUv: Vec2f,
        val tangent: Vec4f,
    ) {
        companion object : MeshStruct(PlayerMeshStruct::class)
    }

    companion object {
        const val ETF_TEXTURED_NOSE_PART = 0xFD
        const val ETF_VILLAGER_NOSE_PART = 0xFE

        internal fun encodedPart(path: String): Int = when (path) {
            "head.etf_textured_nose" -> ETF_TEXTURED_NOSE_PART
            "head.etf_villager_nose" -> ETF_VILLAGER_NOSE_PART
            "head.hat" -> SkinParts.HAT.ordinal + 1
            "body.jacket" -> SkinParts.JACKET.ordinal + 1
            "left_leg.pants" -> SkinParts.LEFT_PANTS.ordinal + 1
            "right_leg.pants" -> SkinParts.RIGHT_PANTS.ordinal + 1
            "left_arm.sleeve" -> SkinParts.LEFT_SLEEVE.ordinal + 1
            "right_arm.sleeve" -> SkinParts.RIGHT_SLEEVE.ordinal + 1
            else -> 0x00
        }
    }
}
