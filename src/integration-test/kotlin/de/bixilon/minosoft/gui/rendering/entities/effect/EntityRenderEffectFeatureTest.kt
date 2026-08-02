/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.effect

import de.bixilon.kutil.reflection.ReflectionUtil.field
import de.bixilon.kutil.reflection.ReflectionUtil.getFieldOrNull
import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemRenderProperty
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.registries.blocks.state.TestBlockStates
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawer
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.register.EntityRenderFeatures
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.FileTextureLoader
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.LightColorMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil.blockPosition
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNotSame
import org.testng.Assert.assertNull
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration

@Test(groups = ["entities", "rendering"])
class EntityRenderEffectFeatureTest {
    private val meshField = MeshedFeature::class.java.getFieldOrNull("mesh")!!.field

    private val MeshedFeature<*>.currentMesh: Mesh?
        get() = meshField[this] as Mesh?

    @Suppress("UNCHECKED_CAST")
    private fun createLivingRenderer(): LivingEntityRenderer<*> {
        val entities = create(worldSize = 1)
        entities.session.world[BlockPosition(0, 0, 0)] = TestBlockStates.OPAQUE1
        val renderer = entities.create(Pig) as LivingEntityRenderer<*>
        renderer.updateVisibility(EntityVisibilityLevels.VISIBLE)
        renderer.entity.draw(now())
        return renderer
    }

    fun `shadow updates the prior loaded mesh in place`() {
        val renderer = createLivingRenderer()
        val owner = Any()

        renderer.shadow.update(Duration.ZERO)
        renderer.shadow.prepare()
        val first = requireNotNull(renderer.shadow.currentMesh)
        assertSame(MeshStates.LOADED, first.state)

        renderer.renderEffects.publish(
            owner,
            mapOf(CemRenderProperty.SHADOW_SIZE to 0.5f),
        )
        renderer.shadow.update(Duration.ZERO)
        assertSame(first, renderer.shadow.currentMesh)
        renderer.shadow.prepare()
        assertSame(MeshStates.LOADED, first.state)

        renderer.shadow.unload()
        assertNull(renderer.shadow.currentMesh)
    }

    fun `leash mesh follows native attachment state`() {
        val renderer = createLivingRenderer()
        val holder = renderer.renderer.createEntity(Pig)
        holder._id = 42
        holder.draw(now())

        renderer.entity.attachment.leashHolder = holder
        renderer.leash.update(Duration.ZERO)
        val mesh = requireNotNull(renderer.leash.currentMesh)
        assertSame(LightColorMeshBuilder.LightColorMeshStruct, mesh.buffer.struct)
        assertSame(renderer.renderer.context.light.map.buffer, renderer.renderer.context.shaders.entityLeashShader.lightmap)

        renderer.entity.attachment.leashHolder = null
        renderer.leash.update(Duration.ZERO)
        assertNull(renderer.leash.currentMesh)
    }

    fun `leash rebuilds when endpoint light changes`() {
        val renderer = createLivingRenderer()
        val holder = renderer.renderer.createEntity(Pig)
        holder._id = 42
        holder.draw(now())
        renderer.entity.attachment.leashHolder = holder

        renderer.leash.update(Duration.ZERO)
        val first = requireNotNull(renderer.leash.currentMesh)

        val position = renderer.entity.renderInfo.position
            .plus(y = renderer.entity.eyeHeight.toDouble())
            .blockPosition
        val section = requireNotNull(
            requireNotNull(renderer.renderer.session.world.chunks[position.chunkPosition])
                .sections.create(position.sectionHeight),
        )
        section.light.light[position.inSectionPosition] = LightLevel(block = 7, sky = 0)

        renderer.leash.update(Duration.ZERO)

        assertNotSame(first, renderer.leash.currentMesh)
    }

    fun `leash offset updates the prior loaded ribbon mesh in place`() {
        val renderer = createLivingRenderer()
        val holder = renderer.renderer.createEntity(Pig)
        holder._id = 42
        holder.draw(now())
        renderer.entity.attachment.leashHolder = holder

        renderer.leash.update(Duration.ZERO)
        renderer.leash.prepare()
        val first = requireNotNull(renderer.leash.currentMesh)
        assertSame(MeshStates.LOADED, first.state)

        renderer.renderEffects.publish(
            Any(),
            mapOf(CemRenderProperty.LEASH_OFFSET_X to 0.5f),
        )
        renderer.leash.update(Duration.ZERO)
        assertSame(first, renderer.leash.currentMesh)
        renderer.leash.prepare()
        assertSame(MeshStates.LOADED, first.state)

        renderer.leash.unload()
        assertNull(renderer.leash.currentMesh)
    }

