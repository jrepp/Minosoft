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

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.camera.target.targets.EntityTarget
import de.bixilon.minosoft.assets.model.skeletal.expression.CemExpressionVariableCatalog
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureConditions
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureContextFactory
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.entities.Poses
import de.bixilon.minosoft.data.entities.entities.AgeableMob
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.Mob
import de.bixilon.minosoft.data.entities.entities.TamableAnimal
import de.bixilon.minosoft.data.entities.entities.animal.Bee
import de.bixilon.minosoft.data.entities.entities.animal.Wolf
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.entities.entities.projectile.AbstractArrow
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.biomes.BiomePrecipitation
import de.bixilon.minosoft.data.registries.effects.attributes.MinecraftAttributes
import de.bixilon.minosoft.data.registries.fluid.fluids.LavaFluid
import de.bixilon.minosoft.data.registries.item.items.weapon.defend.DefendingItem
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil.blockPosition
import de.bixilon.minosoft.physics.entities.living.LivingEntityPhysics
import de.bixilon.minosoft.physics.parts.climbing.ClimbingPhysics.isClimbing
import kotlin.math.sqrt

/**
 * Bridges Minosoft's live entity/render state to EMF 3.0.17's expression ABI.
 *
 * Render-path-only inputs (GUI, held-item, shoulder, item-frame and hover
 * contexts) intentionally retain the catalog's false default until those
 * paths publish an explicit context.
 */
