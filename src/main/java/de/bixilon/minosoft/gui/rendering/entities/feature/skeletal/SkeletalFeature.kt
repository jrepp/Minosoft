/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.MVec3f
import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.gui.rendering.entities.easteregg.EntityEasterEggs.isFlipped
import de.bixilon.minosoft.gui.rendering.entities.feature.DrawableEntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostEvents
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterialFrame
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.Colors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import kotlin.time.Duration
import kotlin.random.Random

open class SkeletalFeature(
    renderer: EntityRenderer<*>,
    val instance: SkeletalInstance,
) : DrawableEntityRenderFeature(renderer), EntityOutlineFeature {
    override val castsShadow get() = true
    override val additionalLayers: Set<EntityLayer>
        get() = if (layer == EntityLayer.Translucent) emptySet() else TRANSLUCENT_LAYER
    protected val manager = renderer.renderer.context.skeletal
    private val rotation = MVec3f()
    private val expressionRandom = Random(renderer.entity.uuid?.hashCode() ?: renderer.entity.id ?: 0)
    private val geckoHostRandom = Random((renderer.entity.uuid?.hashCode() ?: renderer.entity.id ?: 0) xor GECKO_HOST_RANDOM_SALT)
    private val expressionContext = CemEntityExpressionContextFactory(renderer)
    private var entityTexture: EntityTextureMaterialFrame? = null
    private var entityTextures: Map<de.bixilon.minosoft.data.registries.identified.ResourceLocation, EntityTextureMaterialFrame> = emptyMap()
    private val geckoEvents = GeckoLibEntityEventConsumer(renderer, instance)
    private var animationEventCursor = 0L
    private val publishesCemRenderEffects =
        instance.model.contentIdentity?.format == SkeletalContentFormat.OPTIFINE_CEM
    /**
     * Acceptance-only material override for proving the retained emissive
     * entity layer when the current scene has no authored emissive companion.
     * Normal rendering leaves this false and remains entirely asset-driven.
     */
    @Volatile
    var referenceEmissiveBaseOverride = false

    protected var position = Vec3d.EMPTY
    protected var yaw = 0.0f

    constructor(renderer: EntityRenderer<*>, model: BakedSkeletalModel) : this(renderer, model.createInstance(renderer.renderer.context))

    init {
        instance.neutralAnimation.eventConsumer = geckoEvents::dispatch
        instance.geckoAnimation.eventConsumer = geckoEvents::dispatch
    }

    // TODO. free instance when out of view distance?


    protected open fun updatePosition() {
        val renderInfo = renderer.info
        val yaw = renderInfo.bodyYaw
        val position = renderInfo.position

        var changes = 0
        if (this.position != position) {
            changes++
            this.position = position
        }
        if (this.yaw != yaw) {
            changes++
            this.yaw = yaw
        }
        if (changes == 0) return
        if (renderer.entity.isFlipped()) {
            this.yaw *= -1.0f
        }
        updateInstance()
    }

    protected open fun updateInstance() {
        this.rotation.y = -yaw.rad
        instance.update(this.rotation.unsafe, renderer.matrix.unsafe)
    }

    override fun update(delta: Duration) {
        super.update(delta)
        instance.transform.reset()
        updatePosition()
        instance.animation.draw(delta)
        replayEntityAnimations()
        val geckoState = if (instance.model.contentIdentity?.format == SkeletalContentFormat.GECKOLIB) {
            val entity = renderer.entity
            val velocity = entity.physics.velocity
            GeckoLibAnimationState(
                ageSeconds = entity.age.coerceAtLeast(0) / 20.0f,
                moving = velocity.x * velocity.x + velocity.z * velocity.z > MOVEMENT_EPSILON_SQUARED,
                data = buildMap {
                    put("query.is_on_ground", if (entity.physics.onGround) 1.0 else 0.0)
                    put("query.is_in_water", if (entity.physics.inWater) 1.0 else 0.0)
                    put("query.is_sneaking", if (entity.isSneaking) 1.0 else 0.0)
                    put("query.is_sprinting", if (entity.isSprinting) 1.0 else 0.0)
                    put("query.is_swimming", if (entity.isSwimming) 1.0 else 0.0)
                    putAll(instance.geckoAnimation.resolveTrackedData(entity.data::raw))
                    putAll(instance.geckoAnimation.resolveHostState { input ->
                        GeckoLibEntityHostStateResolver.resolve(entity, input, geckoHostRandom)
                    })
                },
            )
        } else {
            null
        }
        if (geckoState != null) instance.geckoAnimation.updateState(geckoState)
        if (instance.geckoAnimation.active) {
            instance.geckoAnimation.draw(delta, requireNotNull(geckoState))
        } else {
            instance.neutralAnimation.draw(delta)
        }
        entityTextures = renderer.renderer.context.models.skeletal.entityTextures(renderer.entity, instance.model)
        entityTexture = if (instance.model.entityTextureLayers.isEmpty()) entityTextures.values.singleOrNull() else null
        expressionContext.ruleIndex = entityTexture?.ruleIndex
            ?: entityTextures.values.firstOrNull()?.ruleIndex
            ?: 0
        if (instance.cemExpression.active) {
            instance.cemExpression.context = expressionContext.create(expressionRandom::nextDouble)
            instance.cemExpression.draw()
            if (publishesCemRenderEffects) {
                renderer.renderEffects.publish(this, instance.cemExpression.render)
            }
        }
        instance.transform.transform(instance.matrix.unsafe)
        if (instance.geckoAnimation.active) {
            instance.geckoAnimation.dispatchEvents()
        } else {
            instance.neutralAnimation.dispatchEvents()
        }
        instance.material = entityTexture?.base
    }

    private fun replayEntityAnimations() {
        if (!instance.geckoAnimation.active) return
        val entity = renderer.entity
        val batch = entity.animationEvents.readAfter(animationEventCursor)
        animationEventCursor = batch.latestSequence
        for (event in batch.events) {
            val age = entity.age - event.entityAge
            if (age !in 0..MAX_REPLAY_AGE_TICKS) continue
            instance.geckoAnimation.triggerEvent(GeckoLibHostEvents.entityAnimation(event.animation))
        }
    }

    override fun prepare() {
        super<DrawableEntityRenderFeature>.prepare()
        if (instance.state == SkeletalModelStates.PREPARING) {
            instance.load()
        }
    }

    override fun draw() {
        manager.shader.entityColor = entityColor()
        val tint = tint()
        instance.draw(tint)
        if (instance.model.entityTextureLayers.isNotEmpty()) {
            val shader = manager.shader
            for ((base, layer) in instance.model.entityTextureLayers) {
                val texture = entityTextures[base]?.base ?: base
                layer.meshes[texture]?.let { instance.drawMesh(shader, it) }
            }
        }
        drawGeckoRenderLayers(tint, translucent = false)
    }

    override fun drawLayer(layer: EntityLayer) {
        if (layer == this.layer) return draw()
        if (layer != EntityLayer.Translucent) return
        manager.shader.entityColor = entityColor()
        val tint = tint()
        drawGeckoRenderLayers(tint, translucent = true)
        drawEmissive()
    }

    private fun tint(): de.bixilon.minosoft.data.text.formatting.color.RGBColor {
        return modelTint(renderer.light.value)
    }

    private fun entityColor() = (renderer as? LivingEntityRenderer<*>)
        ?.let { IrisEntityOverlay.resolve(it.entity) }
        ?: de.bixilon.kmath.vec.vec4.f.Vec4f.EMPTY

    private fun drawEmissive() {
        val emissiveMeshes = if (referenceEmissiveBaseOverride) {
            referenceEmissiveBaseMeshes()
        } else if (instance.model.entityTextureLayers.isEmpty()) {
            entityTexture?.emissive?.let { emissive ->
                listOf(instance.model.mesh(emissive))
            }.orEmpty()
        } else {
            instance.model.entityTextureLayers.mapNotNull { (base, layer) ->
                entityTextures[base]?.emissive?.let(layer.meshes::get)
            }
        }
        if (emissiveMeshes.isEmpty()) return
        val system = renderer.renderer.context.system
        try {
            system.reset(
                blending = true,
                faceCulling = false,
                depthMask = false,
                sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                destinationRGB = BlendingFunctions.ONE,
                sourceAlpha = BlendingFunctions.ONE,
                destinationAlpha = BlendingFunctions.ONE,
                depth = DepthFunctions.EQUAL,
            )
            val shader = manager.shader
            shader.withProgramFamily(SceneProgramFamily.ENTITY_EYES) {
                shader.tint = ChatColors.WHITE.rgb()
                emissiveMeshes.forEach { instance.drawMesh(shader, it) }
            }
        } finally {
            system.reset()
        }
    }

    val referenceEmissiveBaseMeshCount: Int
        get() = referenceEmissiveBaseMeshes().size

    private fun referenceEmissiveBaseMeshes() = if (instance.model.entityTextureLayers.isEmpty()) {
        listOf(instance.model.mesh(entityTexture?.base ?: instance.material ?: instance.model.entityTextureBase))
    } else {
        instance.model.entityTextureLayers.mapNotNull { (base, layer) ->
            layer.meshes[entityTextures[base]?.base ?: base]
        }
    }

    /**
     * Allows stateful render layers such as dyed sheep wool to preserve the
     * entity light/damage tint while adding their own material color.
     */
    protected open fun modelTint(tint: de.bixilon.minosoft.data.text.formatting.color.RGBColor) = tint

    override fun drawOutline(color: RGBAColor) {
        val system = renderer.renderer.context.system
        val shader = manager.shader
        try {
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
            shader.outlineColor = color
            instance.draw(shader)
            if (instance.model.entityTextureLayers.isNotEmpty()) {
                for ((base, layer) in instance.model.entityTextureLayers) {
                    val texture = entityTextures[base]?.base ?: base
                    layer.meshes[texture]?.let { instance.drawMesh(shader, it) }
                }
            }
            for ((name, layer) in instance.model.geckoRenderLayers) {
                instance.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                instance.drawMesh(shader, layer.mesh)
            }
            if (instance.model.entityTextureLayers.isEmpty()) {
                entityTexture?.emissive?.let { instance.drawMesh(shader, instance.model.mesh(it)) }
            } else {
                for ((base, layer) in instance.model.entityTextureLayers) {
                    entityTextures[base]?.emissive?.let(layer.meshes::get)?.let { instance.drawMesh(shader, it) }
                }
            }
        } finally {
            shader.outlineColor = Colors.TRANSPARENT
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
        }
    }

    private fun drawGeckoRenderLayers(
        tint: de.bixilon.minosoft.data.text.formatting.color.RGBColor,
        translucent: Boolean,
    ) {
        if (instance.model.geckoRenderLayers.isEmpty()) return
        val system = renderer.renderer.context.system
        val shader = manager.shader
        try {
            for ((name, layer) in instance.model.geckoRenderLayers) {
                if ((layer.blend != GeckoLibRenderLayerBlend.OPAQUE) != translucent) continue
                instance.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
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
                val drawLayer = {
                    shader.tint = if (layer.fullBright) ChatColors.WHITE.rgb() else tint
                    instance.drawMesh(shader, layer.mesh)
                }
                if (!layer.fullBright || layer.blend != GeckoLibRenderLayerBlend.ADDITIVE) {
                    shader.use()
                    drawLayer()
                } else {
                    shader.withProgramFamily(SceneProgramFamily.ENTITY_EYES, drawLayer)
                }
            }
        } finally {
            system.reset()
        }
    }


    override fun unload() {
        super.unload()
        if (publishesCemRenderEffects) renderer.renderEffects.clear(this)
        instance.neutralAnimation.clearEvents()
        instance.geckoAnimation.clearEvents()
        if (instance.state == SkeletalModelStates.PREPARING) {
            instance.drop()
        } else {
            instance.unload()
        }
    }

    override fun invalidate() {
        super.invalidate()
        this.position = Vec3d.EMPTY
        this.yaw = 0.0f
    }

    private companion object {
        val TRANSLUCENT_LAYER = setOf<EntityLayer>(EntityLayer.Translucent)
        const val GECKO_HOST_RANDOM_SALT = 0x4D534746
        const val MOVEMENT_EPSILON_SQUARED = 1.0E-7
        const val MAX_REPLAY_AGE_TICKS = 2
    }
}
