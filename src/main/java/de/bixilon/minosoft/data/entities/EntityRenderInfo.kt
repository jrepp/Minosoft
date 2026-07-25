/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.entities

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.math.interpolation.FloatInterpolation.interpolateLinear
import de.bixilon.minosoft.data.Tickable
import de.bixilon.minosoft.data.entities.EntityRotation.Companion.interpolateYaw
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.Mob
import de.bixilon.minosoft.data.registries.shapes.aabb.AABB
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil
import de.bixilon.minosoft.protocol.network.session.play.tick.TickUtil
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

class EntityRenderInfo(private val entity: Entity) : Tickable {
    private var position0 = Vec3d.EMPTY
    private var position1 = entity.physics.position
    private var defaultAABB = entity.defaultAABB


    private var eyeHeight0 = 0.0f
    private var eyeHeight1 = eyeHeight0

    var position: Vec3d = position1
        private set
    var eyePosition: Vec3d = position
        private set
    var cameraAABB: AABB? = defaultAABB
        private set

    private var rotation0 = EntityRotation.EMPTY
    private var rotation1 = entity.physics.rotation
    var rotation: EntityRotation = rotation1
        private set
    private val bodyRotation = (entity as? Mob)?.let { EntityBodyRotation(rotation1.yaw, entity.physics.headYaw) }
    var bodyYaw: Float = rotation1.yaw
        private set
    var headYaw: Float = entity.physics.headYaw
        private set
    /** Current render interpolation within the entity's most recent game tick. */
    var partialTick: Float = 0.0f
        private set

    init {
        interpolateAABB(true)
    }


    private fun interpolatePosition(delta: Float) {
        val position1 = this.position1
        val eyeHeight1 = this.eyeHeight1

        if (position == position1 && eyeHeight0 == eyeHeight1) {
            interpolateAABB(false)
            return
        }

        position = Vec3dUtil.interpolateLinear(delta.toDouble(), position0, position1)

        eyePosition = position.plus(y = interpolateLinear(delta, eyeHeight0, eyeHeight1).toDouble())

        interpolateAABB(true)
    }

    private fun interpolateAABB(force: Boolean) {
        val defaultAABB = entity.defaultAABB
        if (!force && this.defaultAABB === defaultAABB) {
            return
        }
        cameraAABB = defaultAABB?.let { it + position }
    }

    private fun interpolateRotation(delta: Float) {
        val rotation1 = this.rotation1
        if (rotation != rotation1) {
            val rotation0 = this.rotation0
            rotation = EntityRotation(interpolateYaw(delta, rotation0.yaw, rotation1.yaw), interpolateLinear(delta, rotation0.pitch, rotation1.pitch))
        }

        val bodyRotation = this.bodyRotation
        if (bodyRotation == null) {
            bodyYaw = rotation.yaw
            headYaw = entity.physics.headYaw
            return
        }
        bodyYaw = bodyRotation.interpolateBody(delta)
        headYaw = bodyRotation.interpolateHead(delta)
    }

    fun draw(time: ValueTimeMark) {
        val delta = ((time - entity.lastTickTime) / TickUtil.TIME_PER_TICK).toFloat().coerceIn(0.0f, 1.0f)
        partialTick = delta
        interpolatePosition(delta)
        interpolateRotation(delta)
    }

    private fun tickPosition() {
        val entityPosition = entity.physics.position
        if (position0 == entityPosition && position1 == entityPosition) return

        position0 = position1
        position1 = entityPosition
    }

    private fun tickRotation() {
        val entityRotation = entity.physics.rotation
        if (rotation0 === entityRotation && rotation1 === entityRotation) return

        rotation0 = rotation1
        rotation1 = entityRotation
    }

    private fun tickEyeHeight() {
        val eyeHeight = entity.eyeHeight
        if (eyeHeight0 == eyeHeight && eyeHeight1 == eyeHeight) return

        eyeHeight0 = eyeHeight1
        eyeHeight1 = eyeHeight
    }

    private fun tickBodyRotation() {
        val entity = entity as? Mob ?: return
        val bodyRotation = bodyRotation ?: return
        val current = entity.physics.position
        val previous = position1
        val deltaX = current.x - previous.x
        val deltaZ = current.z - previous.z
        bodyRotation.tick(
            entityYaw = entity.physics.rotation.yaw,
            headYaw = entity.physics.headYaw,
            moving = deltaX * deltaX + deltaZ * deltaZ > MOVEMENT_THRESHOLD,
            independent = entity.primaryPassenger !is Mob,
            maxHeadRotation = entity.maxHeadRotation,
        )
        // Vanilla body control writes its moving-head clamp back to the entity.
        // Preserve that client-derived state until a later head-rotation packet
        // supplies a new value.
        entity.physics.forceSetHeadYaw(bodyRotation.currentHeadYaw)
    }

    override fun tick() {
        tickBodyRotation()
        tickPosition()
        tickEyeHeight()
        tickRotation()
    }

    private companion object {
        const val MOVEMENT_THRESHOLD = 2.500000277905201E-7
    }
}