class CemEntityExpressionContextFactory(
    private val renderer: EntityRenderer<*>,
) {
    private val limb = CemLimbState()
    private var angerTimeStart = 0
    var ruleIndex = 0
    var renderPath = CemRenderPathContext.WORLD

    fun create(random: () -> Double): SkeletalExpressionContext {
        val entity = renderer.entity
        val renderInfo = renderer.info
        val renderContext = renderer.renderer.context
        val localPlayer = renderContext.session.player
        val living = entity as? LivingEntity
        val player = entity as? PlayerEntity
        val physics = entity.physics
        val position = physics.position
        val localPosition = localPlayer.physics.position
        val partialTick = renderInfo.partialTick
        val world = renderContext.session.world
        val worldTime = world.presentationTime
        val positionInfo = physics.positionInfo
        val skyHeight = positionInfo.chunk?.light?.heightmap?.get(positionInfo.position.inChunkPosition)
            ?.takeUnless { it == Int.MIN_VALUE }
        val wet = CemEntityExpressionMath.wet(
            inWater = physics.inWater,
            raining = world.presentationWeather.raining,
            dimensionWeather = world.dimension.effects.weather,
            rainBiome = positionInfo.biome?.precipitationAt(positionInfo.position) == BiomePrecipitation.RAIN,
            skyHeight = skyHeight,
            feetY = positionInfo.position.y,
            eyeY = positionInfo.eyePosition.y,
        )
        val velocity = physics.velocity
        val inGround = CemEntityExpressionMath.inGround(
            arrow = entity is AbstractArrow,
            noClip = (entity as? AbstractArrow)?.isNoClip == true,
            velocitySquared = velocity.length2(),
            collisionBlock = positionInfo.state?.flags?.let { BlockStateFlags.COLLISIONS in it } == true,
        )
        val movement = CemEntityExpressionMath.movement(velocity.x, velocity.z, renderInfo.bodyYaw)
        val baby = entity is AgeableMob && entity.isBaby
        val limbSample = limb.sample(
            age = entity.age,
            currentPosition = position,
            partialTick = partialTick,
            active = living != null && entity.attachment.vehicle == null,
            baby = baby,
        )
        val leftSwing = player?.armSwing?.progress(Arms.LEFT)
        val rightSwing = player?.armSwing?.progress(Arms.RIGHT)
        val rightHanded = when (entity) {
            is PlayerEntity -> entity.mainArm == Arms.RIGHT
            is Mob -> !entity.isLeftHanded
            is LivingEntity -> true
            else -> false
        }
        val rightSlot = if (rightHanded) EquipmentSlots.MAIN_HAND else EquipmentSlots.OFF_HAND
        val leftSlot = if (rightHanded) EquipmentSlots.OFF_HAND else EquipmentSlots.MAIN_HAND
        val usingStack = living?.usingHand?.let { living.equipment[it.slot] }
        val angerTime = when (entity) {
            is Wolf -> entity.angerTime
            is Bee -> entity.remainingAngerTimer
            else -> 0
        }
        angerTimeStart = maxOf(angerTimeStart, angerTime)
        val fluid = fluidDepth(world, position.blockPosition)
        val distanceX = position.x - localPosition.x
        val distanceY = position.y - localPosition.y
        val distanceZ = position.z - localPosition.z
        val health = living?.health ?: 1.0
        val maxHealth = living?.attributes?.get(MinecraftAttributes.MAX_HEALTH) ?: 1.0
        val entityTextureContext = EntityTextureContextFactory.create(entity)

        return CemExpressionVariableCatalog.context(
            variables = mapOf(
                "limb_swing" to limbSample.swing,
                "limb_speed" to limbSample.speed,
                "frame_time" to if (renderContext.state == RenderingStates.PAUSED) 0.0 else partialTick / 20.0,
                "age" to CemEntityExpressionMath.tickValue(entity.age.toLong(), partialTick, CemEntityExpressionMath.EMF_TICK_WRAP),
                "head_yaw" to CemEntityExpressionMath.relativeHeadYaw(renderInfo.headYaw, renderInfo.bodyYaw),
                "head_pitch" to renderInfo.rotation.pitch.toDouble(),
                "rot_x" to renderInfo.rotation.pitch.rad.toDouble(),
                "rot_y" to renderInfo.bodyYaw.rad.toDouble(),
                "player_rot_x" to localPlayer.physics.rotation.pitch.rad.toDouble(),
                "player_rot_y" to localPlayer.physics.rotation.yaw.rad.toDouble(),
                "health" to health,
                "max_health" to maxHealth,
                "hurt_time" to ((living?.hurtTime ?: 0) - partialTick).coerceAtLeast(0.0f).toDouble(),
                "death_time" to if (living == null || living.deathTime == 0) 0.0 else living.deathTime + partialTick.toDouble(),
                "id" to CemEntityExpressionMath.entityId(entity.id ?: 0),
                "dimension" to CemEntityExpressionMath.dimension(world.name?.toString()),
                "time" to CemEntityExpressionMath.tickValue(worldTime.age, partialTick, CemEntityExpressionMath.EMF_TICK_WRAP),
                "day_time" to CemEntityExpressionMath.tickValue(worldTime.age, partialTick, CemEntityExpressionMath.EMF_DAY_TIME_WRAP),
                "day_count" to worldTime.age / CemEntityExpressionMath.EMF_TICK_WRAP.toDouble(),
                "frame_counter" to CemEntityExpressionMath.frameCounter(renderContext.frameNumber),
                "swing_progress" to maxOf(leftSwing ?: 0.0f, rightSwing ?: 0.0f).toDouble(),
                "distance" to sqrt(distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ),
                "move_forward" to movement.forward,
                "move_strafing" to movement.strafing,
                "height_above_ground" to heightAboveGround(world, position.blockPosition, position.y),
                "fluid_depth" to fluid.total,
                "fluid_depth_down" to fluid.down,
                "fluid_depth_up" to fluid.up,
                "anger_time" to (angerTime - partialTick).coerceAtLeast(0.0f).toDouble(),
                "anger_time_start" to angerTimeStart.toDouble(),
                "rule_index" to ruleIndex.toDouble(),
                "is_paused" to renderContext.state.flag(RenderingStates.PAUSED),
                "is_hovered" to ((renderContext.session.camera.target.target as? EntityTarget)?.entity === entity).flag(),
                "is_first_person_hand" to renderPath.firstPersonHand.flag(),
                "is_in_hand" to renderPath.inHand.flag(),
                "is_in_item_frame" to renderPath.inItemFrame.flag(),
                "is_in_ground" to inGround.flag(),
                "is_in_gui" to renderPath.inGui.flag(),
                "is_on_head" to renderPath.onHead.flag(),
                "is_on_shoulder" to renderPath.onShoulder.flag(),
                "is_alive" to (living == null || health > 0.0).flag(),
                "is_hurt" to ((living?.hurtTime ?: 0) > 0).flag(),
                "is_burning" to entity.isOnFire.flag(),
                "is_child" to baby.flag(),
                "is_gliding" to entity.isFlyingWithElytra.flag(),
                "is_glowing" to entity.hasGlowingEffect.flag(),
                "is_in_water" to physics.inWater.flag(),
                "is_in_lava" to (physics.submersion[LavaFluid] > 0.0).flag(),
                "is_invisible" to entity.isInvisible.flag(),
                "is_on_ground" to physics.onGround.flag(),
                "is_ridden" to entity.attachment.passengers.isNotEmpty().flag(),
                "is_riding" to (entity.attachment.vehicle != null).flag(),
                "is_right_handed" to rightHanded.flag(),
                "is_sneaking" to entity.isSneaking.flag(),
                "is_sprinting" to entity.isSprinting.flag(),
                "is_swimming" to entity.isSwimming.flag(),
                "is_crawling" to (living?.pose == Poses.SWIMMING && !entity.isSwimming).flag(),
                "is_climbing" to ((physics as? LivingEntityPhysics<*>)?.isClimbing() == true).flag(),
                "is_aggressive" to ((entity as? Mob)?.isAggressive == true).flag(),
                "is_sitting" to ((entity as? TamableAnimal)?.isSitting == true).flag(),
                "is_tamed" to ((entity as? TamableAnimal)?.isTamed == true).flag(),
                "is_wet" to wet.flag(),
                "is_jumping" to ((physics as? LivingEntityPhysics<*>)?.input?.jumping == true).flag(),
                "is_swinging_left_arm" to (leftSwing != null).flag(),
                "is_swinging_right_arm" to (rightSwing != null).flag(),
                "is_holding_item_right" to (living?.equipment?.get(rightSlot) != null).flag(),
                "is_holding_item_left" to (living?.equipment?.get(leftSlot) != null).flag(),
                "is_using_item" to (living?.usingHand != null).flag(),
                "is_blocking" to (usingStack?.item is DefendingItem).flag(),
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
            random = random,
        )
    }

    private fun heightAboveGround(world: World, position: BlockPosition, entityY: Double): Double {
        for (y in position.y downTo world.dimension.minY) {
            val state = world[position.with(y = y)] ?: return 0.0
            if (BlockStateFlags.FULL_COLLISION in state.flags) return entityY - y
        }
        return 0.0
    }

    private fun fluidDepth(world: World, position: BlockPosition): CemFluidDepth {
        val state = world[position]
        if (state == null || BlockStateFlags.FLUID !in state.flags) return CemFluidDepth.ZERO

        var firstEmptyBelow = position.y - 1
        while (firstEmptyBelow >= world.dimension.minY) {
            val next = world[position.with(y = firstEmptyBelow)]
            if (next == null || BlockStateFlags.FLUID !in next.flags) break
            firstEmptyBelow--
        }
        var firstEmptyAbove = position.y + 1
        while (firstEmptyAbove <= world.dimension.maxY) {
            val next = world[position.with(y = firstEmptyAbove)]
            if (next == null || BlockStateFlags.FLUID !in next.flags) break
            firstEmptyAbove++
        }
        val down = (position.y - firstEmptyBelow).toDouble()
        val up = (firstEmptyAbove - position.y).toDouble()
        return CemFluidDepth(down + up - 1.0, down, up)
    }

    private fun Boolean.flag() = if (this) 1.0 else 0.0
    private fun <T> T.flag(expected: T) = (this == expected).flag()
}

data class CemRenderPathContext(
    val firstPersonHand: Boolean = false,
    val inHand: Boolean = false,
    val inItemFrame: Boolean = false,
    val inGui: Boolean = false,
    val onHead: Boolean = false,
    val onShoulder: Boolean = false,
) {
    companion object {
        val WORLD = CemRenderPathContext()
    }
}

private data class CemFluidDepth(
    val total: Double,
    val down: Double,
    val up: Double,
) {
    companion object {
        val ZERO = CemFluidDepth(0.0, 0.0, 0.0)
    }
}
