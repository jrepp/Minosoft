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
import de.bixilon.minosoft.assets.model.skeletal.expression.CemExpressionVariableCatalog
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.data.entities.entities.AgeableMob
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.effects.attributes.MinecraftAttributes
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterialFrame
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureConditions
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureContextFactory
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import kotlin.time.Duration
import kotlin.random.Random
import kotlin.math.sqrt

open class SkeletalFeature(
    renderer: EntityRenderer<*>,
    val instance: SkeletalInstance,
) : DrawableEntityRenderFeature(renderer) {
    protected val manager = renderer.renderer.context.skeletal
    private val rotation = MVec3f()
    private val expressionRandom = Random(renderer.entity.uuid?.hashCode() ?: renderer.entity.id ?: 0)
    private var entityTexture: EntityTextureMaterialFrame? = null
    private var entityTextures: Map<de.bixilon.minosoft.data.registries.identified.ResourceLocation, EntityTextureMaterialFrame> = emptyMap()
    private val geckoEvents = GeckoLibEntityEventConsumer(renderer, instance)

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
        val yaw = renderInfo.rotation.yaw
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
        if (instance.cemExpression.active) {
            val entity = renderer.entity
            val position = entity.physics.position
            val living = entity as? LivingEntity
            val playerEntity = entity as? PlayerEntity
            val localPlayer = renderer.renderer.context.session.player
            val localPosition = localPlayer.physics.position
            val frameTime = delta.inWholeNanoseconds / 1_000_000_000.0
            val worldTime = renderer.renderer.context.session.world.time
            val leftSwing = playerEntity?.armSwing?.progress(Arms.LEFT)
            val rightSwing = playerEntity?.armSwing?.progress(Arms.RIGHT)
            val health = living?.health ?: 0.0
            val maxHealth = living?.attributes?.get(MinecraftAttributes.MAX_HEALTH) ?: 0.0
            val distanceX = position.x - localPosition.x
            val distanceY = position.y - localPosition.y
            val distanceZ = position.z - localPosition.z
            val entityTextureContext = EntityTextureContextFactory.create(entity)
            instance.cemExpression.context = CemExpressionVariableCatalog.context(
                variables = mapOf(
                    "age" to (entity.age % 27_720) + frameTime,
                    "frame_time" to frameTime,
                    "head_yaw" to renderer.info.rotation.yaw.rad.toDouble(),
                    "head_pitch" to renderer.info.rotation.pitch.rad.toDouble(),
                    "rot_x" to renderer.info.rotation.pitch.rad.toDouble(),
                    "rot_y" to renderer.info.rotation.yaw.rad.toDouble(),
                    "player_rot_x" to localPlayer.physics.rotation.pitch.rad.toDouble(),
                    "player_rot_y" to localPlayer.physics.rotation.yaw.rad.toDouble(),
                    "health" to health,
                    "max_health" to maxHealth,
                    "id" to (entity.id ?: 0).toDouble(),
                    "time" to (worldTime.age % 27_720) + frameTime,
                    "day_time" to (worldTime.time % 31_415) + frameTime,
                    "day_count" to worldTime.age / 27_720.0,
                    "swing_progress" to maxOf(leftSwing ?: 0.0f, rightSwing ?: 0.0f).toDouble(),
                    "distance" to sqrt(distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ),
                    "is_alive" to if (living == null || health > 0.0) 1.0 else 0.0,
                    "is_burning" to if (entity.isOnFire) 1.0 else 0.0,
                    "is_child" to if (entity is AgeableMob && entity.isBaby) 1.0 else 0.0,
                    "is_gliding" to if (entity.isFlyingWithElytra) 1.0 else 0.0,
                    "is_glowing" to if (entity.hasGlowingEffect) 1.0 else 0.0,
                    "is_in_water" to if (entity.physics.inWater) 1.0 else 0.0,
                    "is_invisible" to if (entity.isInvisible) 1.0 else 0.0,
                    "is_on_ground" to if (entity.physics.onGround) 1.0 else 0.0,
                    "is_ridden" to if (entity.attachment.passengers.isNotEmpty()) 1.0 else 0.0,
                    "is_riding" to if (entity.attachment.vehicle != null) 1.0 else 0.0,
                    "is_right_handed" to if (playerEntity?.mainArm == Arms.RIGHT) 1.0 else 0.0,
                    "is_sneaking" to if (entity.isSneaking) 1.0 else 0.0,
                    "is_sprinting" to if (entity.isSprinting) 1.0 else 0.0,
                    "is_swimming" to if (entity.isSwimming) 1.0 else 0.0,
                    "is_swinging_left_arm" to if (leftSwing != null) 1.0 else 0.0,
                    "is_swinging_right_arm" to if (rightSwing != null) 1.0 else 0.0,
                    "is_using_item" to if (living?.usingHand != null) 1.0 else 0.0,
                    "player_pos_x" to localPosition.x,
                    "player_pos_y" to localPosition.y,
                    "player_pos_z" to localPosition.z,
                    "pos_x" to position.x,
                    "pos_y" to position.y,
                    "pos_z" to position.z,
                ),
                rawFunctionResolver = { name, arguments ->
                    if (name != "nbt" || arguments.size != 2) {
                        null
                    } else {
                        if (EntityTextureConditions.matchesNbt(arguments[0], arguments[1], entityTextureContext)) 1.0 else 0.0
                    }
                },
                random = { expressionRandom.nextDouble() },
            )
            instance.cemExpression.draw()
        }
        instance.transform.transform(instance.matrix.unsafe)
        if (instance.geckoAnimation.active) {
            instance.geckoAnimation.dispatchEvents()
        } else {
            instance.neutralAnimation.dispatchEvents()
        }
        entityTextures = renderer.renderer.context.models.skeletal.entityTextures(renderer.entity, instance.model)
        entityTexture = if (instance.model.entityTextureLayers.isEmpty()) entityTextures.values.singleOrNull() else null
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
