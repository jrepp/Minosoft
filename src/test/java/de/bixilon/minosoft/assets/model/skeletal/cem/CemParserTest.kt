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

package de.bixilon.minosoft.assets.model.skeletal.cem

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalUv
import de.bixilon.minosoft.data.Axes
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CemParserTest {
    private fun String.stream() = ByteArrayInputStream(encodeToByteArray())

    @Test
    fun `parse jem geometry expressions and external jpm`() {
        val jpm = """
            {
              "id": "template_head",
              "part": "head",
              "translate": [0, 8, 0],
              "boxes": [{
                "coordinates": [-4, 0, -4, 8, 8, 8],
                "uvNorth": [0, 0, 8, 8],
                "uvSouth": [8, 0, 16, 8]
              }]
            }
        """.trimIndent()
        val parser = CemParser { _, reference -> if (reference == "head.jpm") jpm.stream() else null }
        val model = parser.parseJem(
            minosoft("cem/zombie.jem"),
            """
                {
                  "texture": "zombie.png",
                  "textureSize": [64, 64],
                  "shadow_size": 0.5,
                  "models": [
                    {
                      "id": "body",
                      "part": "body",
                      "invertAxis": "xy",
                      "mirrorTexture": "u",
                      "translate": [0, 12, 0],
                      "rotate": [1, 2, 3],
                      "boxes": [{
                        "coordinates": [-4, 0, -2, 8, 12, 4],
                        "textureOffset": [16, 16],
                        "sizeAdd": 0.25,
                        "sizeAddY": 0.5
                      }],
                      "animations": [{"this.rx": "sin(age)"}]
                    },
                    {
                      "model": "head.jpm",
                      "id": "head"
                    }
                  ]
                }
            """.trimIndent().stream(),
        )

        assertEquals(model.format, SkeletalContentFormat.OPTIFINE_CEM)
        assertEquals(model.identifier, "zombie")
        assertEquals(model.textureSize, Vec2i(64, 64))
        assertEquals(model.texture, "zombie.png")
        assertEquals(model.roots.size, 2)
        assertEquals(model.bones.keys, setOf("body", "head"))

        val body = model.bones.getValue("body")
        assertEquals(body.target, "body")
        assertEquals(body.pivot, Vec3f(0.0f, 12.0f, 0.0f))
        assertEquals(body.rotation, Vec3f(1.0f, 2.0f, 3.0f))
        assertEquals(body.invertAxes, setOf(Axes.X, Axes.Y))
        assertTrue(body.mirrorTextureU)
        assertEquals(body.cubes.single().inflate, Vec3f(0.25f, 0.75f, 0.25f))
        assertEquals((body.cubes.single().uv as SkeletalUv.Box).offset, Vec2f(16.0f, 16.0f))
        assertEquals(model.expressions.single().expression, "sin(age)")

        val headFaces = model.bones.getValue("head").cubes.single().uv as SkeletalUv.Faces
        assertEquals(headFaces.faces.keys, setOf(Directions.NORTH, Directions.SOUTH))
    }

    @Test
    fun `parse standalone jpm`() {
        val model = CemParser().parseJpm(
            minosoft("cem/part.jpm"),
            """{"id":"root","boxes":[{"coordinates":[0,0,0,1,2,3],"textureOffset":[4,5]}]}""".stream(),
        )

        assertEquals(model.bones.getValue("root").cubes.single().size, Vec3f(1.0f, 2.0f, 3.0f))
    }
}
