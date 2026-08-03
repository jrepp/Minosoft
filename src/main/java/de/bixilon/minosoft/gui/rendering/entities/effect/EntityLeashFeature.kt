/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.effect

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.collections.primitive.floats.FloatList
import de.bixilon.kutil.collections.primitive.ints.IntList
import de.bixilon.minosoft.data.entities.Poses
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.decoration.LeashFenceKnotEntity
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.LightColorMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.LightColorMeshBuilder.LightColorMeshStruct
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil.blockPosition
import de.bixilon.minosoft.util.collections.floats.FloatListUtil
import de.bixilon.minosoft.util.collections.ints.IntListUtil
import kotlin.time.Duration

/**
 * Camera-relative vanilla ribbon leash. The entity-side attachment point
 * consumes EMF's evaluated local leash offsets; the holder endpoint remains
 * native entity state populated by the attach-entity packet.
 */
class EntityLeashFeature(
    private val livingRenderer: LivingEntityRenderer<*>,
) : MeshedFeature<Mesh>(livingRenderer, EntityRenderStateKeys.LEASH), FeatureDrawable {
    private var key: Key? = null
    private var meshData: FloatList? = null
    private var meshIndex: IntList? = null
    private var builder: ReusableLeashMeshBuilder? = null
    private var pendingVertexUpdate = false

    override val updatePriority get() = EFFECT_UPDATE_PRIORITY
    override val priority get() = -50

    override fun update(delta: Duration) {
        val holder = livingRenderer.entity.attachment.leashHolder
        if (holder == null || holder.id == null) {
            clear()
            return
        }
        val effect = livingRenderer.renderEffects.snapshot(livingRenderer.distance2)
        val offset = livingRenderer.renderer.context.camera.offset.offset
        val source = EntityLeashProjector.mobAnchor(
            position = livingRenderer.info.position,
            bodyYaw = livingRenderer.info.bodyYaw,
            width = livingRenderer.entity.dimensions.x,
            standingEyeHeight = livingRenderer.entity.eyeHeight,
            emfOffset = effect.leashOffset,
        )
        val target = holderAnchor(holder)
        val start = Vec3f(
            (source.x - offset.x).toFloat(),
            (source.y - offset.y).toFloat(),
            (source.z - offset.z).toFloat(),
        )
        val end = Vec3f(
            (target.x - offset.x).toFloat(),
            (target.y - offset.y).toFloat(),
            (target.z - offset.z).toFloat(),
        )
        val startLight = endpointLight(livingRenderer.entity)
        val endLight = endpointLight(holder)
        val next = Key(start, end, startLight, endLight)
        if (!unload && mesh != null && key == next) return
        key = next
        build(start, end, startLight, endLight)
    }

    private fun holderAnchor(holder: Entity) = when (holder) {
        is LeashFenceKnotEntity -> EntityLeashProjector.knotHolderAnchor(holder.renderInfo.position)
        is PlayerEntity -> EntityLeashProjector.playerHolderAnchor(
            position = holder.renderInfo.position,
            bodyYaw = holder.renderInfo.bodyYaw,
            pitch = holder.renderInfo.rotation.pitch,
            height = holder.dimensions.y,
            rightMainArm = holder.mainArm == Arms.RIGHT,
            sneaking = holder.isSneaking,
            swimming = holder.pose == Poses.SWIMMING,
            flying = holder.isFlyingWithElytra || holder.isRiptideAttacking,
            velocity = holder.physics.velocity.unsafe,
        )

        else -> EntityLeashProjector.genericHolderAnchor(holder.renderInfo.position, holder.eyeHeight)
    }

    private fun endpointLight(entity: Entity): LightLevel {
        val eye = entity.renderInfo.position.plus(y = entity.eyeHeight.toDouble())
        var light = entity.session.world.getLight(eye.blockPosition)
        if (entity.isOnFire) {
            light = light.with(block = LightLevel.MAX_LEVEL)
        }
        return light
    }

    private fun build(start: Vec3f, end: Vec3f, startLight: LightLevel, endLight: LightLevel) {
        val builder = resetBuilder()
        for (quad in EntityLeashProjector.ribbons(start, end, startLight, endLight)) {
            builder.addVertex(quad.first0, quad.color0, quad.light0, quad.normal)
            builder.addVertex(quad.second0, quad.color0, quad.light0, quad.normal)
            builder.addVertex(quad.second1, quad.color1, quad.light1, quad.normal)
            builder.addVertex(quad.first1, quad.color1, quad.light1, quad.normal)
            builder.addIndexQuad()
        }
        val current = mesh
        if (current == null || current.state != MeshStates.LOADED) {
            mesh = builder.bake()
        } else {
            pendingVertexUpdate = true
        }
    }

    private fun resetBuilder(): ReusableLeashMeshBuilder {
        val data = meshData ?: FloatListUtil.direct(MAX_FLOATS, false).also { meshData = it }
        val index = meshIndex ?: IntListUtil.direct(MAX_INDICES, false).also { meshIndex = it }
        data.clear()
        index.clear()
        return (builder ?: ReusableLeashMeshBuilder(livingRenderer.renderer.context, data, index).also {
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
        livingRenderer.renderer.context.shaders.entityLeashShader.use()
        super.draw(mesh)
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
        failure?.let { throw it }
    }

    private data class Key(
        val start: Vec3f,
        val end: Vec3f,
        val startLight: LightLevel,
        val endLight: LightLevel,
    )

    private companion object {
        const val EFFECT_UPDATE_PRIORITY = 100
        const val QUADS = EntityLeashProjector.SEGMENTS * 2
        val MAX_FLOATS = QUADS * 4 * LightColorMeshStruct.floats
        const val MAX_INDICES = QUADS * 6
    }

    private class ReusableLeashMeshBuilder(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        data: FloatList,
        index: IntList,
    ) : LightColorMeshBuilder(context, QUADS, data, index) {
        override val reused: Boolean = true

        fun reset(data: FloatList, index: IntList) {
            _data = data
            _index = index
        }
    }
}
