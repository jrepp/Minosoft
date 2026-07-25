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

package de.bixilon.minosoft.assets.model.skeletal.gecko

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeckoLibParserTest {
    private fun String.stream() = ByteArrayInputStream(encodeToByteArray())

    @Test
    fun `parse geometry hierarchy box and per-face uv`() {
        val document = GeckoLibParser().parseGeometry(
            minosoft("geo/test.geo.json"),
            """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {
                      "identifier": "geometry.test",
                      "texture_width": 64,
                      "texture_height": 32,
                      "visible_bounds_width": 2
                    },
                    "bones": [
                      {
                        "name": "root",
                        "pivot": [0, 12, 0],
                        "cubes": [{
                          "origin": [-4, 0, -2],
                          "size": [8, 12, 4],
                          "inflate": 0.25,
                          "uv": [16, 16]
                        }]
                      },
                      {
                        "name": "head",
                        "parent": "root",
                        "pivot": [0, 12, 0],
                        "rotation": [1, 2, 3],
                        "cubes": [{
                          "origin": [-4, 12, -4],
                          "size": [8, 8, 8],
                          "uv": {
                            "north": {"uv": [0, 0], "uv_size": [8, 8]}
                          }
                        }]
                      }
                    ]
                  }]
                }
            """.trimIndent().stream(),
        )

        val model = document.models.single()
        assertEquals(model.format, SkeletalContentFormat.GECKOLIB)
        assertEquals(model.identifier, "geometry.test")
        assertEquals(model.roots.single().name, "root")
        assertEquals(model.roots.single().children.single().name, "head")
        assertEquals(model.bones.getValue("root").cubes.single().inflate, Vec3f(0.25f))

        val faces = model.bones.getValue("head").cubes.single().uv as SkeletalUv.Faces
        assertEquals(faces.faces.getValue(Directions.NORTH).offset, Vec2f(0.0f, 0.0f))
    }

    @Test
    fun `parse and attach numeric and expression animations`() {
        val parser = GeckoLibParser()
        val geometry = parser.parseGeometry(
            minosoft("geo/test.geo.json"),
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test"},"bones":[{"name":"root"}]}]}""".stream(),
        ).models.single()
        val animations = parser.parseAnimations(
            minosoft("animations/test.animation.json"),
            """
                {
                  "format_version": "1.8.0",
                  "animations": {
                    "move": {
                      "loop": true,
                      "animation_length": 2,
                      "sound_effects": {
                        "0.25": {"effect": "test:step"}
                      },
                      "particle_effects": {
                        "0.5": {
                          "effect": "test:dust",
                          "locator": "foot",
                          "pre_effect_script": "variable.alpha = 1;"
                        }
                      },
                      "timeline": {
                        "0.75": ["instruction.one", "instruction.two"]
                      },
                      "bones": {
                        "root": {
                          "rotation": {
                            "0.0": {"vector": [0, 0, 0]},
                            "1.0": {"vector": ["query.head_x_rotation", 0, 0], "lerp_mode": "catmullrom"}
                          },
                          "scale": {"vector": [1, 1, 1]}
                        }
                      }
                    }
                  }
                }
            """.trimIndent().stream(),
        )
        val model = parser.attachAnimations(geometry, animations)
        val animation = model.animations.getValue("move")

        assertEquals(animation.loop, SkeletalAnimationLoop.LOOP)
        assertEquals(animation.lengthSeconds, 2.0f)
        val rotation = animation.channels.getValue("root").first { it.target == SkeletalAnimationTarget.ROTATION }
        assertEquals(rotation.keyframes.last().interpolation, SkeletalInterpolation.CATMULL_ROM)
        assertTrue(rotation.keyframes.last().value is SkeletalVectorValue.Expression)
        assertEquals(
            listOf(
                SkeletalAnimationEventType.SOUND,
                SkeletalAnimationEventType.PARTICLE,
                SkeletalAnimationEventType.CUSTOM_INSTRUCTION,
            ),
            animation.events.map(SkeletalAnimationEvent::type),
        )
        assertEquals("test:step", animation.events[0].payload)
        assertEquals("foot", animation.events[1].locator)
        assertEquals("instruction.one;instruction.two", animation.events[2].payload)
    }

    @Test
    fun `custom animation loop type survives neutral parsing`() {
        val animation = GeckoLibParser().parseAnimations(
            minosoft("animations/custom_loop.animation.json"),
            """
                {
                  "animations": {
                    "pulse": {
                      "loop": "test:conditional",
                      "animation_length": 1,
                      "bones": {}
                    }
                  }
                }
            """.trimIndent().stream(),
        ).getValue("pulse")

        assertEquals(SkeletalAnimationLoop.ONCE, animation.loop)
        assertEquals("test:conditional", animation.sourceLoopType)
    }
}
