/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.effect

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.collections.primitive.floats.FloatList
import de.bixilon.kutil.collections.primitive.ints.IntList
import de.bixilon.minosoft.data.entities.entities.AgeableMob
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.register.EntityRenderFeatures
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.SimpleTextureMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.SimpleTextureMeshBuilder.SimpleTextureMeshStruct
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.util.collections.floats.FloatListUtil
import de.bixilon.minosoft.util.collections.ints.IntListUtil
import kotlin.time.Duration

/** Native translucent entity shadow projected onto nearby terrain surfaces. */
class EntityShadowFeature(
    private val entityRenderer: EntityRenderer<*>,
) : MeshedFeature<Mesh>(entityRenderer), FeatureDrawable {
    private var key: Key? = null
    private var meshData: FloatList? = null
    private var meshIndex: IntList? = null
    private var builder: ReusableShadowMeshBuilder? = null
    private var allocatedQuads = 0
    private var pendingVertexUpdate = false

    override val updatePriority get() = EFFECT_UPDATE_PRIORITY
    override val layer get() = EntityLayer.Translucent
    override val priority get() = -100

    override fun update(delta: Duration) {
        val effect = entityRenderer.renderEffects.snapshot(entityRenderer.distance2)
        val position = entityRenderer.info.position
        val offset = entityRenderer.renderer.context.camera.offset.offset
        val babyScale = if (
            entityRenderer.entity is AgeableMob && entityRenderer.entity.isBaby
        ) BABY_SHADOW_SCALE else 1.0f
        val texture = entityRenderer.renderer.context.textures.static[EntityRenderFeatures.SHADOW_TEXTURE]
            ?: entityRenderer.renderer.features.shadowTexture
        val next = Key(
            x = position.x + effect.shadowOffset.x,
            y = position.y,
            z = position.z + effect.shadowOffset.y,
            size = effect.shadowSize * babyScale,
            opacity = effect.shadowOpacity,
            worldOcclusion = entityRenderer.renderer.session.world.occlusion,
            blockRevision = entityRenderer.renderer.session.world.blockRevision,
            chunkRevision = entityRenderer.renderer.session.world.chunks.revision,
            textureId = texture.shaderId,
            offsetX = offset.x,
            offsetY = offset.y,
            offsetZ = offset.z,
        )
        if (
            entityRenderer.entity.isInvisible ||
            next.size <= 0.0f ||
            next.opacity <= 0.0f
        ) {
            clear()
            return
        }
        if (!unload && mesh != null && key == next) return
        key = next
        val projected = EntityShadowProjector.project(
            world = entityRenderer.renderer.session.world,
            centerX = next.x,
            entityY = next.y,
            centerZ = next.z,
            radius = next.size,
            opacity = next.opacity,
        )
        if (projected.isEmpty()) {
            clear()
            return
        }
        val capacity = if (mesh == null || projected.size > allocatedQuads) {
            Integer.highestOneBit(projected.size).let { if (it == projected.size) it else it shl 1 }
        } else {
            allocatedQuads
        }
        val builder = resetBuilder(capacity)
        for (quad in projected) {
            val x0 = (quad.x0 - offset.x).toFloat()
            val x1 = (quad.x1 - offset.x).toFloat()
            val y = (quad.y - offset.y).toFloat()
            val z0 = (quad.z0 - offset.z).toFloat()
            val z1 = (quad.z1 - offset.z).toFloat()
            val color = shadowColor(quad.opacity)
            builder.addQuad(
                positions = arrayOf(
                    Vec3f(x0, y, z0),
                    Vec3f(x0, y, z1),
                    Vec3f(x1, y, z1),
                    Vec3f(x1, y, z0),
                ),
                uvs = arrayOf(
                    Vec2f(quad.u0, quad.v0),
                    Vec2f(quad.u0, quad.v1),
                    Vec2f(quad.u1, quad.v1),
                    Vec2f(quad.u1, quad.v0),
                ),
                texture = texture,
                color = color,
            )
        }
        while (builder.quads < capacity) builder.addPaddingQuad(texture)
        val current = mesh
        if (current == null || current.state != MeshStates.LOADED || capacity != allocatedQuads) {
            allocatedQuads = capacity
            mesh = builder.bake()
        } else {
            pendingVertexUpdate = true
        }
    }

    private fun resetBuilder(capacity: Int): ReusableShadowMeshBuilder {
        val floatCapacity = Math.multiplyExact(Math.multiplyExact(capacity, 4), SimpleTextureMeshStruct.floats)
        val indexCapacity = Math.multiplyExact(capacity, 6)
        val data = meshData ?: FloatListUtil.direct(floatCapacity, false).also { meshData = it }
        val index = meshIndex ?: IntListUtil.direct(indexCapacity, false).also { meshIndex = it }
        data.clear()
        index.clear()
        data.ensureSize(floatCapacity)
        index.ensureSize(indexCapacity)
        return (builder ?: ReusableShadowMeshBuilder(entityRenderer.renderer.context, data, index).also {
            builder = it
        }).also { it.reset(data, index) }
    }

    override fun prepare() {
        if (pendingVertexUpdate) {
            pendingVertexUpdate = false
            val current = mesh
            if (current != null && current.state == MeshStates.LOADED) {
                builder?.updateVertices(current)
            } else {
                mesh = builder?.bake()
            }
        }
        super<MeshedFeature>.prepare()
    }

    override fun draw(mesh: Mesh) {
        val context = entityRenderer.renderer.context
        try {
            context.system.set(RENDER_SETTINGS)
            context.shaders.entityShadowTextureShader.use()
            super.draw(mesh)
        } finally {
            context.system.set(EntityLayer.Translucent.settings)
        }
    }

    private fun clear() {
        key = null
        pendingVertexUpdate = false
        if (mesh != null) mesh = null
    }

    override fun unload() {
        var failure: Throwable? = null
        try {
            super.unload()
        } catch (error: Throwable) {
            failure = error
        }
        pendingVertexUpdate = false
        builder?.drop(free = false)
        builder = null
        try {
            meshData?.free()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        meshData = null
        try {
            meshIndex?.free()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        meshIndex = null
        allocatedQuads = 0
        failure?.let { throw it }
    }

    private fun shadowColor(opacity: Float) = RGBAColor(1.0f, 1.0f, 1.0f, opacity)

    private data class Key(
        val x: Double,
        val y: Double,
        val z: Double,
        val size: Float,
        val opacity: Float,
        val worldOcclusion: Int,
        val blockRevision: Int,
        val chunkRevision: Int,
        val textureId: Int,
        val offsetX: Int,
        val offsetY: Int,
        val offsetZ: Int,
    )

    private class ReusableShadowMeshBuilder(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        data: FloatList,
        index: IntList,
    ) : SimpleTextureMeshBuilder(context, 1, data, index) {
        override val reused: Boolean = true
        val quads: Int get() = data.size / SimpleTextureMeshStruct.floats / 4

        fun reset(data: FloatList, index: IntList) {
            _data = data
            _index = index
        }

        fun addPaddingQuad(texture: ShaderTexture) {
            repeat(4) {
                addVertex(Vec3f.EMPTY, texture, Vec2f.EMPTY, TRANSPARENT)
            }
            addIndexQuad()
        }

        private companion object {
            val TRANSPARENT = RGBAColor(1.0f, 1.0f, 1.0f, 0.0f)
        }
    }

    companion object {
        private const val BABY_SHADOW_SCALE = 0.5f
        private const val EFFECT_UPDATE_PRIORITY = 100
        val RENDER_SETTINGS = EntityLayer.Translucent.settings.copy(
            blending = true,
            faceCulling = false,
            depthMask = true,
            sourceRGB = BlendingFunctions.SOURCE_ALPHA,
            destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
            sourceAlpha = BlendingFunctions.ONE,
            destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
            depth = DepthFunctions.LESS_OR_EQUAL,
        )
    }
}
