/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.skeletal

import de.bixilon.kmath.vec.vec3.d.MVec3d
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.time.TimeUtil
import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEventPlayback
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEventPlaybackTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEventContext
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEvents
import de.bixilon.minosoft.data.entities.block.BlockEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.skeletal.instance.TransformInstance
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

/**
 * Target-neutral GeckoLib block-entity renderer. A route is resolved while a
 * section cache is built, then this instance retains the selected content
 * generation until the cache is replaced or unloaded.
 */
class GeckoLibBlockEntityRenderer(
    override val entity: BlockEntity,
    private val context: RenderContext,
    val contentModel: BakedSkeletalModel,
) : de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer, GeckoLibEventPlaybackTarget {
    private val skeletal = contentModel.createInstance(context)
    private var light = LightLevel.MAX
    private var lastDraw: ValueTimeMark = TimeUtil.NULL

    init {
        skeletal.neutralAnimation.eventConsumer = ::dispatch
        skeletal.geckoAnimation.eventConsumer = ::dispatch
    }

    override fun update(light: LightLevel) {
        this.light = light
    }

    override fun draw() {
        val now = now()
        val delta = if (lastDraw == TimeUtil.NULL) Duration.ZERO else now - lastDraw
        lastDraw = now

        skeletal.transform.reset()
        skeletal.animation.draw(delta)
        val state = GeckoLibAnimationState(
            ageSeconds = entity.session.world.time.age / 20.0f,
            data = mapOf("query.is_on_ground" to 1.0),
        )
        skeletal.geckoAnimation.updateState(state)
        if (skeletal.geckoAnimation.active) {
            skeletal.geckoAnimation.draw(delta, state)
        } else {
            skeletal.neutralAnimation.draw(delta)
        }
        skeletal.update(entity.position, Vec3f.EMPTY)
        skeletal.transform.transform(skeletal.matrix.unsafe)
        if (skeletal.geckoAnimation.active) {
            skeletal.geckoAnimation.dispatchEvents()
        } else {
            skeletal.neutralAnimation.dispatchEvents()
        }

        skeletal.draw(light)
        drawRenderLayers()
    }

    private fun drawRenderLayers() {
        if (skeletal.model.geckoRenderLayers.isEmpty()) return
        val system = context.system
        val shader = context.skeletal.lightmapShader
        try {
            for ((name, layer) in skeletal.model.geckoRenderLayers) {
                skeletal.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                when (layer.blend) {
                    GeckoLibRenderLayerBlend.OPAQUE -> system.reset(
                        faceCulling = false,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.TRANSLUCENT -> system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.ADDITIVE -> system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE,
                        depth = DepthFunctions.EQUAL,
                    )
                }
                shader.use()
                shader.light = (if (layer.fullBright) LightLevel.MAX else light).raw.toInt()
                skeletal.drawMesh(shader, layer.mesh)
            }
        } finally {
            system.reset()
        }
    }

    override fun load() {
        if (skeletal.state == SkeletalModelStates.PREPARING) skeletal.load()
    }

    override fun unload() {
        clearEvents()
        if (skeletal.state == SkeletalModelStates.LOADED) skeletal.unload()
    }

    override fun drop() {
        clearEvents()
        if (skeletal.state == SkeletalModelStates.PREPARING) skeletal.drop()
    }

    private fun clearEvents() {
        skeletal.neutralAnimation.clearEvents()
        skeletal.geckoAnimation.clearEvents()
    }

    private fun dispatch(animation: String, event: SkeletalAnimationEvent) {
        val eventContext = GeckoLibRuntimeEventContext(
            animation = animation,
            event = event,
            position = position(event.locator),
        )
        GeckoLibRuntimeEvents.dispatch(eventContext)
        GeckoLibEventPlayback.dispatch(eventContext, this)
    }

    override fun playSound(context: GeckoLibRuntimeEventContext, sound: ResourceLocation) {
        entity.session.world.audio?.play(sound, context.position)
    }

    override fun spawnParticle(context: GeckoLibRuntimeEventContext, particle: ResourceLocation) {
        val session = entity.session
        val renderer = session.world.particle ?: return
        val type = session.registries.particleType[particle]
            ?: return rejected(context, "Unknown particle type '$particle'.")
        val factory = type.factory
            ?: return rejected(context, "Particle type '$particle' has no native renderer factory.")
        try {
            renderer += factory.build(session, context.position, MVec3d.EMPTY, type.default())
        } catch (error: Throwable) {
            rejected(context, "Could not build particle '$particle': ${error.message}")
        }
    }

    override fun customInstruction(context: GeckoLibRuntimeEventContext) = Unit

    override fun rejected(context: GeckoLibRuntimeEventContext, reason: String) {
        Log.log(LogMessageType.RENDERING, LogLevels.VERBOSE) {
            "GECKOLIB_EVENT_REJECTED block=${entity.position} animation=${context.animation} type=${context.event.type} reason=$reason"
        }
    }

    private fun position(locator: String?): Vec3d {
        if (locator.isNullOrBlank()) {
            return Vec3d(entity.position.x + 0.5, entity.position.y + 0.5, entity.position.z + 0.5)
        }
        val transform = skeletal.transform.find(locator)
            ?: return Vec3d(entity.position.x + 0.5, entity.position.y + 0.5, entity.position.z + 0.5)
        val local = transform.matrix.unsafe * transform.pivot
        val offset = context.camera.offset.offset
        return Vec3d(local.x + offset.x, local.y + offset.y, local.z + offset.z)
    }

    private fun TransformInstance.find(name: String): TransformInstance? {
        children[name]?.let { return it }
        for (child in children.values) child.find(name)?.let { return it }
        return null
    }
}