    fun `shadow projection follows stepped terrain surfaces`() {
        IT.VERSION
        val session = createSession(worldSize = 1)
        session.world[BlockPosition(0, 0, 0)] = TestBlockStates.OPAQUE1
        session.world[BlockPosition(1, 1, 0)] = TestBlockStates.OPAQUE1

        val projected = EntityShadowProjector.project(
            world = session.world,
            centerX = 1.0,
            entityY = 2.0,
            centerZ = 0.5,
            radius = 1.25f,
            opacity = 1.0f,
        )

        assertEquals(projected.map { it.y }.toSet(), setOf(1.0, 2.0))
        assertEquals(projected.maxOf { it.opacity }, 0.5f)
        val lower = projected.single { it.x0 == 0.0 && it.y == 1.0 }
        assertEquals(lower.u0, 0.9f, 0.0001f)
        assertEquals(lower.u1, 0.5f, 0.0001f)
        assertEquals(lower.v0, 0.7f, 0.0001f)
        assertEquals(lower.v1, 0.3f, 0.0001f)
    }

    fun `shadow texture uses vanilla resource identity and fixed clamped sampling`() {
        IT.VERSION
        val entities = create()
        val texture = entities.features.shadowTexture

        assertSame(entities.context.textures.static[EntityRenderFeatures.SHADOW_TEXTURE], texture)
        assertEquals((texture.loader as FileTextureLoader).file, EntityRenderFeatures.SHADOW_TEXTURE)
        assertEquals(texture.mipmaps, 0)
        assertTrue("DISABLE_MIPMAPS" in entities.context.shaders.entityShadowTextureShader.native.defines)
        assertTrue("CLAMP_TEXTURE_UV" in entities.context.shaders.entityShadowTextureShader.native.defines)
        assertEquals(EntityShadowFeature.RENDER_SETTINGS.sourceRGB, BlendingFunctions.SOURCE_ALPHA)
        assertEquals(EntityShadowFeature.RENDER_SETTINGS.destinationRGB, BlendingFunctions.ONE_MINUS_SOURCE_ALPHA)
        assertEquals(EntityShadowFeature.RENDER_SETTINGS.sourceAlpha, BlendingFunctions.ONE)
        assertEquals(EntityShadowFeature.RENDER_SETTINGS.destinationAlpha, BlendingFunctions.ONE_MINUS_SOURCE_ALPHA)
        assertEquals(EntityShadowFeature.RENDER_SETTINGS.depth, DepthFunctions.LESS_OR_EQUAL)
        assertTrue(EntityShadowFeature.RENDER_SETTINGS.depthMask)
    }

    fun `shadow projection requires a full collision surface`() {
        IT.VERSION
        val session = createSession(worldSize = 1)
        session.world[BlockPosition(0, 0, 0)] = TestBlockStates.TEST1

        val projected = EntityShadowProjector.project(
            world = session.world,
            centerX = 0.5,
            entityY = 1.0,
            centerZ = 0.5,
            radius = 0.5f,
            opacity = 0.5f,
        )

        assertEquals(projected, emptyList<EntityShadowProjector.Quad>())
    }

    fun `shadow UV keeps full block bounds for the clamp sampler`() {
        IT.VERSION
        val session = createSession(worldSize = 1)
        session.world[BlockPosition(0, 0, 0)] = TestBlockStates.OPAQUE1

        val quad = EntityShadowProjector.project(
            world = session.world,
            centerX = 0.1,
            entityY = 1.0,
            centerZ = 0.1,
            radius = 0.2f,
            opacity = 0.5f,
        ).single()

        assertEquals(quad.x0, 0.0)
        assertEquals(quad.x1, 1.0)
        assertEquals(quad.u0, 0.75f, 0.0001f)
        assertEquals(quad.u1, -1.75f, 0.0001f)
    }

    fun `stationary shadow invalidates when terrain changes`() {
        val renderer = createLivingRenderer()
        renderer.shadow.update(Duration.ZERO)
        assertNotNull(renderer.shadow.currentMesh)

        renderer.renderer.session.world[BlockPosition(0, 0, 0)] = null
        renderer.shadow.update(Duration.ZERO)

        assertNull(renderer.shadow.currentMesh)
    }

    fun `late render effects update after subsequently registered model features`() {
        val renderer = create().create(Pig) as LivingEntityRenderer<*>
        val order = mutableListOf<String>()

        fun feature(name: String, priority: Int) = object : EntityRenderFeature(renderer) {
            override val updatePriority get() = priority

            override fun update(delta: Duration) {
                order += name
            }

            override fun collect(drawer: EntityDrawer) = Unit
        }.also {
            it.updateVisibility(EntityVisibilityLevels.VISIBLE)
            renderer.features.register(it)
        }

        feature("effect", 100)
        feature("model", 0)
        renderer.features.update(Duration.ZERO)

        assertEquals(order, listOf("model", "effect"))
    }
}
