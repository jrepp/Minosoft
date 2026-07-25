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
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterialFrame
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import kotlin.time.Duration
import kotlin.random.Random

open class SkeletalFeature(
    renderer: EntityRenderer<*>,
    val instance: SkeletalInstance,
) : DrawableEntityRenderFeature(renderer) {
    protected val manager = renderer.renderer.context.skeletal
    private val rotation = MVec3f()
    private val expressionRandom = Random(renderer.entity.uuid?.hashCode() ?: renderer.entity.id ?: 0)
    private val expressionContext = CemEntityExpressionContextFactory(renderer)
    private var entityTexture: EntityTextureMaterialFrame? = null
    private var entityTextures: Map<de.bixilon.minosoft.data.registries.identified.ResourceLocation, EntityTextureMaterialFrame> = emptyMap()
    private val geckoEvents = GeckoLibEntityEventConsumer(renderer, instance)
    private val publishesCemRenderEffects =
        instance.model.contentIdentity?.format == SkeletalContentFormat.OPTIFINE_CEM

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
        val geckoState = if (instance.model.contentIdentity?.format == SkeletalContentFormat.GECKOLIB) {
            val entity = renderer.entity
            val velocity = entity.physics.velocity
            GeckoLibAnimationState(
                ageSeconds = entity.age.coerceAtLeast(0) / 20.0f,
                moving = velocity.x * velocity.x + velocity.z * velocity.z > MOVEMENT_EPSILON_SQUARED,
                data = mapOf(
                    "query.is_on_ground" to if (entity.physics.onGround) 1.0 else 0.0,
                    "query.is_in_water" to if (entity.physics.inWater) 1.0 else 0.0,
                    "query.is_sneaking" to if (entity.isSneaking) 1.0 else 0.0,
                    "query.is_sprinting" to if (entity.isSprinting) 1.0 else 0.0,
                    "query.is_swimming" to if (entity.isSwimming) 1.0 else 0.0,
                ),
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

    override fun prepare() {
        super.prepare()
        if (instance.state == SkeletalModelStates.PREPARING) {
            instance.load()
        }
    }

    override fun draw() {
        var tint = renderer.light.value
        if (renderer is LivingEntityRenderer<*>) {
            tint *= renderer.damage.value
        }
        instance.draw(tint)
        if (instance.model.entityTextureLayers.isNotEmpty()) {
            val shader = manager.shader
            for ((base, layer) in instance.model.entityTextureLayers) {
                val texture = entityTextures[base]?.base ?: base
                layer.meshes[texture]?.let { instance.drawMesh(shader, it) }
            }
        }
        drawGeckoRenderLayers(tint)
        val emissiveMeshes = if (instance.model.entityTextureLayers.isEmpty()) {
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
            shader.use()
            shader.tint = ChatColors.WHITE.rgb()
            emissiveMeshes.forEach { instance.drawMesh(shader, it) }
        } finally {
            system.reset()
        }
    }

    private fun drawGeckoRenderLayers(tint: de.bixilon.minosoft.data.text.formatting.color.RGBColor) {
        if (instance.model.geckoRenderLayers.isEmpty()) return
        val system = renderer.renderer.context.system
        val shader = manager.shader
        try {
            for ((name, layer) in instance.model.geckoRenderLayers) {
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
                shader.use()
                shader.tint = if (layer.fullBright) ChatColors.WHITE.rgb() else tint
                instance.drawMesh(shader, layer.mesh)
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
        const val MOVEMENT_EPSILON_SQUARED = 1.0E-7
    }
}
