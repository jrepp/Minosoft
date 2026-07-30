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

package de.bixilon.minosoft.gui.rendering.skeletal.binding

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliasSet
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliases
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalElement
import de.bixilon.minosoft.gui.rendering.skeletal.model.transforms.SkeletalTransform
import de.bixilon.minosoft.gui.rendering.models.block.element.face.FaceUV
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkeletalModelBinderTest {

    @Test
    fun `Gecko box UV size is floored before geometry inflation`() {
        val content = SkeletalContent(
            source = ResourceLocation.of("test:geo/inflated.geo.json"),
            format = SkeletalContentFormat.GECKOLIB,
            formatVersion = "1.12.0",
            identifier = "geometry.inflated",
            textureSize = Vec2i(64, 64),
            roots = listOf(
                SkeletalBone(
                    name = "root",
                    cubes = listOf(
                        SkeletalCube(
                            origin = Vec3f.EMPTY,
                            size = Vec3f(4.75f, 3.5f, 6.9f),
                            inflate = Vec3f(0.01f),
                            uv = SkeletalUv.Box(Vec2f.EMPTY),
                        ),
                    ),
                ),
            ),
        )

        val cube = assertNotNull(
            SkeletalModelBinder.bind(content).model.elements["root"]?.children?.get("cube_0"),
        )

        assertEquals(Vec3f(-0.01f), cube.from)
        assertEquals(Vec3f(4.75f, 3.5f, 6.9f) + Vec3f(0.01f), cube.to)
        assertEquals(Vec3f(4.0f, 3.0f, 6.0f), cube.boxUvSize)
    }

    @Test
    fun `Gecko hinged planes overlap their parent boundary without changing UV size`() {
        val tail = SkeletalCube(
            origin = Vec3f(0.0f, 2.0f, 4.5f),
            size = Vec3f(0.0f, 6.0f, 7.0f),
            pivot = Vec3f(0.0f, 6.0f, 4.5f),
            inflate = Vec3f(0.01f),
            uv = SkeletalUv.Box(Vec2f(0.0f, 16.0f)),
        )

        val (from, to) = SkeletalModelBinder.cubeBounds(SkeletalContentFormat.GECKOLIB, tail)

        assertEquals(-0.01f, from.x, 0.0001f)
        assertEquals(0.01f, to.x, 0.0001f)
        assertEquals(4.44f, from.z, 0.0001f)
        assertEquals(11.51f, to.z, 0.0001f)
    }

    @Test
    fun `Gecko box UV maps authored front to north and honors mirror`() {
        val offset = Vec2i(0, 0)
        val size = Vec3f(4.0f, 4.0f, 5.0f)

        assertEquals(
            FaceUV(Vec2f(5.0f, 5.0f), Vec2f(9.0f, 9.0f)),
            SkeletalModelBinder.geckoBoxUv(offset, size, Directions.NORTH, mirror = false),
        )
        assertEquals(
            FaceUV(Vec2f(18.0f, 5.0f), Vec2f(14.0f, 9.0f)),
            SkeletalModelBinder.geckoBoxUv(offset, size, Directions.SOUTH, mirror = true),
        )
    }

    @Test
    fun `Gecko plane within a quarter pixel snaps onto its hinge`() {
        val wing = SkeletalCube(
            origin = Vec3f(1.5f, 6.25f, -1.0f),
            size = Vec3f(9.0f, 0.0f, 6.0f),
            pivot = Vec3f(1.5f, 6.0f, 0.0f),
            uv = SkeletalUv.Box(Vec2f.EMPTY),
        )

        val (from, to) = SkeletalModelBinder.cubeBounds(SkeletalContentFormat.GECKOLIB, wing)

        assertEquals(6.0f, from.y, 0.0001f)
        assertEquals(6.0f, to.y, 0.0001f)
        assertEquals(1.45f, from.x, 0.0001f)
    }

    @Test
    fun `binds hierarchy transforms box UV and per-face UV without OpenGL`() {
        val content = SkeletalContent(
            source = ResourceLocation.of("test:geo/bird.geo.json"),
            format = SkeletalContentFormat.GECKOLIB,
            formatVersion = "1.12.0",
            identifier = "geometry.bird",
            textureSize = Vec2i(64, 32),
            texture = "entity/bird",
            roots = listOf(
                SkeletalBone(
                    name = "root",
                    pivot = Vec3f(0, 8, 0),
                    rotation = Vec3f(0, 90, 0),
                    cubes = listOf(SkeletalCube(Vec3f.EMPTY, Vec3f(2), uv = SkeletalUv.Box(Vec2f(0, 0)))),
                    children = listOf(
                        SkeletalBone(
                            name = "wing",
                            pivot = Vec3f(2, 8, 0),
                            cubes = listOf(
                                SkeletalCube(
                                    Vec3f(2, 7, 0),
                                    Vec3f(2, 1, 4),
                                    uv = SkeletalUv.Faces(
                                        mapOf(Directions.NORTH to SkeletalFaceUv(Vec2f(4, 4), Vec2f(2, 1), 90)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            expressions = listOf(SkeletalExpressionBinding("root", "this.ry", "query.turn")),
        )

        val binding = SkeletalModelBinder.bind(content)
        val root = assertNotNull(binding.model.elements["root"])
        val wing = assertNotNull(root.children["wing"])
        val wingCube = assertNotNull(wing.children["cube_0"])

        assertEquals("test:entity/bird", binding.defaultMaterial.toString())
        assertEquals("test:textures/entity/bird.png", binding.model.textures.getValue(binding.defaultMaterial).source.toString())
        assertTrue(root.children.containsKey("cube_0"))
        assertEquals(90, wingCube.faces.getValue(Directions.NORTH).rotation)
        assertEquals((-PI / 2.0).toFloat(), binding.model.transforms.getValue("root").rotation.y, 0.0001f)
        assertEquals(binding.expressions, binding.model.expressions)
        assertEquals("query.turn", binding.model.expressions.single().expression)
        assertEquals(
            SkeletalContentIdentity(content.source, content.format, content.identifier),
            binding.model.contentIdentity,
        )
    }

    @Test
    fun `binds CEM part aliases into geometry transforms and expressions`() {
        val entity = ResourceLocation.of("minecraft:zombie")
        val registration = SkeletalPartAliases.register(
            SkeletalPartAliasSet(
                id = "test:binder-zombie",
                entity = entity,
                minimumVersionId = 100,
                maximumVersionId = 200,
                aliases = mapOf("leftArm" to "left_arm"),
            ),
        )
        try {
            val content = SkeletalContent(
                source = ResourceLocation.of("test:optifine/cem/zombie.jem"),
                format = SkeletalContentFormat.OPTIFINE_CEM,
                formatVersion = null,
                identifier = "zombie",
                textureSize = Vec2i(64, 64),
                roots = listOf(
                    SkeletalBone(
                        name = "custom_arm",
                        target = "leftArm",
                        cubes = listOf(
                            SkeletalCube(
                                origin = Vec3f.EMPTY,
                                size = Vec3f(2.0f),
                                uv = SkeletalUv.Box(Vec2f.EMPTY),
                            ),
                        ),
                    ),
                ),
                expressions = listOf(
                    SkeletalExpressionBinding("custom_arm", "this.rx", "age"),
                ),
            )

            val binding = SkeletalModelBinder.bind(content, versionId = 150, entity = entity)

            assertTrue("left_arm" in binding.model.elements)
            assertTrue("left_arm" in binding.model.transforms)
            assertEquals("left_arm", binding.model.elements.getValue("left_arm").transform)
            assertEquals("left_arm", binding.aliases.getValue("custom_arm"))
            assertEquals("left_arm", binding.model.expressionAliases.getValue("leftArm"))
        } finally {
            registration.close()
        }
    }

    @Test
    fun `composes replacement and attached CEM roots with native parts`() {
        val nativeHead = SkeletalElement(
            from = Vec3f(-4.0f),
            to = Vec3f(4.0f),
            transform = "head",
            faces = emptyMap(),
        )
        val nativeBody = SkeletalElement(
            from = Vec3f(-3.0f),
            to = Vec3f(3.0f),
            transform = "body",
            faces = emptyMap(),
        )
        val base = SkeletalModel(
            elements = linkedMapOf("head" to nativeHead, "body" to nativeBody),
            textures = emptyMap(),
            transforms = linkedMapOf(
                "head" to SkeletalTransform(Vec3f(0, 24, 0)),
                "body" to SkeletalTransform(Vec3f(0, 12, 0)),
            ),
        )
        val content = SkeletalContent(
            source = ResourceLocation.of("minecraft:optifine/cem/zombie.jem"),
            format = SkeletalContentFormat.OPTIFINE_CEM,
            formatVersion = null,
            identifier = "zombie",
            textureSize = Vec2i(64, 64),
            roots = listOf(
                SkeletalBone(
                    name = "replacement_body",
                    target = "body",
                    cubes = listOf(
                        SkeletalCube(Vec3f.EMPTY, Vec3f(6.0f), uv = SkeletalUv.Box(Vec2f.EMPTY)),
                    ),
                ),
                SkeletalBone(
                    name = "hat",
                    target = "head",
                    attach = true,
                    pivot = Vec3f(0, 25, 0),
                    cubes = listOf(
                        SkeletalCube(Vec3f(-5.0f), Vec3f(10.0f), uv = SkeletalUv.Box(Vec2f.EMPTY)),
                    ),
                ),
            ),
            expressions = listOf(SkeletalExpressionBinding("hat", "this.ry", "age")),
        )

        val binding = SkeletalModelBinder.bind(content)
        val composed = SkeletalModelComposer.compose(base, binding)
        val attachment = binding.attachments.keys.single()

        assertTrue(composed.elements.getValue("body") !== nativeBody)
        assertEquals(nativeHead, composed.elements.getValue("head").copy(children = emptyMap()))
        assertEquals(attachment, composed.elements.getValue("head").children.getValue(attachment).transform)
        assertTrue(attachment in composed.transforms.getValue("head").children)
        assertEquals(attachment, composed.expressionAliases.getValue("hat"))
        assertEquals("head", composed.expressionAliases.getValue("head"))
    }
}
