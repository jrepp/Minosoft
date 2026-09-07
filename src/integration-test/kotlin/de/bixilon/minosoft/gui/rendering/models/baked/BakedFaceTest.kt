/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.models.baked

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.collections.primitive.floats.HeapFloatList
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.kutil.reflection.ReflectionUtil.getFieldOrNull
import de.bixilon.minosoft.data.text.formatting.color.RGBArray
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshBuilder
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshesBuilder
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil
import de.bixilon.minosoft.gui.rendering.light.terrain.TerrainQuadLight
import de.bixilon.minosoft.gui.rendering.models.block.state.baked.BakedFace
import de.bixilon.minosoft.gui.rendering.models.block.state.baked.Shades
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureLoader
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureRenderData
import de.bixilon.minosoft.gui.rendering.terrain.IrisTerrainMaterial
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray
import de.bixilon.minosoft.test.ITUtil.allocate
import de.bixilon.minosoft.util.KUtil.toResourceLocation
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["models"])
class BakedFaceTest {
    private val texture = "block/test"

    private fun texture(): Texture {
        val manager = BakedModelTestUtil.createTextureManager(texture)
        return manager.static.create(texture.toResourceLocation()).apply { this::loader.forceSet(DummyTextureLoader); load(RenderContext::class.java.allocate()); renderData = DummyTextureRenderData; transparency = TextureTransparencies.OPAQUE }
    }

    private fun singleMesh(): ChunkMeshBuilder {
        val mesh = ChunkMeshBuilder::class.java.allocate()

        mesh::primitive.forceSet(PrimitiveTypes.QUAD)
        mesh::struct.forceSet(ChunkMeshBuilder.ChunkMeshStruct)

        mesh::class.java.getFieldOrNull("_data")!!.forceSet(mesh, HeapFloatList(1000))

        mesh::estimate.forceSet(1000)

        return mesh
    }

    private fun mesh(): ChunkMeshesBuilder {
        val mesh = ChunkMeshesBuilder::class.java.allocate()
        mesh::opaque.forceSet(singleMesh())

        return mesh
    }

    fun mixed() {
        // TODO: negative uv is not supported anymore
        val face = BakedFace(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f), UnpackedUVArray(floatArrayOf(-1f, -2f, -3f, -4f, -5f, -6f, -7f, -8f)), Shades.UP, -1, null, texture())

        val mesh = mesh()

        face.render(Vec3f(0.0f, 0.0f, 0.0f), mesh, null, byteArrayOf(0, 0, 0, 0, 0, 0, 0), AmbientOcclusionUtil.EMPTY)

        val texture = 0.buffer()
        val lightTint = 0xFFFFFF.buffer()

        val data = baseVertices(mesh.opaque.data.toArray())
        val expected = floatArrayOf(
            0f, 1f, 2f, PackedUV(-1f, -2f).raw, texture, lightTint,
            3f, 4f, 5f, PackedUV(-3f, -4f).raw, texture, lightTint,
            6f, 7f, 8f, PackedUV(-5f, -6f).raw, texture, lightTint,
            9f, 10f, 11f, PackedUV(-7f, -8f).raw, texture, lightTint,
        )


        assertEquals(data, expected)
    }

    fun blockSouth() {
        val face = BakedFace(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f), UnpackedUVArray(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)), Shades.UP, -1, null, texture())

        val mesh = mesh()

        face.render(Vec3f(0.0f, 0.0f, 0.0f), mesh, null, byteArrayOf(0, 0, 0, 0, 0, 0, 0), AmbientOcclusionUtil.EMPTY)

        val texture = 0.buffer()
        val lightTint = 0xFFFFFF.buffer()

        val data = baseVertices(mesh.opaque.data.toArray())
        val expected = floatArrayOf(
            0f, 0f, 0f, PackedUV(0f, 0f).raw, texture, lightTint,
            0f, 1f, 0f, PackedUV(0f, 1f).raw, texture, lightTint,
            0f, 1f, 1f, PackedUV(1f, 1f).raw, texture, lightTint,
            0f, 0f, 1f, PackedUV(1f, 0f).raw, texture, lightTint,
        )


        assertEquals(data, expected)
    }

    fun `terrain quad writes one shared Iris material frame and per vertex mid block`() {
        val face = BakedFace(
            floatArrayOf(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
            ),
            UnpackedUVArray(
                floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f,
                    0.0f, 1.0f,
                ),
            ),
            Shades.UP,
            -1,
            null,
            texture(),
        )
        val mesh = singleMesh().apply {
            material = IrisTerrainMaterial(
                blockId = 41,
                centerX = 0.5f,
                centerY = 0.5f,
                centerZ = 0.5f,
                lightValue = 14,
            )
        }

        face.render(Vec3f.EMPTY, mesh, null, ByteArray(7), AmbientOcclusionUtil.EMPTY)

        val data = mesh.data.toArray()
        val stride = ChunkMeshBuilder.ChunkMeshStruct.floats
        assertEquals(stride, 21)
        for (vertex in 0 until 4) {
            val offset = vertex * stride
            assertEquals(data[offset + 6], 41.0f)
            assertEquals(data[offset + 7], -1.0f)
            assertEquals(data[offset + 8], 0.5f, 0.001f)
            assertEquals(data[offset + 9], 0.5f, 0.001f)
            assertEquals(data[offset + 10], 1.0f, 0.001f)
            assertEquals(data[offset + 14], 0.0f, 0.001f)
            assertEquals(data[offset + 15], 0.0f, 0.001f)
            assertEquals(data[offset + 16], -1.0f, 0.001f)
            assertEquals(data[offset + 20], 14.0f)
        }
        assertEquals(data[17], 0.5f, 0.001f)
        assertEquals(data[18], 0.5f, 0.001f)
        assertEquals(data[19], 0.5f, 0.001f)
        assertEquals(data[stride + 17], -0.5f, 0.001f)
    }

    fun `smooth terrain quad preserves independent light and tint per vertex`() {
        val face = BakedFace(
            floatArrayOf(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
            ),
            UnpackedUVArray(floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f)),
            Shades.UP,
            0,
            null,
            texture(),
        )
        val mesh = singleMesh()
        val fallback = RGBArray(1).apply { this[0] = RGBColor(0x123456) }
        val lights = intArrayOf(0x10, 0x42, 0xA5, 0xFF)
        val colors = arrayOf(
            RGBColor(0x112233),
            RGBColor(0x446688),
            RGBColor(0x99BBDD),
            RGBColor(0xFEDCBA),
        )

        face.render(
            Vec3f.EMPTY,
            mesh,
            fallback,
            TerrainQuadLight(lights, FloatArray(4) { 1.0f }, flipDiagonal = true),
            colors,
        )

        val data = mesh.data.toArray()
        val stride = ChunkMeshBuilder.ChunkMeshStruct.floats
        for (vertex in 0 until 4) {
            assertEquals(
                data[vertex * stride + 5].toRawBits(),
                (lights[vertex] shl 24) or colors[vertex].rgb,
            )
        }
    }


    // TODO: triangle order

    private fun baseVertices(data: FloatArray): FloatArray {
        val stride = ChunkMeshBuilder.ChunkMeshStruct.floats
        assertEquals(data.size, stride * 4)
        return FloatArray(6 * 4) { index ->
            val vertex = index / 6
            val component = index % 6
            data[vertex * stride + component]
        }
    }
}
