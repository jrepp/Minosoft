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
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.data.entities.entities.AgeableMob
import de.bixilon.minosoft.data.entities.entities.LivingEntity
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
    private var entityTexture: EntityTextureMaterialFrame? = null

    protected var position = Vec3d.EMPTY
    protected var yaw = 0.0f

    constructor(renderer: EntityRenderer<*>, model: BakedSkeletalModel) : this(renderer, model.createInstance(renderer.renderer.context))


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
        instance.neutralAnimation.draw(delta)
        if (instance.cemExpression.active) {
            val entity = renderer.entity
            val position = entity.physics.position
            instance.cemExpression.context = SkeletalExpressionContext(
                mapOf(
                    "age" to entity.age.toDouble(),
                    "head_yaw" to renderer.info.rotation.yaw.rad.toDouble(),
                    "head_pitch" to renderer.info.rotation.pitch.rad.toDouble(),
                    "health" to ((entity as? LivingEntity)?.health ?: 0.0),
                    "is_alive" to if (entity !is LivingEntity || entity.health > 0.0) 1.0 else 0.0,
                    "is_burning" to if (entity.isOnFire) 1.0 else 0.0,
                    "is_child" to if (entity is AgeableMob && entity.isBaby) 1.0 else 0.0,
                    "pos_x" to position.x,
                    "pos_y" to position.y,
                    "pos_z" to position.z,
                ),
                random = { expressionRandom.nextDouble() },
            )
            instance.cemExpression.draw()
        }
        instance.transform.transform(instance.matrix.unsafe)
        entityTexture = renderer.renderer.context.models.skeletal.entityTexture(renderer.entity, instance.model)
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
        val emissive = entityTexture?.emissive ?: return
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
            instance.draw(shader, emissive)
        } finally {
            system.reset()
        }
    }


    override fun unload() {
        super.unload()
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
}
